package com.hdcollection.enforcement.service

import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class AlarmReporter(private val settings: AppSettings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // App 端不做去重，由平台端统一控制（平台检查未处理状态的同类告警）

    suspend fun report(
        alarmType: String, alarmLevel: Int,
        title: String, content: String,
        lat: Double? = null, lng: Double? = null
    ) = withContext(Dispatchers.IO) {
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.w("AlarmReporter: 平台地址或设备ID未配置")
            return@withContext
        }

        try {
            val json = JSONObject().apply {
                put("deviceId", settings.deviceId)
                put("alarmType", alarmType)
                put("alarmLevel", alarmLevel)
                put("title", title)
                put("content", content)
                lat?.let { put("latitude", it) }
                lng?.let { put("longitude", it) }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/alarm/report")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Timber.i("AlarmReporter: 告警已上报 type=$alarmType, level=$alarmLevel")
                } else {
                    Timber.w("AlarmReporter: 上报失败 HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "AlarmReporter: 上报异常 type=$alarmType")
        }
    }
}
