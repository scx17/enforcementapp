package com.hdcollection.enforcement.config

import android.content.Context
import android.content.SharedPreferences
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 远程配置快照（后端可下发的字段全集）。
 *
 * 仅保留平台 `DeviceConfigApiController.ConfigTemplates` 中真正可在 App 端落地的字段；
 * 所有字段都有默认值，字段缺失时回退默认。
 */
data class RemoteConfig(
    // video_quality
    val videoResolution: String = "1920x1080",
    val videoFps: Int = 25,
    val videoBitrateKbps: Int = 4000,
    // audio
    val audioEnabled: Boolean = true,
    // gps
    val gpsEnabled: Boolean = true,
    val gpsIntervalSeconds: Int = 30,
    // upload
    val uploadAuto: Boolean = true,
    val uploadWifiOnly: Boolean = false,
    val uploadMaxRetries: Int = 3,
    // storage
    val storageAutoCleanDays: Int = 30,
    // recording
    val recordingSegmentMinutes: Int = 5,
    // osd
    val osdShowTime: Boolean = true,
    val osdShowDeviceId: Boolean = true,
    val osdShowGps: Boolean = false,
    val osdFontSize: Int = 16,
    // alarm
    val sosEnabled: Boolean = true,
    // 元数据
    val version: Long = 0L,
    val lastPushAt: Long = 0L
)

/**
 * 远程配置管理器（进程单例）。
 *
 * 职责：
 *  1. 接收 SignalR `ConfigPush` 事件的 JSON，解析并合并到本地 `RemoteConfig`
 *  2. 将最新快照持久化到独立的 SharedPreferences（"remote_config"），冷启动能恢复
 *  3. 通过 [config] StateFlow 对外广播变更，业务层（Camera/GPS/Upload/OSD 等）订阅消费
 *  4. 应用成功后回传 ack 到后端
 */
