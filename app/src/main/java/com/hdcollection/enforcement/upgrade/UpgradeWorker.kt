package com.hdcollection.enforcement.upgrade

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.data.AppSettings
import timber.log.Timber

/**
 * 周期性升级检查 Worker。
 *
 * 由 EnforcementApp 注册为 1H + 15m flex 的 PeriodicWorkRequest。
 * MainActivity 启动时也立即跑一次（通过 OneTimeWorkRequest 入队）。
 *
 * 流程：
 *   1. checkUpdate
 *   2. 无更新 → 退出
 *   3. 有更新 + 设备忙 + 非强制 → busy_skipped 上报后退出
 *   4. 下载 APK + SHA-256 校验
 *   5. 静默或弹窗安装
 */
class UpgradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("UpgradeWorker: 周期检查开始")
        val settings = AppSettings(
            applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        )
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.d("UpgradeWorker: 平台/deviceId 未配置，跳过")
            return Result.success()
        }

        val info = UpgradeManager.checkUpdate(applicationContext, settings)
        if (info == null) {
            Timber.d("UpgradeWorker: 无新版本")
            return Result.success()
        }
        Timber.i("UpgradeWorker: 发现新版本 vc=${info.versionCode}, force=${info.isForce}")

        // 设备忙时非强制升级跳过；强制升级无视忙状态
        if (!info.isForce && AppBusyState.isBusy()) {
            Timber.i("UpgradeWorker: 设备忙(${AppBusyState.describe()})，跳过本次非强制升级 vc=${info.versionCode}")
            UpgradeManager.reportEvent(
                applicationContext, settings, info.versionCode,
                "busy_skipped", AppBusyState.describe()
            )
            return Result.success()
        }

        // 下载
        UpgradeManager.reportEvent(
            applicationContext, settings, info.versionCode, "download_start", null
        )
        var lastReportedPercent = 0
        val apk = UpgradeManager.downloadApk(applicationContext, info) { percent ->
            // 控制上报频率：每 25% 一次，避免日志洪流
            if (percent - lastReportedPercent >= 25) {
                lastReportedPercent = percent
                UpgradeManager.reportEvent(
                    applicationContext, settings, info.versionCode,
                    "download_progress", "$percent%"
                )
            }
        }
        if (apk == null) {
            UpgradeManager.reportEvent(
                applicationContext, settings, info.versionCode,
                "download_failed", "下载或 SHA-256 校验失败"
            )
            return Result.retry()
        }
        UpgradeManager.reportEvent(
            applicationContext, settings, info.versionCode, "download_done", "${apk.length()}B"
        )

        // 安装
        val triggered = UpgradeManager.install(applicationContext, apk)
        UpgradeManager.reportEvent(
            applicationContext, settings, info.versionCode,
            if (triggered) "install_triggered" else "install_failed",
            "deviceOwner=${UpgradeManager.isDeviceOwner(applicationContext)}"
        )
        // 真正的 install_success/install_failed 通过 PendingIntent 广播由 InstallResultReceiver 上报
        return Result.success()
    }
}
