package com.hdcollection.enforcement.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.camera.Camera2Preview
import com.hdcollection.enforcement.ui.main.MainActivity
import timber.log.Timber
import java.io.File

class MediaCaptureService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MediaCaptureService = this@MediaCaptureService
    }

    private val binder = LocalBinder()
    private var camera: Camera2Preview? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("MediaCaptureService onCreate")
        startForegroundWithNotification()
        camera = Camera2Preview(this).also { it.start() }
        Timber.i("Camera2Preview 已在 Service 内启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("MediaCaptureService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        camera?.detachPreview()
        camera?.stop()
        camera = null
        super.onDestroy()
    }

    // —— 给 Activity 用的代理方法 ——
    fun attachPreview(surfaceView: SurfaceView) { camera?.attachPreview(surfaceView) }
    fun detachPreview() { camera?.detachPreview() }
    fun capturePhoto(out: File, cb: (File) -> Unit) { camera?.capturePhoto(out, cb) }
    fun startLocalRecording(out: File) { camera?.startLocalRecording(out) }
    fun stopLocalRecording() { camera?.stopLocalRecording() }
    fun switchCamera() { camera?.switchCamera() }
    fun isFrontCamera(): Boolean = camera?.isFrontCamera() ?: false
    fun startEncoding(rtpIp: String, rtpPort: Int, ssrc: Int) { camera?.startEncoding(rtpIp, rtpPort, ssrc) }
    fun stopEncoding() { camera?.stopEncoding() }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "执法仪后台采集", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证息屏后仍可被平台点播"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("执法仪运行中")
            .setContentText("视频采集与上报服务")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Timber.i("MediaCaptureService startForeground 完成")
    }

    companion object {
        const val CHANNEL_ID = "media_capture_service"
        const val NOTIFICATION_ID = 1001
    }
}