class RemoteConfigManager(
    private val context: Context,
    private val settings: AppSettings
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadFromPrefs())
    val config: StateFlow<RemoteConfig> = _config.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 处理 ConfigPush 事件原始 JSON。*/
    fun applyPush(raw: JSONObject) {
        try {
            val configs = raw.optJSONObject("configs") ?: run {
                Timber.w("ConfigPush: configs 字段为空，忽略")
                return
            }
            val pushTime = System.currentTimeMillis()

            val merged = mergeFromJson(_config.value, configs).copy(
                version = _config.value.version + 1,
                lastPushAt = pushTime
            )
            _config.value = merged
            saveToPrefs(merged)

            val appliedKeys = configs.keys().asSequence().toList()
            Timber.i("ConfigPush 已应用: version=${merged.version}, keys=$appliedKeys")

            // 回传 ack（异步）
            sendAck(appliedKeys)
        } catch (e: Exception) {
            Timber.e(e, "ConfigPush 应用失败")
        }
    }

    /** 合并 ConfigPush JSON 到当前快照。未知键或类型不符时保留原值。*/
    private fun mergeFromJson(current: RemoteConfig, configs: JSONObject): RemoteConfig {
        var next = current
        configs.keys().forEach { key ->
            val sec = configs.optJSONObject(key) ?: return@forEach
            when (key) {
                "video_quality" -> next = next.copy(
                    videoResolution = sec.optString("resolution", next.videoResolution),
                    videoFps = sec.optInt("fps", next.videoFps),
                    videoBitrateKbps = sec.optInt("bitrate", next.videoBitrateKbps)
                )
                "audio" -> next = next.copy(
                    audioEnabled = sec.optBoolean("enabled", next.audioEnabled)
                )
                "gps" -> next = next.copy(
                    gpsEnabled = sec.optBoolean("enabled", next.gpsEnabled),
                    gpsIntervalSeconds = sec.optInt("intervalSeconds", next.gpsIntervalSeconds)
                )
                "upload" -> next = next.copy(
                    uploadAuto = sec.optBoolean("autoUpload", next.uploadAuto),
                    uploadWifiOnly = sec.optBoolean("wifiOnly", next.uploadWifiOnly),
                    uploadMaxRetries = sec.optInt("maxRetries", next.uploadMaxRetries)
                )
                "storage" -> next = next.copy(
                    storageAutoCleanDays = sec.optInt("autoCleanDays", next.storageAutoCleanDays)
                )
                "recording" -> next = next.copy(
                    recordingSegmentMinutes = sec.optInt("segmentMinutes", next.recordingSegmentMinutes)
                )
                "osd" -> next = next.copy(
                    osdShowTime = sec.optBoolean("showTime", next.osdShowTime),
                    osdShowDeviceId = sec.optBoolean("showDeviceId", next.osdShowDeviceId),
                    osdShowGps = sec.optBoolean("showGps", next.osdShowGps),
                    osdFontSize = sec.optInt("fontSize", next.osdFontSize)
                )
                "alarm" -> next = next.copy(
                    sosEnabled = sec.optBoolean("sosEnabled", next.sosEnabled)
                )
                else -> Timber.w("ConfigPush: 忽略未知配置段 $key")
            }
        }
        return next
    }

    private fun saveToPrefs(c: RemoteConfig) {
        prefs.edit().apply {
            putString("videoResolution", c.videoResolution)
            putInt("videoFps", c.videoFps)
            putInt("videoBitrateKbps", c.videoBitrateKbps)
            putBoolean("audioEnabled", c.audioEnabled)
            putBoolean("gpsEnabled", c.gpsEnabled)
            putInt("gpsIntervalSeconds", c.gpsIntervalSeconds)
            putBoolean("uploadAuto", c.uploadAuto)
            putBoolean("uploadWifiOnly", c.uploadWifiOnly)
            putInt("uploadMaxRetries", c.uploadMaxRetries)
            putInt("storageAutoCleanDays", c.storageAutoCleanDays)
            putInt("recordingSegmentMinutes", c.recordingSegmentMinutes)
            putBoolean("osdShowTime", c.osdShowTime)
            putBoolean("osdShowDeviceId", c.osdShowDeviceId)
            putBoolean("osdShowGps", c.osdShowGps)
            putInt("osdFontSize", c.osdFontSize)
            putBoolean("sosEnabled", c.sosEnabled)
            putLong("version", c.version)
            putLong("lastPushAt", c.lastPushAt)
            apply()
        }
    }

    private fun loadFromPrefs(): RemoteConfig {
        val def = RemoteConfig()
        if (!prefs.contains("version")) return def
        return RemoteConfig(
            videoResolution = prefs.getString("videoResolution", def.videoResolution) ?: def.videoResolution,
            videoFps = prefs.getInt("videoFps", def.videoFps),
            videoBitrateKbps = prefs.getInt("videoBitrateKbps", def.videoBitrateKbps),
            audioEnabled = prefs.getBoolean("audioEnabled", def.audioEnabled),
            gpsEnabled = prefs.getBoolean("gpsEnabled", def.gpsEnabled),
            gpsIntervalSeconds = prefs.getInt("gpsIntervalSeconds", def.gpsIntervalSeconds),
            uploadAuto = prefs.getBoolean("uploadAuto", def.uploadAuto),
            uploadWifiOnly = prefs.getBoolean("uploadWifiOnly", def.uploadWifiOnly),
            uploadMaxRetries = prefs.getInt("uploadMaxRetries", def.uploadMaxRetries),
            storageAutoCleanDays = prefs.getInt("storageAutoCleanDays", def.storageAutoCleanDays),
            recordingSegmentMinutes = prefs.getInt("recordingSegmentMinutes", def.recordingSegmentMinutes),
            osdShowTime = prefs.getBoolean("osdShowTime", def.osdShowTime),
            osdShowDeviceId = prefs.getBoolean("osdShowDeviceId", def.osdShowDeviceId),
            osdShowGps = prefs.getBoolean("osdShowGps", def.osdShowGps),
            osdFontSize = prefs.getInt("osdFontSize", def.osdFontSize),
            sosEnabled = prefs.getBoolean("sosEnabled", def.sosEnabled),
            version = prefs.getLong("version", 0L),
            lastPushAt = prefs.getLong("lastPushAt", 0L)
        )
    }

    private fun sendAck(appliedKeys: List<String>) {
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.w("ConfigPush ack 跳过: platformApiUrl 或 deviceId 未配置")
            return
        }
        scope.launch {
            try {
                val keysJson = appliedKeys.joinToString(",") { "\"$it\"" }
                val body = """{"deviceId":"${settings.deviceId}","appliedKeys":[$keysJson],"version":${_config.value.version},"ackTime":${_config.value.lastPushAt}}"""
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${settings.platformApiUrl}/api/device-config/${settings.deviceId}/ack")
                    .post(body)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Timber.i("ConfigPush ack 已上报: keys=$appliedKeys, version=${_config.value.version}")
                    } else {
                        Timber.w("ConfigPush ack 上报失败: http=${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "ConfigPush ack 上报异常")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "remote_config"
    }
}
