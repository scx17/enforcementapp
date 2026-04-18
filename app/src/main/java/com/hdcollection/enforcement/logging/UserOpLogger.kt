package com.hdcollection.enforcement.logging

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hdcollection.enforcement.BuildConfig
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.data.db.AppDatabase
import com.hdcollection.enforcement.data.db.UserOpLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 执法仪端用户操作审计日志记录器。
 * - record(...) 仅写本地 Room 队列，主链路不阻塞
 * - critical=true 立刻 flush；普通事件攒够 [FLUSH_BATCH_SIZE] 条或周期 30 秒 flush
 * - flush 失败保留队列并 retryCount++，由 WorkManager 周期触发补传
 *
 * 设计文档: docs/plans/2026-04-12-user-operation-log-design.md §4.2
 */
object UserOpLogger {
    private const val FLUSH_BATCH_SIZE = 20
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val UPLOAD_CHUNK = 50

    private lateinit var appContext: Context
    private lateinit var settings: AppSettings
    private val uploadMutex = Mutex()

    @Volatile private var initialized = false
    @Volatile private var periodicJob: Job? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Application.onCreate 中调用一次 */
    fun init(context: Context, appSettings: AppSettings) {
        if (initialized) return
        appContext = context.applicationContext
        settings = appSettings
        initialized = true

        // 启动时先尝试把积压的补传一次（上一次进程挂掉可能留下数据）
        CoroutineScope(Dispatchers.IO).launch { flush() }

        // 常驻协程：每 30 秒检查一次队列
        periodicJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                } catch (e: Exception) {
                    Timber.w(e, "UserOpLogger 周期 flush 异常")
                }
            }
        }
        Timber.i("UserOpLogger 已初始化")
    }

    /**
     * 记录一条审计事件。
     * @param critical true 表示关键事件，写库后立即触发 flush（Login/Logout/SOS/StartRecording/响应平台指令）
     */
    fun record(
        operationType: String,
        description: String,
        targetType: String? = null,
        targetId: String? = null,
        extraData: String? = null,
        critical: Boolean = false
    ) {
        if (!initialized) {
            Timber.w("UserOpLogger 未初始化: $operationType")
            return
        }
        val lat = runCatching { (appContext as? com.hdcollection.enforcement.EnforcementApp)?.locationService?.getLatitude() }.getOrNull()
        val lon = runCatching { (appContext as? com.hdcollection.enforcement.EnforcementApp)?.locationService?.getLongitude() }.getOrNull()

        val entity = UserOpLogEntity(
            operationType = operationType,
            description = description,
            targetType = targetType,
            targetId = targetId,
            clientTime = System.currentTimeMillis(),
            networkType = detectNetworkType(),
            gpsLat = lat?.takeIf { it != 0.0 },
            gpsLon = lon?.takeIf { it != 0.0 },
            appVersion = BuildConfig.VERSION_NAME,
            extraData = extraData
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(appContext).userOpLogDao()
                dao.insert(entity)
                val count = dao.count()
                if (critical || count >= FLUSH_BATCH_SIZE) {
                    flush()
                }
            } catch (e: Exception) {
                Timber.w(e, "UserOpLogger 写入队列失败: $operationType")
            }
        }
    }

    /**
     * 尝试上报队列里所有待发送事件。WorkManager 也会调用此方法。
     * 返回成功上报的条数。
     */
    suspend fun flush(): Int {
        if (!initialized) return 0
        return uploadMutex.withLock { doFlush() }
    }

    private suspend fun doFlush(): Int {
        val dao = AppDatabase.getInstance(appContext).userOpLogDao()
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) return 0

        var totalSent = 0
        while (true) {
            val batch = dao.peek(UPLOAD_CHUNK)
            if (batch.isEmpty()) break

            val ok = uploadBatch(batch)
            if (ok) {
                dao.deleteByIds(batch.map { it.id })
                totalSent += batch.size
            } else {
                dao.incrementRetry(batch.map { it.id })
                break  // 网络失败就停止，等下轮
            }
        }
        if (totalSent > 0) Timber.i("UserOpLogger.flush: 上报 $totalSent 条")
        return totalSent
    }

    private fun uploadBatch(batch: List<UserOpLogEntity>): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("deviceId", settings.deviceId)
                put("logs", JSONArray().apply {
                    val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    batch.forEach { e ->
                        put(JSONObject().apply {
                            put("operationType", e.operationType)
                            put("description", e.description)
                            e.targetType?.let { put("targetType", it) }
                            e.targetId?.let { put("targetId", it) }
                            put("clientTime", dateFmt.format(java.util.Date(e.clientTime)))
                            e.networkType?.let { put("networkType", it) }
                            e.gpsLat?.let { put("gpsLat", it) }
                            e.gpsLon?.let { put("gpsLon", it) }
                            e.appVersion?.let { put("appVersion", it) }
                            e.extraData?.let { put("extraData", it) }
                        })
                    }
                })
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${settings.platformApiUrl}/api/user-operlog/batch")
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) true
                else {
                    Timber.w("UserOpLogger.upload HTTP ${resp.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "UserOpLogger.upload 失败")
            false
        }
    }

    private fun detectNetworkType(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "offline"
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4g"
            else -> "other"
        }
    }
}
