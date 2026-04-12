package com.hdcollection.enforcement.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.gb28181.GB28181Manager
import com.hdcollection.enforcement.gb28181.StreamCallback
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import com.hdcollection.enforcement.hardware.HardwareKeyReceiver
import com.hdcollection.enforcement.hardware.LightState
import com.hdcollection.enforcement.ui.LightPanelFragment
import com.hdcollection.enforcement.ui.function.FunctionActivity
import com.hdcollection.enforcement.ui.playback.PlaybackActivity
import com.hdcollection.enforcement.service.AlarmReporter
import com.hdcollection.enforcement.service.MediaCaptureService
import com.hdcollection.enforcement.receiver.UsbStateReceiver
import com.hdcollection.enforcement.ui.settings.SettingsActivity
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), StreamCallback {

    private lateinit var settings: AppSettings
    private lateinit var gb28181Manager: GB28181Manager
    private lateinit var alarmReporter: AlarmReporter

    private var isNavigatingInternally = false
    private var isRecording = false
    private var isFlashOn = false
    private var currentRecordFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gbNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var mediaService: MediaCaptureService? = null
    private val mediaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MediaCaptureService.LocalBinder)?.getService()
            mediaService = svc
            Timber.i("MediaCaptureService connected")
            svc?.attachPreview(findViewById(R.id.surfacePreview))
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
            Timber.w("MediaCaptureService disconnected")
        }
    }
    private val hardwareKeyReceiver = HardwareKeyReceiver()
    private val usbStateReceiver = UsbStateReceiver()

    // 语音提示播放器
    private var voicePlayer: MediaPlayer? = null

    // 系统快门音
    private val shutterSound = MediaActionSound()

    // 按键防抖：设备同时发 KeyEvent + 广播，防止同一操作触发两次
    private var lastRecordingToggleTime = 0L
    private var lastPhotoTime = 0L
    private val DEBOUNCE_MS = 1500L

    // 录像计时
    private var recordingStartTime = 0L
    private val recordingTimerHandler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            updateRecordingTimer()
            recordingTimerHandler.postDelayed(this, 1000)
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 保持屏幕常亮 + WakeLock 防止 CPU 休眠
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 全屏沉浸模式：隐藏导航栏和状态栏
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnforcementApp::MainWakeLock")
        wakeLock?.acquire()

        // 启动 MediaCaptureService（前台服务）
        val mediaIntent = Intent(this, MediaCaptureService::class.java)
        ContextCompat.startForegroundService(this, mediaIntent)
        bindService(mediaIntent, mediaServiceConnection, Context.BIND_AUTO_CREATE)

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        alarmReporter = AlarmReporter(settings)
        try {
            gb28181Manager = GB28181Manager(settings, this)
            Timber.i("GB28181Manager 初始化成功")
        } catch (e: Exception) {
            Timber.e(e, "GB28181Manager 初始化失败")
            throw e
        }

        // Camera 已由 MediaCaptureService 持有并 start，preview 在 onServiceConnected 中 attach

        // 预加载快门音
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        setupBottomButtons()
        updateDeviceInfo()
        updateStreamStatus("初始化", "#9E9E9E")

        // 录像指示器默认隐藏
        findViewById<TextView>(R.id.tvRecordingIndicator).visibility = View.GONE

        if (hasRequiredPermissions()) {
            startGB28181()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }

        registerGbNetworkCallback()

        // 注册 SIP 来电监听
        (application as EnforcementApp).sipManager.onIncomingCall = { call ->
            runOnUiThread { showIncomingCallDialog(call) }
        }

        // 注册工单到达监听 — App 内弹窗 + 循环声音
        (application as EnforcementApp).notificationService.onWorkTaskReceived = { title, priority ->
            runOnUiThread { showWorkTaskAlert(title, priority) }
        }

        // 注册硬件按键广播接收器
        registerHardwareKeyReceiver()

        // 注册 USB 插拔广播接收器
        registerReceiver(usbStateReceiver, UsbStateReceiver.createIntentFilter())
        Timber.i("USB state receiver registered")
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        // 仅在被系统切走时拉回前台（内部导航不拦截）
        if (!isNavigatingInternally) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isNavigatingInternally) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                }
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigatingInternally = false
        clockHandler.post(clockRunnable)
        updateDeviceInfo()
    }

    private fun startInternalActivity(intent: Intent) {
        isNavigatingInternally = true
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hardwareKeyReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbStateReceiver) } catch (_: Exception) {}
        unregisterGbNetworkCallback()
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        voicePlayer?.release()
        shutterSound.release()
        gb28181Manager.unregister()
        mediaService?.detachPreview()
        try { unbindService(mediaServiceConnection) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun setupBottomButtons() {
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startInternalActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnLight).setOnClickListener {
            showLightPanel()
        }
        findViewById<ImageButton>(R.id.btnPlayback).setOnClickListener {
            startInternalActivity(Intent(this, PlaybackActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnFunction).setOnClickListener {
            startInternalActivity(Intent(this, FunctionActivity::class.java))
        }
        // 切换前后摄像头
        findViewById<ImageButton>(R.id.btnSwitchCamera).setOnClickListener {
            mediaService?.switchCamera()
            Timber.i("摄像头切换: front=${mediaService?.isFrontCamera()}")
        }
    }

    private fun startGB28181() {
        updateStreamStatus("注册中", "#FF9800")
        gb28181Manager.register()
    }

    /**
     * 注册系统网络状态回调，WiFi 恢复时立即触发 GB28181 重新注册。
     * 解决断网后重连不会自动重注册的问题（原来要等 keepAlive 心跳累计 3 次失败约 3 分钟才感知）。
     */
    private fun registerGbNetworkCallback() {
        if (gbNetworkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Timber.i("GB28181 NetworkCallback: onAvailable network=$network")
                    gb28181Manager.triggerReconnect("network onAvailable")
                }

                override fun onLost(network: Network) {
                    Timber.i("GB28181 NetworkCallback: onLost network=$network")
                    gb28181Manager.notifyNetworkLost("network onLost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        Timber.d("GB28181 NetworkCallback: capabilities validated, network=$network")
                        gb28181Manager.triggerReconnect("network validated")
                    }
                }
            }
            cm.registerNetworkCallback(req, cb)
            gbNetworkCallback = cb
            Timber.i("GB28181: 网络状态回调已注册")
        } catch (e: Exception) {
            Timber.w(e, "GB28181: 注册网络回调失败")
        }
    }

    private fun unregisterGbNetworkCallback() {
        val cb = gbNetworkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
            Timber.i("GB28181: 网络状态回调已反注册")
        } catch (e: Exception) {
            Timber.w(e, "GB28181: 反注册网络回调失败")
        } finally {
            gbNetworkCallback = null
        }
    }

    private var watermarkEnabled = false

    private fun updateClock() {
        val now = Date()
        val cfg = (application as EnforcementApp).remoteConfigManager.config.value

        val tvTime = findViewById<TextView>(R.id.tvTime)
        val tvDate = findViewById<TextView>(R.id.tvDate)
        val tvGps = findViewById<TextView>(R.id.tvGps)
        val tvDeviceIdOsd = findViewById<TextView>(R.id.tvDeviceId)

        // 时间/日期
        if (cfg.osdShowTime) {
            tvTime.visibility = android.view.View.VISIBLE
            tvDate.visibility = android.view.View.VISIBLE
            tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
            tvDate.text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        } else {
            tvTime.visibility = android.view.View.GONE
            tvDate.visibility = android.view.View.GONE
        }

        // 设备号
        tvDeviceIdOsd?.visibility = if (cfg.osdShowDeviceId) android.view.View.VISIBLE else android.view.View.GONE

        // GPS
        if (cfg.osdShowGps) {
            tvGps?.visibility = android.view.View.VISIBLE
            val locService = (application as EnforcementApp).locationService
            val gpsText = if (locService.getLatitude() != 0.0) {
                String.format("%.6f, %.6f %s", locService.getLatitude(), locService.getLongitude(), locService.getProviderDesc())
            } else {
                "定位中..."
            }
            tvGps?.text = gpsText
        } else {
            tvGps?.visibility = android.view.View.GONE
        }

        // 字体大小（全部 OSD TextView 共用）
        val fontSp = cfg.osdFontSize.coerceIn(10, 40).toFloat()
        tvTime.textSize = fontSp
        tvDate.textSize = fontSp * 0.75f
        tvGps?.textSize = fontSp * 0.75f
        tvDeviceIdOsd?.textSize = fontSp * 0.75f

        // 更新摄像头画面水印（硬件 OSD，直接烧录到视频帧中）
        updateCameraWatermark(now, cfg)
    }

    private fun updateCameraWatermark(now: Date, cfg: com.hdcollection.enforcement.config.RemoteConfig) {
        try {
            val dm = android.app.devicemanager.DeviceManager.getInstance()
            if (dm == null) {
                if (!watermarkEnabled) { Timber.w("DeviceManager 为 null，水印不可用"); watermarkEnabled = true }
                return
            }
            // 整体开关：只要 showTime 或 showDeviceId 有一个开启就启用水印
            val anyOsdOn = cfg.osdShowTime || cfg.osdShowDeviceId
            if (!watermarkEnabled) {
                dm.setCameraWaterMarkEnable(anyOsdOn)
                watermarkEnabled = true
                Timber.i("摄像头硬件水印初始化: enabled=$anyOsdOn")
            } else {
                dm.setCameraWaterMarkEnable(anyOsdOn)
            }
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            dm.setCameraWaterMarkText(0, if (cfg.osdShowTime) timeFmt.format(now) else "")
            dm.setCameraWaterMarkText(1, if (cfg.osdShowDeviceId) settings.deviceId else "")
        } catch (e: Exception) {
            if (!watermarkEnabled) {
                Timber.w(e, "硬件水印不可用: ${e.message}")
                watermarkEnabled = true // 只报一次
            }
        }
    }

    private fun updateDeviceInfo() {
        val deviceId = settings.deviceId.ifEmpty { "未配置" }
        findViewById<TextView>(R.id.tvDeviceId).text = deviceId

        val resolution = settings.videoResolution
        val bitrate = settings.videoBitrate
        findViewById<TextView>(R.id.tvEncoding).text = "H264 $resolution ${bitrate}k"

        Timber.i("设备信息加载: deviceId=$deviceId, sipServer=${settings.sipServer}:${settings.sipPort}, resolution=$resolution, bitrate=${bitrate}k")

        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        val extDir = getExternalFilesDir(null)
        if (extDir != null) {
            val total = extDir.totalSpace / (1024 * 1024 * 1024.0)
            val free = extDir.freeSpace / (1024 * 1024 * 1024.0)
            val used = total - free
            findViewById<TextView>(R.id.tvStorage).text =
                String.format("%.1fG/%.1fG", used, total)
        }
    }

    private fun updateStreamStatus(text: String, colorHex: String) {
        val tv = findViewById<TextView>(R.id.tvStreamStatus)
        tv.text = "● $text"
        tv.setTextColor(Color.parseColor(colorHex))
    }

    private fun hasRequiredPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isEmpty()) {
                Timber.i("所有权限已授予: ${permissions.joinToString()}")
                startGB28181()
            } else {
                Timber.w("权限被拒绝: ${denied.joinToString()}")
                updateStreamStatus("权限不足", "#F44336")
            }
        }
    }

    // StreamCallback 实现
    override fun onRegistered(deviceId: String) {
        Timber.i("GB28181 registered: $deviceId")
        runOnUiThread {
            updateStreamStatus("注册在线", "#4CAF50")
            // 上线后检查未处理工单
            checkPendingWorkTasks()
        }
    }

    override fun onRegistrationFailed(reason: String) {
        Timber.w("GB28181 registration failed: $reason")
        runOnUiThread { updateStreamStatus("断网", "#F44336") }
    }

    override fun onStreamStartRequested(channelId: String, rtpIp: String, rtpPort: Int, ssrc: Int) {
        Timber.i("Stream start requested: $channelId -> $rtpIp:$rtpPort ssrc=$ssrc")
        runOnUiThread { updateStreamStatus("推流中", "#2196F3") }
        mediaService?.startEncoding(rtpIp, rtpPort, ssrc)
    }

    override fun onStreamStopRequested(channelId: String) {
        Timber.i("Stream stop requested: $channelId")
        mediaService?.stopEncoding()
        runOnUiThread { updateStreamStatus("注册在线", "#4CAF50") }
    }

    override fun onIntercomReceived(callerInfo: String) {
        Timber.i("Intercom received from: $callerInfo")
    }

    // 禁止返回键退出（执法仪主应用不允许被退出）
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        Timber.d("返回键被拦截，执法仪主应用禁止退出")
    }

    // 物理按键绑定（DSJ-Z6 执法仪）
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            KeyEvent.KEYCODE_CAMERA, 293 -> {
                toggleLocalRecording()
                true
            }
            KeyEvent.KEYCODE_FOCUS, 294 -> {
                capturePhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun registerHardwareKeyReceiver() {
        hardwareKeyReceiver.onKeyAction = { action ->
            runOnUiThread { handleHardwareKey(action) }
        }
        registerReceiver(hardwareKeyReceiver, HardwareKeyReceiver.createIntentFilter())
        Timber.i("Hardware key receiver registered")
    }

    private fun handleHardwareKey(action: HardwareKeyReceiver.KeyAction) {
        when (action) {
            HardwareKeyReceiver.KeyAction.VIDEO_PRESS -> toggleLocalRecording()
            HardwareKeyReceiver.KeyAction.VIDEO_LONG_PRESS -> { /* 忽略：PRESS 已触发一次，避免长按重复切换 */ }
            HardwareKeyReceiver.KeyAction.PHOTO_PRESS -> capturePhoto()
            HardwareKeyReceiver.KeyAction.PHOTO_LONG_PRESS -> capturePhoto()
            HardwareKeyReceiver.KeyAction.PHOTO_LONG_PRESS_CANCELED -> {}
            HardwareKeyReceiver.KeyAction.SOS_PRESS -> {
                val app = application as EnforcementApp
                if (!app.remoteConfigManager.config.value.sosEnabled) {
                    Timber.i("SOS_PRESS ignored: sosEnabled=false (远程配置已禁用)")
                    return
                }
                toggleFlashLight()
                CoroutineScope(Dispatchers.IO).launch {
                    alarmReporter.report(
                        "sos", 3, "紧急按钮报警",
                        "${settings.deviceId} 触发紧急按钮",
                        app.locationService.getLatitude().takeIf { it != 0.0 },
                        app.locationService.getLongitude().takeIf { it != 0.0 }
                    )
                }
            }
            HardwareKeyReceiver.KeyAction.SOS_LONG_PRESS -> {
                val app = application as EnforcementApp
                if (!app.remoteConfigManager.config.value.sosEnabled) {
                    Timber.i("SOS_LONG_PRESS ignored: sosEnabled=false (远程配置已禁用)")
                    return
                }
                LightState.strobeRedBlueOn = true
                DeviceHardwareManager.setStrobeRedBlueBlink()
                Timber.i("SOS: strobe red-blue blink activated")
                CoroutineScope(Dispatchers.IO).launch {
                    alarmReporter.report(
                        "sos", 3, "紧急按钮报警(长按)",
                        "${settings.deviceId} 长按触发紧急按钮",
                        app.locationService.getLatitude().takeIf { it != 0.0 },
                        app.locationService.getLongitude().takeIf { it != 0.0 }
                    )
                }
            }
            HardwareKeyReceiver.KeyAction.PTT_DOWN -> {
                val sipManager = (application as EnforcementApp).sipManager
                if (!sipManager.isInCall()) {
                    val targetUri = "sip:commander@${settings.sipServer}"
                    sipManager.makeCall(targetUri)
                    Timber.i("PTT: calling $targetUri")
                }
            }
            HardwareKeyReceiver.KeyAction.PTT_UP -> {
                val sipManager = (application as EnforcementApp).sipManager
                if (sipManager.isInCall()) {
                    sipManager.hangup()
                    Timber.i("PTT: call ended")
                }
            }
            HardwareKeyReceiver.KeyAction.MARK_PRESS -> {
                Timber.i("Mark key pressed: timestamp=${System.currentTimeMillis()}")
            }
            HardwareKeyReceiver.KeyAction.MARK_LONG_PRESS -> showLightPanel()
            HardwareKeyReceiver.KeyAction.RECORD_PRESS -> Timber.i("Record key pressed")
            HardwareKeyReceiver.KeyAction.RECORD_LONG_PRESS -> Timber.i("Record key long pressed")
            HardwareKeyReceiver.KeyAction.FN_PRESS -> startInternalActivity(Intent(this, FunctionActivity::class.java))
            HardwareKeyReceiver.KeyAction.FN_LONG_PRESS -> startInternalActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun toggleFlashLight() {
        isFlashOn = !isFlashOn
        LightState.flashOn = isFlashOn
        val hw = DeviceHardwareManager
        if (hw.isAvailable()) {
            hw.setFlashLight(isFlashOn)
        } else {
            try {
                val cameraManager = getSystemService(CameraManager::class.java)
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                cameraManager.setTorchMode(cameraId, isFlashOn)
            } catch (e: Exception) {
                Timber.e(e, "toggleFlashLight failed")
            }
        }
        Timber.i("Flash light toggled: $isFlashOn")
    }

    private fun playVoice(resId: Int) {
        voicePlayer?.release()
        voicePlayer = MediaPlayer.create(this, resId)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }

    private fun showLightPanel() {
        LightPanelFragment().show(supportFragmentManager, "light_panel")
    }

    private fun toggleLocalRecording() {
        val now = System.currentTimeMillis()
        if (now - lastRecordingToggleTime < DEBOUNCE_MS) return
        lastRecordingToggleTime = now
        if (isRecording) {
            val recordFile = currentRecordFile

            mediaService?.stopLocalRecording()
            isRecording = false
            currentRecordFile = null
            playVoice(R.raw.voice_stop_recording)
            showRecordingIndicator(false)
            Timber.i("Local recording stopped: ${recordFile?.name}")

            // 不再自动上传：只通知平台文件清单（含缩略图），等待平台 Pull 任务或手动触发上传
            if (recordFile != null && recordFile.exists()) {
                val app = application as EnforcementApp
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.fileSyncService.syncFileList()
                        Timber.i("录像完成立即触发文件清单同步: ${recordFile.name}, ${recordFile.length() / 1024}KB")
                    } catch (e: Exception) {
                        Timber.e(e, "录像完成后同步清单失败")
                    }
                }
            }
        } else {
            val dir = File(filesDir, "recordings").apply { mkdirs() }
            val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
            currentRecordFile = file
            mediaService?.startLocalRecording(file)
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            playVoice(R.raw.voice_start_recording)
            showRecordingIndicator(true)
            Timber.i("Local recording started: ${file.name}")
        }
    }

    private fun showRecordingIndicator(show: Boolean) {
        val indicator = findViewById<TextView>(R.id.tvRecordingIndicator)
        if (show) {
            indicator.visibility = View.VISIBLE
            recordingTimerHandler.post(recordingTimerRunnable)
        } else {
            indicator.visibility = View.GONE
            recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        }
    }

    private fun updateRecordingTimer() {
        val indicator = findViewById<TextView>(R.id.tvRecordingIndicator)
        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
        val min = elapsed / 60
        val sec = elapsed % 60
        indicator.text = String.format("● REC %02d:%02d", min, sec)
        // 闪烁红点效果
        val alpha = if ((elapsed % 2) == 0L) 1.0f else 0.6f
        indicator.alpha = alpha
    }

    private fun capturePhoto() {
        val now = System.currentTimeMillis()
        if (now - lastPhotoTime < DEBOUNCE_MS) return
        lastPhotoTime = now
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        // 播放快门声
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        mediaService?.capturePhoto(file) { savedFile ->
            Timber.i("Photo captured: ${savedFile.name}")
            runOnUiThread { playVoice(R.raw.voice_photo_taken) }
            // 立即触发文件清单同步，让平台看到缩略图（不自动上传，等平台 Pull）
            val app = application as EnforcementApp
            CoroutineScope(Dispatchers.IO).launch {
                try { app.fileSyncService.syncFileList() }
                catch (e: Exception) { Timber.w(e, "拍照后同步清单失败") }
            }
        }
    }

    private var workTaskToneGenerator: android.media.ToneGenerator? = null
    private var workTaskToneHandler: Handler? = null
    private var workTaskToneRunnable: Runnable? = null
    private var workTaskDialog: AlertDialog? = null

    private fun showWorkTaskAlert(title: String, priority: String) {
        Timber.i("工单弹窗: title=$title, priority=$priority")
        // 如果已有弹窗在显示，先关掉
        workTaskDialog?.dismiss()

        val toneType = when (priority) {
            "urgent" -> android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
            "high" -> android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            else -> android.media.ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE
        }
        val interval = when (priority) {
            "urgent" -> 2000L; "high" -> 4000L; else -> 6000L
        }

        // 循环响铃
        stopWorkTaskTone()
        workTaskToneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
        workTaskToneHandler = Handler(Looper.getMainLooper())
        workTaskToneRunnable = object : Runnable {
            override fun run() {
                workTaskToneGenerator?.startTone(toneType, 1000)
                workTaskToneHandler?.postDelayed(this, interval)
            }
        }
        workTaskToneRunnable?.run()

        val priorityLabel = when (priority) { "urgent" -> "【紧急】"; "high" -> "【高】"; else -> "" }

        workTaskDialog = AlertDialog.Builder(this)
            .setTitle("${priorityLabel}新工单")
            .setMessage(title)
            .setCancelable(false)
            .setPositiveButton("阅读") { d, _ ->
                stopWorkTaskTone()
                d.dismiss()
                workTaskDialog = null
                isNavigatingInternally = true
                // 自动标记所有未阅工单为已阅，然后打开工单列表
                com.hdcollection.enforcement.ui.worktask.WorkTaskListActivity.markAllAsRead(
                    settings.platformApiUrl, settings.deviceId
                )
                val intent = Intent(this@MainActivity, com.hdcollection.enforcement.ui.worktask.WorkTaskListActivity::class.java)
                startActivity(intent)
            }
            .create()
        workTaskDialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        workTaskDialog?.show()
    }

    /** 上线时检查未阅读/执行中工单 */
    private fun checkPendingWorkTasks() {
        val settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        if (settings.platformApiUrl.isEmpty()) return
        com.hdcollection.enforcement.ui.worktask.WorkTaskListActivity.checkPendingTasks(
            settings.platformApiUrl, settings.deviceId
        ) { unread, inProgress ->
            if (unread > 0) {
                showWorkTaskAlert("您有 $unread 条未阅读工单", if (unread >= 3) "urgent" else "normal")
            } else if (inProgress > 0) {
                // 执行中未完成，提醒一次
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 80)
                tg.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 1500)
                Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 2000)
                android.widget.Toast.makeText(this, "您有 $inProgress 条执行中工单未完成", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopWorkTaskTone() {
        workTaskToneRunnable?.let { workTaskToneHandler?.removeCallbacks(it) }
        workTaskToneGenerator?.release()
        workTaskToneGenerator = null
        workTaskToneHandler = null
        workTaskToneRunnable = null
    }

    private fun showIncomingCallDialog(callerUri: String) {
        Timber.i("对讲来电: caller=$callerUri")
        val view = layoutInflater.inflate(R.layout.dialog_incoming_call, null)
        view.findViewById<TextView>(R.id.tvCaller).text = callerUri

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<Button>(R.id.btnAccept).setOnClickListener {
            Timber.i("对讲来电已接听: caller=$callerUri")
            (application as EnforcementApp).sipManager.acceptCall()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnDecline).setOnClickListener {
            Timber.i("对讲来电已拒绝: caller=$callerUri")
            (application as EnforcementApp).sipManager.declineCall()
            dialog.dismiss()
        }
        dialog.show()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
