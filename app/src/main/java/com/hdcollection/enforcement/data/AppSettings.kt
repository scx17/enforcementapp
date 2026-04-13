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
}
