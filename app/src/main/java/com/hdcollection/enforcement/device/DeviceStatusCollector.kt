package com.hdcollection.enforcement.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import timber.log.Timber

/**
 * Phase 4 K 字段采集：电量/充电/信号/网络类型/剩余存储
 * 各字段失败时返回 null，由调用方决定是否上报。
 */
object DeviceStatusCollector {

    data class Snapshot(
        val battery: Int?,
        val charge: Int?,
        val signal: Int?,
        val networkType: String?,
        val storageRemaining: Long?
    )

    fun collect(context: Context): Snapshot {
        val battery = readBattery(context)
        val charge = readChargeState(context)
        val networkType = readNetworkType(context)
        val signal = readSignalLevel(context, networkType)
        val storage = readStorageRemaining()
        return Snapshot(battery, charge, signal, networkType, storage)
    }

    private fun readBattery(ctx: Context): Int? = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    } catch (e: Throwable) {
        Timber.w(e, "readBattery 失败"); null
    }

    private fun readChargeState(ctx: Context): Int? = try {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL) 1 else 0
    } catch (e: Throwable) {
        Timber.w(e, "readChargeState 失败"); null
    }

    private fun readNetworkType(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "NONE"
            val cap = cm.getNetworkCapabilities(net) ?: return "NONE"
            when {
                cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> readCellularGeneration(ctx)
                else -> "NONE"
            }
        } catch (e: Throwable) {
            Timber.w(e, "readNetworkType 失败"); null
        }
    }

    private fun readCellularGeneration(ctx: Context): String {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // dataNetworkType 需 READ_PHONE_STATE，部分系统会返回 NETWORK_TYPE_UNKNOWN
            @Suppress("MissingPermission")
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "CELL"
            }
        } catch (e: Throwable) {
            "CELL"
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun readSignalLevel(ctx: Context, networkType: String?): Int? {
        return try {
            if (networkType == "WIFI") {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
                android.net.wifi.WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 6).coerceIn(0, 5)
            } else {
                // TelephonyManager.getSignalStrength() 是 Android 9 (API 28) 才加的方法，
                // 老设备（如 BT280T / Android 7.1）调用会抛 NoSuchMethodError。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.signalStrength?.level?.coerceIn(0, 5)
            }
        } catch (t: Throwable) {
            // 用 Throwable 兜底：NoSuchMethodError / LinkageError 等不是 Exception 子类。
            Timber.w(t, "readSignalLevel 失败"); null
        }
    }

    private fun readStorageRemaining(): Long? = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes
    } catch (e: Throwable) {
        Timber.w(e, "readStorageRemaining 失败"); null
    }
}
