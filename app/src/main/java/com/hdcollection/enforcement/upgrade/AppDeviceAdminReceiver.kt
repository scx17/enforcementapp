package com.hdcollection.enforcement.upgrade

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Device Admin 接收器——开启 Device Owner 的入口。
 *
 * 现场首次启用步骤（每台设备一次性）：
 *   adb shell dpm set-device-owner com.hdcollection.enforcement/.upgrade.AppDeviceAdminReceiver
 *
 * 设为 Device Owner 后，App 才能调 PackageInstaller.Session 静默安装升级 APK，
 * 跳过系统安装确认对话框。
 *
 * 设备前置条件：
 *   - 只有 system user，没有添加额外用户/Google 账户
 *   - App 已安装（dpm 命令需要先有这个组件）
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Timber.i("AppDeviceAdmin: 已启用 Device Admin")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.w("AppDeviceAdmin: 已禁用 Device Admin（远程升级将退化到弹窗安装）")
    }
}
