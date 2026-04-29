package com.hdcollection.enforcement.util

import android.content.Context
import android.provider.Settings
import timber.log.Timber
import java.util.UUID

/**
 * 设备唯一标识统一获取入口。
 *
 * 历史教训：早期版本拿不到 IMEI 时返回 "0000000000000000"，导致后端
 * 把所有这类设备塌缩到同一 sipDeviceId 后缀（44010200491320000000）。
 * 必须用 ANDROID_ID / UUID 兜底，保证唯一稳定。
 *
 * 优先级：
 *  1. 厂商 DeviceManager.imei（仅部分 MTK 定制机有，系统签名 API）
 *  2. Settings.Secure.ANDROID_ID（无权限要求，工厂重置才变）
 *  3. 随机 UUID 持久化 SharedPreferences（最终兜底）
 */
object DeviceIdentity {

    private const val PREFS_NAME = "app_settings"
    private const val PREFS_KEY_FALLBACK_UUID = "fallback_device_uuid"

    // 早期 Android 模拟器/某些设备的固定脏值
    private const val ANDROID_ID_DIRTY = "9774d56d682e549c"

    @android.annotation.SuppressLint("HardwareIds")
    fun getStableImei(context: Context): String {
        // 1. 厂商私有 API
        try {
            val dm = android.app.devicemanager.DeviceManager.getInstance()
            val vendorImei = dm.imei?.takeIf { it.isNotBlank() && !isDirty(it) }
            if (vendorImei != null) return vendorImei
        } catch (t: Throwable) {
            Timber.w("DeviceManager.getImei() 不可用: ${t.message}, 回退 ANDROID_ID")
        }
        // 2. ANDROID_ID
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != ANDROID_ID_DIRTY && !isDirty(androidId)) {
            return "ANDROID_$androidId"
        }
        // 3. UUID 兜底
        return fallbackUuid(context)
    }

    private fun fallbackUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        var uuid = prefs.getString(PREFS_KEY_FALLBACK_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = "UUID_" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(PREFS_KEY_FALLBACK_UUID, uuid).apply()
            Timber.i("生成 fallback 设备 UUID: $uuid")
        }
        return uuid
    }

    /**
     * 判断字符串是否为「脏值」——全零、全相同字符、过短。
     * 用于过滤 IMEI 和检测 sipDeviceId 后缀。
     */
    fun isDirty(s: String): Boolean {
        if (s.isBlank()) return true
        if (s.length < 4) return true
        return s.all { it == s[0] }
    }

    /**
     * 检查给定的 sipDeviceId 是否使用了脏后缀（末 7 位全相同字符），
     * 这种 ID 是早期 IMEI=0 fallback 的产物，必须重新配置以避免多设备塌缩。
     */
    fun isSipDeviceIdDirty(sipDeviceId: String?): Boolean {
        if (sipDeviceId.isNullOrBlank()) return false
        if (sipDeviceId.length < 7) return false
        val suffix = sipDeviceId.takeLast(7)
        return suffix.all { it == suffix[0] }
    }
}
