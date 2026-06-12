package com.hdcollection.enforcement.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hdcollection.enforcement.ui.main.MainActivity
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // 退出标志还在时不自启动（用户主动退出了应用）
            if (com.hdcollection.enforcement.service.MediaCaptureService.isAppExiting(context)) {
                Timber.w("开机自启动: 检测到退出标志，跳过")
                return
            }
            Timber.i("开机自启动: launching MainActivity")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
