package com.hdcollection.enforcement.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class PlatformNotification(
    val message: String,
    val receivedAt: Long = System.currentTimeMillis()
)

class PlatformNotificationService(
    private val context: Context,
    private val settings: AppSettings
) {
    private var connection: HubConnection? = null
    val notifications: CopyOnWriteArrayList<PlatformNotification> = CopyOnWriteArrayList()

    var onNotificationReceived: ((PlatformNotification) -> Unit)? = null

    fun connect() {
        if (settings.platformApiUrl.isEmpty()) {
            Timber.w("SignalR: platformApiUrl not configured, skipping connect")
            return
        }
        try {
            val conn = HubConnectionBuilder
                .create("${settings.platformApiUrl}/hubs/monitor")
                .build()

            conn.on("PlatformNotification", { message: String ->
                val notification = PlatformNotification(message)
                notifications.add(0, notification)
                Timber.i("Platform notification: $message")
                showSystemNotification(message)
                onNotificationReceived?.invoke(notification)
            }, String::class.java)

            conn.start().blockingAwait()
            conn.invoke("JoinDeviceNotificationGroup", settings.deviceId)
            connection = conn
            Timber.i("SignalR connected to ${settings.platformApiUrl}/hubs/monitor")
        } catch (e: Exception) {
            Timber.e(e, "SignalR connection failed")
        }
    }

    fun disconnect() {
        try {
            connection?.stop()
            connection = null
            Timber.i("SignalR disconnected")
        } catch (e: Exception) {
            Timber.e(e, "SignalR disconnect error")
        }
    }

    private fun showSystemNotification(message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "平台通知", NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("平台通知")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "platform_notifications"
    }
}
