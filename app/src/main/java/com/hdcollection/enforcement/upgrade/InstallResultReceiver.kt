package com.hdcollection.enforcement.upgrade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 接收 PackageInstaller.Session.commit() 后的静默安装结果，上报平台。
 *
 * 平台后端可在 sys_app_upgrade_log 看到 install_success / install_failed 详情。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Timber.i("InstallResult: status=$status, msg=$msg")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 非 Device Owner 路径退化时，需要用户手动确认
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                        Timber.i("InstallResult: 已弹出用户确认页")
                    } catch (t: Throwable) {
                        Timber.e(t, "InstallResult: 启动确认页失败")
                    }
                }
                report(context, "install_pending_user", msg)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Timber.i("InstallResult: 安装成功，自动拉起 MainActivity")
                report(context, "install_success", null)
                // OTA 静默安装完成后主动 launch — 否则 app 进入 stopped state，
                // 后续 reboot 时 BOOT_COMPLETED 不会送给 stopped 的 app，开机自启动失效
                try {
                    val intent = Intent(context, com.hdcollection.enforcement.ui.main.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Timber.e(t, "InstallResult: 安装成功后启动 MainActivity 失败")
                }
            }
            else -> {
                val event = "install_failed"
                val reason = "status=$status,$msg"
                Timber.w("InstallResult: 安装失败 $reason")
                report(context, event, reason)
            }
        }
    }

    private fun report(context: Context, event: String, message: String?) {
        val settings = AppSettings(
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        )
        // 升级目标 versionCode 在静默装上下文里没有直接传入，传 0（事件本身已能表达）
        CoroutineScope(Dispatchers.IO).launch {
            UpgradeManager.reportEvent(context, settings, 0, event, message)
        }
    }
}
