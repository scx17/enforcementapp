package com.hdcollection.enforcement

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import com.hdcollection.enforcement.config.RemoteConfigManager
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.logging.FileLoggingTree
import com.hdcollection.enforcement.logging.UserOpLogger
import com.hdcollection.enforcement.notification.PlatformNotificationService
import com.hdcollection.enforcement.service.AlarmReporter
import com.hdcollection.enforcement.service.LogUploadWorker
import com.hdcollection.enforcement.service.UploadWorker
import com.hdcollection.enforcement.service.UserOpLogUploadWorker
import com.hdcollection.enforcement.sip.SipManager
import com.hdcollection.enforcement.upload.SnapshotUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EnforcementApp : Application() {

    lateinit var logFile: File
        private set

    lateinit var notificationService: PlatformNotificationService
        private set

    lateinit var sipManager: SipManager
        private set

    lateinit var locationService: com.hdcollection.enforcement.service.LocationService
        private set

    lateinit var fileSyncService: com.hdcollection.enforcement.sync.FileListSyncService
        private set

    lateinit var snapshotUploader: SnapshotUploader
        private set

    lateinit var remoteConfigManager: RemoteConfigManager
        private set

    private val pullMutex = kotlinx.coroutines.sync.Mutex()

    override fun onCreate() {
        super.onCreate()

        // 初始化日志文件（外部存储，便于导出）
        val logDir = getExternalFilesDir("logs") ?: filesDir
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        logFile = File(logDir, "app_$dateStr.log")

        // 挂载 Timber：Debug 模式同时输出到 Logcat，始终输出到文件
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            if (DeviceHardwareManager.isAvailable()) {
                DeviceHardwareManager.setUsbDisk(false)
                Timber.d("debug 包: 已重置 USB 为 ADB 模式")
            }
        }
        Timber.plant(FileLoggingTree(logFile))

        Timber.i("EnforcementApp started, log file: ${logFile.absolutePath}")

        // 初始化 SIP 对讲
        val settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        // 卸载重装后 SharedPreferences 全清，写入编译时兜底 platformApiUrl，
        // 让 MainActivity.syncCustomCodeFromPlatform 能联网调 /api/device/me?imei=xxx
        // 自动恢复 deviceId/customCode/sipServer。免现场扫码。
        if (settings.platformApiUrl.isEmpty()) {
            val fallback = com.hdcollection.enforcement.BuildConfig.DEFAULT_PLATFORM_API_URL
            if (fallback.isNotEmpty()) {
                settings.platformApiUrl = fallback
                Timber.i("EnforcementApp: 使用编译时兜底 platformApiUrl=$fallback")
            }
        }

        // 初始化用户操作审计（需要在任何 record 调用之前完成）
        UserOpLogger.init(this, settings)

        sipManager = SipManager(settings)
        // 升级模块的"是否对讲中"判断要查 sipManager.state
        com.hdcollection.enforcement.upgrade.AppBusyState.sipManagerProvider = { sipManager }

        // 锁屏白名单:Device Owner 时把自己加入 LockTask 白名单,
        // MainActivity 进入 Lock Task Mode 时无需用户确认(否则系统会弹"屏幕固定?")
        try {
            val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isDeviceOwnerApp(packageName)) {
                val admin = android.content.ComponentName(
                    this, com.hdcollection.enforcement.upgrade.AppDeviceAdminReceiver::class.java)
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                Timber.i("LockTask 白名单已设置: $packageName (Device Owner)")
            } else {
                Timber.w("非 Device Owner,LockTask 进入时会弹系统确认窗")
            }
        } catch (t: Throwable) {
            Timber.w(t, "setLockTaskPackages 失败")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sipManager.start()
                Timber.i("SipManager 启动成功")
            } catch (e: Exception) {
                Timber.e(e, "SipManager 启动异常")
            }
        }

        // 初始化 GPS 定位
        locationService = com.hdcollection.enforcement.service.LocationService(this)
        try {
            locationService.start()
            Timber.i("LocationService 启动成功")
        } catch (e: Exception) {
            Timber.e(e, "LocationService 启动异常")
        }

        // 初始化远程配置管理器（先于 SignalR 连接，确保监听启动时 manager 已就绪）
        remoteConfigManager = RemoteConfigManager(this, settings)
        Timber.i("RemoteConfigManager 初始化完成: version=${remoteConfigManager.config.value.version}")

        // 上报硬件 profile，平台据此显示设备硬件分级与推荐配置
        com.hdcollection.enforcement.config.HardwareProfileReporter.reportAsync(this, settings)

        // 初始化 SignalR 平台通知
        notificationService = PlatformNotificationService(this, settings)
        notificationService.onConfigPushReceived = { json ->
            remoteConfigManager.applyPush(json)
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notificationService.connect()
                Timber.i("PlatformNotificationService 连接成功")
            } catch (e: Exception) {
                Timber.e(e, "PlatformNotificationService 连接失败")
            }
        }

        // 文件列表定期同步（每 5 分钟，上报本地文件清单到服务器）
        val syncClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        fileSyncService = com.hdcollection.enforcement.sync.FileListSyncService(this, settings, syncClient)
        snapshotUploader = SnapshotUploader(this, settings)
        Timber.i("SnapshotUploader 初始化完成")
        val syncTimer = java.util.Timer()
        syncTimer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                CoroutineScope(Dispatchers.IO).launch {
                    fileSyncService.syncFileList()
                }
            }
        }, 30_000, 5 * 60 * 1000) // 30秒后开始，每5分钟一次
        Timber.i("文件列表同步定时器已启动（每5分钟）")

        // 监听服务器 PullFile 命令 → 上传指定文件（串行化避免并发竞争）
        notificationService.onPullFileRequested = { fileName ->
            UserOpLogger.record(
                operationType = "RespondPullFile",
                description = "响应平台拉取文件指令 $fileName",
                targetType = "file",
                targetId = fileName,
                critical = true
            )
            CoroutineScope(Dispatchers.IO).launch {
                pullMutex.withLock {
                    pullAndUploadFile(fileName, settings)
                }
            }
        }

        // 监听 PullLog 命令 → 立即上传所有日志（含当天），用于远程排障
        notificationService.onPullLogRequested = {
            UserOpLogger.record(
                operationType = "RespondPullLog",
                description = "响应平台强制拉取日志指令",
                targetType = "log",
                targetId = null,
                critical = true
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val logUploaderClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(2, TimeUnit.MINUTES)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                    val logDir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
                    val uploader = com.hdcollection.enforcement.logging.LogUploader(settings, logUploaderClient, logDir)
                    uploader.uploadAllIncludingToday()
                    Timber.i("PullLog 响应完成")
                } catch (e: Exception) {
                    Timber.e(e, "PullLog 响应失败")
                }
            }
        }

        // WorkManager 注册移到后台线程：首次安装时 Room DB 建表可能耗时 2-3 秒，阻塞主线程会触发 ANR
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val wm = WorkManager.getInstance(this@EnforcementApp)
                // 取消旧版自动上传任务（已改为服务器拉取模式）
                wm.cancelUniqueWork("auto_upload")
                Timber.i("已取消旧版自动上传任务")

                val logUploadRequest = PeriodicWorkRequestBuilder<LogUploadWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork("log_upload", ExistingPeriodicWorkPolicy.KEEP, logUploadRequest)
                Timber.i("WorkManager 日志上传任务已注册: log_upload (每6小时)")

                val opLogUploadRequest = PeriodicWorkRequestBuilder<UserOpLogUploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork("user_oplog_upload", ExistingPeriodicWorkPolicy.KEEP, opLogUploadRequest)
                Timber.i("WorkManager 用户操作审计补传任务已注册: user_oplog_upload (每15分钟)")

                val storageCleanRequest = PeriodicWorkRequestBuilder<com.hdcollection.enforcement.service.StorageCleanupWorker>(
                    1, TimeUnit.DAYS
                ).build()
                wm.enqueueUniquePeriodicWork("storage_cleanup", ExistingPeriodicWorkPolicy.KEEP, storageCleanRequest)
                Timber.i("WorkManager 存储清理任务已注册: storage_cleanup (每日)")

                // 远程升级周期检查：1H 周期 + 15m flex（启动时间错峰，减少集中下载带宽峰值）
                val upgradeRequest = PeriodicWorkRequestBuilder<com.hdcollection.enforcement.upgrade.UpgradeWorker>(
                    1, TimeUnit.HOURS,
                    15, TimeUnit.MINUTES
                ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                // REPLACE 而非 KEEP——本机器以前注册过 1H 任务但需求改了的话能覆盖
                wm.enqueueUniquePeriodicWork("app_upgrade_check",
                    ExistingPeriodicWorkPolicy.UPDATE, upgradeRequest)
                Timber.i("WorkManager 升级检查任务已注册: app_upgrade_check (每1小时)")

                // 启动时立即跑一次 OneTimeWorkRequest 检查，不等 1H 周期
                val upgradeOnce = androidx.work.OneTimeWorkRequestBuilder<com.hdcollection.enforcement.upgrade.UpgradeWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(20, TimeUnit.SECONDS)  // 等设备配置加载、SIP 注册完成后再检查
                    .build()
                wm.enqueue(upgradeOnce)
                Timber.i("WorkManager 启动检查已入队: 20s 后立即触发 UpgradeWorker")
            } catch (e: Exception) {
                Timber.e(e, "WorkManager 注册失败")
            }
        }

        // 订阅远程配置：GPS 热切换
        CoroutineScope(Dispatchers.IO).launch {
            remoteConfigManager.config.collect { cfg ->
                try {
                    locationService.applyConfig(cfg.gpsEnabled, cfg.gpsIntervalSeconds)
                } catch (e: Exception) {
                    Timber.e(e, "LocationService applyConfig 失败")
                }
            }
        }

        // 注册低电量告警广播监听
        val batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                Timber.w("低电量告警: level=$level%")
                val reporter = AlarmReporter(settings)
                CoroutineScope(Dispatchers.IO).launch {
                    reporter.report(
                        "low_battery", 2, "电池低压报警",
                        "设备 ${settings.deviceId} 电量不足 ${level}%"
                    )
                }
            }
        }
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_LOW))
        Timber.i("低电量告警监听已注册")

        // 设备心跳定时器（每 15 秒上报一次，维持在线状态）
        val heartbeatClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val heartbeatFailCount = java.util.concurrent.atomic.AtomicInteger(0)
        val heartbeatTimer = java.util.Timer()
        heartbeatTimer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) return
                try {
                    val snap = com.hdcollection.enforcement.device.DeviceStatusCollector.collect(this@EnforcementApp)
                    val jsonObj = org.json.JSONObject().apply {
                        put("deviceId", settings.deviceId)
                        snap.battery?.let { put("battery", it) }
                        snap.charge?.let { put("charge", it) }
                        snap.signal?.let { put("signal", it) }
                        snap.networkType?.let { put("networkType", it) }
                        snap.storageRemaining?.let { put("storageRemaining", it) }
                        // App 版本（用于平台监控版本分布 + 升级链路决策）
                        val vc = com.hdcollection.enforcement.upgrade.UpgradeManager.getCurrentVersionCode(this@EnforcementApp)
                        val vn = com.hdcollection.enforcement.upgrade.UpgradeManager.getCurrentVersionName(this@EnforcementApp)
                        if (vc > 0) put("appVersionCode", vc)
                        if (vn.isNotEmpty()) put("appVersionName", vn)
                    }
                    val json = jsonObj.toString()
                    val body = okhttp3.RequestBody.create(
                        "application/json".toMediaType(), json.toByteArray()
                    )
                    val request = okhttp3.Request.Builder()
                        .url("${settings.platformApiUrl}/api/map/heartbeat")
                        .post(body)
                        .build()
                    heartbeatClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val prev = heartbeatFailCount.getAndSet(0)
                            if (prev > 0) {
                                Timber.i("心跳恢复: 此前连续失败 $prev 次")
                            }
                        } else {
                            val cnt = heartbeatFailCount.incrementAndGet()
                            Timber.w("心跳 HTTP ${resp.code}, 连续失败 $cnt 次")
                            if (cnt == 3) notificationService.triggerReconnect()
                        }
                    }
                } catch (t: Throwable) {
                    // 用 Throwable 兜底：Timer 线程上的未捕获 Error（如 NoSuchMethodError）
                    // 会让整个进程崩溃，必须在此拦截。
                    val cnt = heartbeatFailCount.incrementAndGet()
                    Timber.w(t, "心跳异常: ${t.message}, 连续失败 $cnt 次")
                    if (cnt == 3) {
                        Timber.e("心跳连续失败 3 次，触发 SignalR 立即重连")
                        notificationService.triggerReconnect()
                    }
                }
            }
        }, 5000, 15000) // 5秒后开始，每15秒一次
        Timber.i("设备心跳定时器已启动（每15秒）")
    }

    /** 服务器拉取命令回调：查找本地文件并上传 */
    private suspend fun pullAndUploadFile(fileName: String, settings: AppSettings) {
        val recDir = File(filesDir, "recordings")
        val photoDir = File(filesDir, "photos")
        val audioDir = File(filesDir, "audios")
        val file = listOfNotNull(
            recDir?.let { java.io.File(it, fileName) },
            photoDir?.let { java.io.File(it, fileName) },
            audioDir?.let { java.io.File(it, fileName) }
        ).firstOrNull { it.exists() }

        if (file == null) {
            Timber.w("PullFile: 本地文件不存在: $fileName, 上报失败给服务器")
            reportPullFileMissing(fileName, settings)
            return
        }

        val fileType = when (file.extension.lowercase()) {
            "mp4", "3gp" -> "video"
            "m4a", "aac", "mp3" -> "audio"
            else -> "image"
        }
        val uploadClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
        val uploadService = com.hdcollection.enforcement.upload.UploadService(
            com.hdcollection.enforcement.data.db.AppDatabase.getInstance(this).uploadQueueDao(),
            settings, uploadClient, this
        )

        uploadService.enqueue(
            deviceId = settings.deviceId,
            fileType = fileType,
            file = file,
            lat = null, lng = null,
            recordTime = file.lastModified()
        )
        uploadService.processPendingUploads()
        Timber.i("PullFile 上传完成: $fileName")
    }

    /** 文件不存在时，上报 status=3 让后端知道拉取失败 */
    private suspend fun reportPullFileMissing(fileName: String, settings: AppSettings) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                val json = """[{"deviceId":"${settings.deviceId}","fileName":"$fileName","fileSize":0,"uploadedSize":0,"status":3,"fileType":"video","recordTime":"${dateFormat.format(java.util.Date())}"}]"""
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaType(), json.toByteArray()
                )
                val request = okhttp3.Request.Builder()
                    .url("${settings.platformApiUrl}/api/device-file/report-upload-status")
                    .post(body)
                    .build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().close()
                Timber.i("已上报文件缺失: $fileName")
            } catch (e: Exception) {
                Timber.e(e, "上报文件缺失失败: $fileName")
            }
        }
    }
}
