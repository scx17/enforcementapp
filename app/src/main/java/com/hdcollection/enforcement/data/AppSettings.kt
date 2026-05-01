package com.hdcollection.enforcement.data

import android.content.SharedPreferences

class AppSettings(private val prefs: SharedPreferences) {

    var sipServer: String
        get() = prefs.getString("sip_server", "") ?: ""
        set(v) = prefs.edit().putString("sip_server", v).apply()

    var sipPort: String
        get() = prefs.getString("sip_port", "5060") ?: "5060"
        set(v) = prefs.edit().putString("sip_port", v).apply()

    var sipUsername: String
        get() = prefs.getString("sip_username", "") ?: ""
        set(v) = prefs.edit().putString("sip_username", v).apply()

    var sipPassword: String
        get() = prefs.getString("sip_password", "") ?: ""
        set(v) = prefs.edit().putString("sip_password", v).apply()

    var platformApiUrl: String
        get() = prefs.getString("platform_api_url", "") ?: ""
        set(v) = prefs.edit().putString("platform_api_url", v).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(v) = prefs.edit().putString("device_id", v).apply()

    var customCode: String
        get() = prefs.getString("custom_code", "") ?: ""
        set(v) = prefs.edit().putString("custom_code", v).apply()

    var customCodeUpdatedAt: Long
        get() = prefs.getLong("custom_code_updated_at", 0L)
        set(v) = prefs.edit().putLong("custom_code_updated_at", v).apply()

    var logUploadInterval: Int
        get() = prefs.getInt("log_upload_interval", 60)
        set(v) = prefs.edit().putInt("log_upload_interval", v).apply()

    var networkPreference: String
        get() = prefs.getString("network_pref", "wifi") ?: "wifi"
        set(v) = prefs.edit().putString("network_pref", v).apply()

    var videoResolution: String
        get() = prefs.getString("video_resolution", "1080P") ?: "1080P"
        set(v) = prefs.edit().putString("video_resolution", v).apply()

    var videoBitrate: Int
        get() = prefs.getInt("video_bitrate", 2048)
        set(v) = prefs.edit().putInt("video_bitrate", v).apply()

    /** 录像分片时长（分钟），本地设置。远程配置优先覆盖。 */
    var recordingSegmentMinutes: Int
        get() = prefs.getInt("recording_segment_minutes", 5)
        set(v) = prefs.edit().putInt("recording_segment_minutes", v).apply()

    /** 本地 SIP 监听端口(默认 5070,非 5060)。
     *  原因:Android 7-9 系统自带 android.net.sip.SipService 抢占 UDP 5060,
     *  我们 App socket bind 5060 时虽然不报错(SO_REUSEPORT 共享),但 inbound 包
     *  会被内核优先送给系统服务,我们 receive 永远拿不到 WVP 响应 → SIP 永远注册不上。
     *  改非标 5070 完全规避这个冲突;Android 10+ 系统已废弃 SipService 不影响。
     *  发往 WVP 的端口仍是 sipPort(默认 5060),NAT 回包按映射端口走,不依赖本地 bind 端口。 */
    var sipLocalPort: Int
        get() = prefs.getInt("sip_local_port", 5070)
        set(v) = prefs.edit().putInt("sip_local_port", v).apply()
}
