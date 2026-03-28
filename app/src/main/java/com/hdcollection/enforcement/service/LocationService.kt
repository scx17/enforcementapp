package com.hdcollection.enforcement.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * GPS 定位服务
 * 自动选择定位源：GPS 硬件 > 网络定位(基站/WiFi)
 * - 有 SIM 卡：GPS + A-GPS（基站辅助），精度最高
 * - 仅 WiFi：GPS 硬件 + WiFi 定位，精度中等
 * - 室内：WiFi/基站定位，精度较低
 */
class LocationService(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var currentLocation: Location? = null
        private set

    var onLocationChanged: ((Location) -> Unit)? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Timber.d("GPS 定位更新: ${location.latitude}, ${location.longitude} 精度=${location.accuracy}m 来源=${location.provider}")
            currentLocation = location
            onLocationChanged?.invoke(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            Timber.i("定位源启用: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Timber.w("定位源禁用: $provider")
        }
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // 网络定位精度较低，仅在没有 GPS 定位时使用
            if (currentLocation == null || currentLocation!!.provider != LocationManager.GPS_PROVIDER) {
                Timber.d("网络定位更新: ${location.latitude}, ${location.longitude} 精度=${location.accuracy}m")
                currentLocation = location
                onLocationChanged?.invoke(location)
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * 开始定位
     * 同时请求 GPS 和网络定位，优先使用 GPS
     */
    @SuppressLint("MissingPermission")
    fun start() {
        // 1. GPS 硬件定位（精度高，室外有效）
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10_000L,    // 最小更新间隔 10 秒
                5f,         // 最小移动距离 5 米
                gpsListener
            )
            // 获取上次已知位置作为初始值
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                currentLocation = it
                Timber.i("GPS 上次位置: ${it.latitude}, ${it.longitude}")
            }
            Timber.i("GPS 硬件定位已启动")
        } else {
            Timber.w("GPS 硬件未启用")
        }

        // 2. 网络定位（基站 + WiFi，室内外均可，精度较低）
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                30_000L,    // 30 秒更新
                50f,        // 50 米移动
                networkListener
            )
            // 如果 GPS 没有初始位置，用网络定位的
            if (currentLocation == null) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    currentLocation = it
                    Timber.i("网络定位上次位置: ${it.latitude}, ${it.longitude}")
                }
            }
            Timber.i("网络定位已启动 (${if (hasSim()) "SIM+WiFi" else "仅WiFi"})")
        } else {
            Timber.w("网络定位未启用")
        }

        // 定期上报位置到平台
        startReporting()
    }

    private var reportTimer: java.util.Timer? = null

    private fun startReporting() {
        val settings = com.hdcollection.enforcement.data.AppSettings(
            context.getSharedPreferences("app_settings", 0)
        )
        val apiUrl = settings.platformApiUrl
        val deviceId = settings.deviceId
        if (apiUrl.isBlank() || deviceId.isBlank()) {
            Timber.w("位置上报跳过: 平台地址或设备ID未配置")
            return
        }

        reportTimer = java.util.Timer()
        reportTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val loc = currentLocation ?: return
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val json = com.google.gson.JsonObject().apply {
                        addProperty("deviceId", deviceId)
                        addProperty("longitude", loc.longitude)
                        addProperty("latitude", loc.latitude)
                        addProperty("accuracy", loc.accuracy)
                        addProperty("provider", loc.provider ?: "unknown")
                    }
                    val mediaType = "application/json".toMediaType()
                    val body = json.toString().toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url("$apiUrl/api/map/report-location")
                        .post(body)
                        .build()
                    client.newCall(request).execute().close()
                    Timber.d("位置已上报: ${loc.latitude}, ${loc.longitude}")
                } catch (e: Exception) {
                    Timber.d("位置上报失败: ${e.message}")
                }
            }
        }, 10_000, 30_000) // 10秒后首次上报，每30秒一次
    }

    fun stop() {
        reportTimer?.cancel()
        reportTimer = null
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(networkListener)
        Timber.i("定位服务已停止")
    }

    /** 获取当前经度 */
    fun getLongitude(): Double = currentLocation?.longitude ?: 0.0

    /** 获取当前纬度 */
    fun getLatitude(): Double = currentLocation?.latitude ?: 0.0

    /** 获取定位精度（米） */
    fun getAccuracy(): Float = currentLocation?.accuracy ?: 0f

    /** 获取定位来源描述 */
    fun getProviderDesc(): String {
        val loc = currentLocation ?: return "未定位"
        return when (loc.provider) {
            LocationManager.GPS_PROVIDER -> "GPS (${loc.accuracy.toInt()}m)"
            LocationManager.NETWORK_PROVIDER -> if (hasSim()) "基站+WiFi (${loc.accuracy.toInt()}m)" else "WiFi (${loc.accuracy.toInt()}m)"
            else -> "${loc.provider} (${loc.accuracy.toInt()}m)"
        }
    }

    /** 检查是否有 SIM 卡 */
    private fun hasSim(): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return tm?.simState == android.telephony.TelephonyManager.SIM_STATE_READY
    }
}
