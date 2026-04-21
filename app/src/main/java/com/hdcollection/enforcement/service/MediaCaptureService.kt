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
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.camera.Camera2Preview
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.gb28181.GB28181Manager
import com.hdcollection.enforcement.gb28181.StreamCallback
import com.hdcollection.enforcement.ui.main.MainActivity
import timber.log.Timber
import java.io.File

class MediaCaptureService : Service(), StreamCallback {

    interface Listener {
        fun onRegistered(deviceId: String) {}
        fun onRegistrationFailed(reason: String) {}
        fun onStreamStarted(channelId: String) {}
        fun onStreamStopped(channelId: String) {}
        fun onIntercomReceived(callerInfo: String) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaCaptureService = this@MediaCaptureService
    }

    private val binder = LocalBinder()
    private var camera: Camera2Preview? = null
    private var gb28181Manager: GB28181Manager? = null
    private lateinit var settings: AppSettings
    private var gbNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    override fun onCreate() {
        super.onCreate()
        Timber.i("MediaCaptureService onCreate")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EnforcementApp::MediaCaptureService"
        ).apply { acquire() }
        Timber.i("Service WakeLock acquired")
        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        startForegroundWithNotification()
        camera = Camera2Preview(this).also { it.start() }
        Timber.i("Camera2Preview 已在 Service 内启动")
        gb28181Manager = GB28181Manager(settings, this).also { it.register() }
        registerGbNetworkCallback()
        Timber.i("GB28181Manager 已在 Service 内启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("MediaCaptureService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        unregisterGbNetworkCallback()
        gb28181Manager?.unregister()
        gb28181Manager?.destroy()
        gb28181Manager = null
        camera?.detachPreview()
        camera?.stop()
        camera = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Timber.i("Service WakeLock released")
        super.onDestroy()
    }

    // —— StreamCallback 实现（GB28181 INVITE/BYE/注册回调）——
    override fun onRegistered(deviceId: String) {
        Timber.i("[Service] GB28181 registered: $deviceId")
        listeners.forEach { it.onRegistered(deviceId) }
    }

    override fun onRegistrationFailed(reason: String) {
        Timber.w("[Service] GB28181 registration failed: $reason")
        listeners.forEach { it.onRegistrationFailed(reason) }
    }

    override fun onStreamStartRequested(channelId: String, rtpIp: String, rtpPort: Int, ssrc: Int) {
        Timber.i("[Service] Stream start: $channelId -> $rtpIp:$rtpPort ssrc=$ssrc")
        camera?.startEncoding(rtpIp, rtpPort, ssrc)
        listeners.forEach { it.onStreamStarted(channelId) }
    }

    override fun onStreamStopRequested(channelId: String) {
        Timber.i("[Service] Stream stop: $channelId")
        camera?.stopEncoding()
        listeners.forEach { it.onStreamStopped(channelId) }
    }

    override fun onIntercomReceived(callerInfo: String) {
        Timber.i("[Service] Intercom: $callerInfo")
        listeners.forEach { it.onIntercomReceived(callerInfo) }
    }

    // —— 网络状态回调（从 MainActivity 迁入）——
    private fun registerGbNetworkCallback() {
        if (gbNetworkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Timber.i("[Service] NetworkCallback: onAvailable network=$network")
                    gb28181Manager?.boundNetwork = network
                    gb28181Manager?.triggerReconnect("network onAvailable")
                }
                override fun onLost(network: Network) {
                    Timber.i("[Service] NetworkCallback: onLost network=$network")
                    gb28181Manager?.boundNetwork = null
                    gb28181Manager?.notifyNetworkLost("network onLost")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    gb28181Manager?.boundNetwork = network
                }
            }
            cm.registerNetworkCallback(req, cb)
            gbNetworkCallback = cb
            Timber.i("[Service] NetworkCallback 已注册")
        } catch (e: Exception) {
            Timber.w(e, "[Service] 注册 NetworkCallback 失败")
        }
    }

    private fun unregisterGbNetworkCallback() {
        val cb = gbNetworkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        gbNetworkCallback = null
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
