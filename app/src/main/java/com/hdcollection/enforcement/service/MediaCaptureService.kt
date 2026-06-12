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
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.camera.Camera2Preview
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.gb28181.GB28181Manager
import com.hdcollection.enforcement.gb28181.StreamCallback
import com.hdcollection.enforcement.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    // Wi-Fi 高优先级锁——告诉系统屏幕熄灭也别让 supplicant 进省电模式,
    // 避免 BT280T 等定制 ROM 上 wifi_sleep_policy=NEVER 失效导致被 AP 频繁踢
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var configCollectJob: Job? = null

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

        // 申请 Wi-Fi 高功耗锁,屏幕熄灭也维持 supplicant 高功耗扫描/连接,
        // 防 RSSI 边缘信号下被 AP 闲置踢出
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "EnforcementApp::WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Timber.i("Service WifiLock(FULL_HIGH_PERF) acquired")
        } catch (t: Throwable) {
            Timber.w(t, "WifiLock 申请失败,wifi 仍按系统默认省电策略走")
        }
        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        startForegroundWithNotification()
        camera = Camera2Preview(this).also { it.start() }
        Timber.i("Camera2Preview 已在 Service 内启动")

        // 远程配置变更时热应用视频参数（分辨率/码率/帧率/拍照分辨率），无需重启 App
        val app = applicationContext as? EnforcementApp
        if (app != null) {
            configCollectJob = serviceScope.launch {
                var lastSig = videoConfigSignature(app)
                app.remoteConfigManager.config.collect {
                    val sig = videoConfigSignature(app)
                    if (sig != lastSig) {
                        Timber.i("RemoteConfig 视频参数变化: $lastSig → $sig")
                        lastSig = sig
                        try {
                            camera?.applyVideoConfigIfChanged()
                        } catch (e: Exception) {
                            Timber.e(e, "applyVideoConfigIfChanged 异常")
                        }
                    }
                }
            }
        }

        gb28181Manager = GB28181Manager(settings, this).also { it.register() }
        registerGbNetworkCallback()
        Timber.i("GB28181Manager 已在 Service 内启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("MediaCaptureService onStartCommand action=${intent?.action}")
        if (isExiting) {
            Timber.w("MediaCaptureService: 检测到退出标志，立即 stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RELOAD_GB28181 -> reloadGb28181()
            ACTION_REOPEN_CAMERA -> reopenCameraIfNeeded()
        }
        return START_STICKY
    }

    /** 扫码 / 第三方应用释放相机后调用：如 CameraDevice 被 disconnect，则重新 openCamera。*/
    private fun reopenCameraIfNeeded() {
        val cam = camera ?: run {
            Timber.w("reopenCameraIfNeeded: camera 实例为空，新建 Camera2Preview")
            Camera2Preview(this).also { camera = it }
        }
        // CameraX 在 onDestroy 里释放 camera 是异步的，立即调用 openCamera 很可能拿不到资源，
        // 延迟 600ms 再请求，start() 内部遇到 cameraDevice=null 会 openCamera
        android.os.Handler(mainLooper).postDelayed({
            Timber.i("reopenCameraIfNeeded: 调用 Camera2Preview.start()")
            cam.start()
        }, 600)
    }

    /** 配置变更（扫码/自动配置后）调用：销毁旧 GB28181 连接并按新设置重新注册，摄像头保持运行。 */
    private fun reloadGb28181() {
        Timber.i("GB28181 配置 reload: deviceId=${settings.deviceId}, sipServer=${settings.sipServer}:${settings.sipPort}")
        try {
            gb28181Manager?.unregister()
            gb28181Manager?.destroy()
        } catch (e: Exception) {
            Timber.w(e, "GB28181 销毁旧连接异常，继续重建")
        }
        gb28181Manager = GB28181Manager(settings, this).also { it.register() }
        Timber.i("GB28181Manager 已按新配置重新注册")
    }


    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Timber.i("MediaCaptureService onDestroy")
        configCollectJob?.cancel()
        configCollectJob = null
        serviceScope.cancel()
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
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        Timber.i("Service WifiLock released")
        super.onDestroy()
    }

    /** 视频参数指纹：分辨率+帧率+码率+拍照分辨率/质量；任一变化触发热应用。*/
    private fun videoConfigSignature(app: EnforcementApp): String {
        val c = app.remoteConfigManager.config.value
        return "${c.videoResolution}@${c.videoFps}/${c.videoBitrateKbps}|photo=${c.photoResolution}/${c.photoQuality}"
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
    // 实际部署中主网是 SIM 流量，WiFi 只是备用。所以订阅"默认 Internet 网络"，
    // OS 会在 WiFi/蜂窝之间自动切换并回调 onAvailable，避免只订 WiFi 导致切回蜂窝后永远不再收到事件。
    private fun registerGbNetworkCallback() {
        if (gbNetworkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val transport = describeTransport(cm, network)
                    Timber.i("[Service] NetworkCallback: onAvailable network=$network, transport=$transport")
                    gb28181Manager?.boundNetwork = network
                    gb28181Manager?.triggerReconnect("network onAvailable($transport)")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                // 低版本（API<24）回退：订阅任意具备 INTERNET 能力的网络
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
            gbNetworkCallback = cb
            Timber.i("[Service] NetworkCallback 已注册（订阅默认 Internet 网络，WiFi+蜂窝均可）")
        } catch (e: Exception) {
            Timber.w(e, "[Service] 注册 NetworkCallback 失败")
        }
    }

    private fun describeTransport(cm: ConnectivityManager, net: Network): String {
        val caps = try { cm.getNetworkCapabilities(net) } catch (_: Exception) { null } ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
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
    fun startAudioRecording(file: File) { camera?.startAudioRecording(file) }
    fun stopAudioRecording(): Long = camera?.stopAudioRecording() ?: 0L
    fun isAudioRecording(): Boolean = camera?.isAudioRecording == true
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
        const val ACTION_RELOAD_GB28181 = "com.hdcollection.enforcement.RELOAD_GB28181"
        const val ACTION_REOPEN_CAMERA = "com.hdcollection.enforcement.REOPEN_CAMERA"

        /** 退出应用时置 true，防止 START_STICKY 把 Service（连带 Activity）拉起 */
        @Volatile
        var isExiting: Boolean = false
    }
}
