package com.hdcollection.enforcement.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import timber.log.Timber

/**
 * 监听 USB 线插拔事件，自动切换 U 盘模式。
 * 插入 USB → setUsbDisk(true)，设备变为 U 盘，指向录像/图片存储目录
 * 拔出 USB → setUsbDisk(false)，恢复正常模式
 */
class UsbStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.hardware.usb.action.USB_STATE") return

        val connected = intent.getBooleanExtra("connected", false)
        val hostConnected = intent.getBooleanExtra("host_connected", false)

        Timber.i("USB 状态变化: connected=$connected, host_connected=$hostConnected")

        if (!DeviceHardwareManager.isAvailable()) {
            Timber.w("USB: 厂商 SDK 不可用，跳过 U 盘模式切换")
            return
        }

        if (connected && !hostConnected) {
            // USB 连接到电脑（非 OTG 主机模式）
            val result = DeviceHardwareManager.setUsbDisk(true)
            Timber.i("USB 已连接，开启 U 盘模式: result=$result, isOpen=${DeviceHardwareManager.isUsbDiskOpen()}")
        } else if (!connected) {
            val result = DeviceHardwareManager.setUsbDisk(false)
            Timber.i("USB 已断开，关闭 U 盘模式: result=$result")
        }
    }

    companion object {
        fun createIntentFilter(): IntentFilter {
            return IntentFilter("android.hardware.usb.action.USB_STATE")
        }
    }
}
