package com.hdcollection.enforcement.config

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * 启动时一次性把硬件能力上报到平台，供管理员判断设备硬件分级、给出推荐编码配置。
 *
 * 字段：model / manufacturer / sdkInt / cores / totalMemMb
 * 平台仅做内存缓存（ConcurrentDictionary），重启会丢失，下次 App 启动会再次上报。
 */
object HardwareProfileReporter {
    private val client = OkHttpClient()

    fun reportAsync(context: Context, settings: AppSettings) {
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.w("硬件 profile 上报跳过: platformApiUrl 或 deviceId 未配置")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val totalMb = mi.totalMem / (1024 * 1024)
                val cores = Runtime.getRuntime().availableProcessors()
                val payload = """{"model":"${Build.MODEL}","manufacturer":"${Build.MANUFACTURER}","sdkInt":${Build.VERSION.SDK_INT},"cores":$cores,"totalMemMb":$totalMb}"""
                val body = payload.toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${settings.platformApiUrl}/api/device-config/${settings.deviceId}/hardware-profile")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Timber.i("硬件 profile 已上报: $payload")
                    } else {
                        Timber.w("硬件 profile 上报失败: http=${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "硬件 profile 上报异常")
            }
        }
    }
}
