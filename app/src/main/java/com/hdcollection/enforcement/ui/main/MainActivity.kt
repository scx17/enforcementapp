package com.hdcollection.enforcement.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.media.MediaActionSound
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.hardware.DeviceHardwareManager
import com.hdcollection.enforcement.hardware.HardwareKeyReceiver
import com.hdcollection.enforcement.hardware.LightState
import com.hdcollection.enforcement.ui.LightPanelFragment
import com.hdcollection.enforcement.ui.function.FunctionActivity
import com.hdcollection.enforcement.ui.playback.PlaybackActivity
import com.hdcollection.enforcement.logging.UserOpLogger
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

class MainActivity : AppCompatActivity(), MediaCaptureService.Listener {

    private lateinit var settings: AppSettings
    private lateinit var alarmReporter: AlarmReporter

    private var isNavigatingInternally = false
    private var isRequestingPermissions = false
    private var isFullyInitialized = false  // onCreate 完整跑完才为 true；未设置时禁止 onPause 触发重启循环
    private var isRecording = false
    private var isFlashOn = false
    private var currentRecordFile: File? = null
    private var mediaService: MediaCaptureService? = null
    private val mediaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MediaCaptureService.LocalBinder)?.getService()
            mediaService = svc
            Timber.i("MediaCaptureService connected")
            svc?.addListener(this@MainActivity)
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

    // 音频录制
    private var currentAudioFile: File? = null
    private var lastAudioToggleTime: Long = 0L
    private var audioTimerStart: Long = 0L
    private val audioTimerHandler = Handler(Looper.getMainLooper())
    private val audioTimerRunnable = object : Runnable {
        override fun run() {
            updateAudioRecordingTimer()
            audioTimerHandler.postDelayed(this, 1000)
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

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        alarmReporter = AlarmReporter(settings)

        // 全新设备未配置 → 仅跳设置页，不启动服务，不请求权限，避免两个 Activity 同时争焦点触发 ANR
        if (settings.customCode.isBlank() && settings.deviceId.isBlank()) {
            Timber.w("设备未配置，跳转设置页")
            isNavigatingInternally = true
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        // 启动 MediaCaptureService（前台服务）— 仅在设备已配置时启动
        val mediaIntent = Intent(this, MediaCaptureService::class.java)
        ContextCompat.startForegroundService(this, mediaIntent)
        bindService(mediaIntent, mediaServiceConnection, Context.BIND_AUTO_CREATE)

        // GB28181Manager + Camera 已由 MediaCaptureService 持有

        // 检查自定义设备编号是否已配置
        if (settings.customCode.isBlank()) {
            // 已有 SIP 编号但无自定义编号 → 从平台拉取（迁移回填的默认值）
            syncCustomCodeFromPlatform()
        } else {
            // 已有编号 → 静默同步（处理管理员改名）
            syncCustomCodeFromPlatform()
        }

        // 预加载快门音
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        setupBottomButtons()
        updateDeviceInfo()
        updateStreamStatus("初始化", "#9E9E9E")

        // 录像指示器默认隐藏
        findViewById<TextView>(R.id.tvRecordingIndicator).visibility = View.GONE

        if (!hasRequiredPermissions()) {
            isRequestingPermissions = true
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }

        // 注册 SIP 来电监听
        (application as EnforcementApp).sipManager.onIncomingCall = { call ->
            runOnUiThread { showIncomingCallDialog(call) }
        }

        // 注册工单到达监听 — App 内弹窗 + 循环声音
        (application as EnforcementApp).notificationService.onWorkTaskReceived = { title, priority ->
            runOnUiThread { showWorkTaskAlert(title, priority) }
        }

        // 平台修改设备编号后实时刷新 UI
        (application as EnforcementApp).notificationService.onCustomCodeChanged = { _ ->
            runOnUiThread { updateDeviceInfo() }
        }

        // 注册硬件按键广播接收器
        registerHardwareKeyReceiver()

        // 注册 USB 插拔广播接收器
        registerReceiver(usbStateReceiver, UsbStateReceiver.createIntentFilter())
        Timber.i("USB state receiver registered")

        isFullyInitialized = true
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        // 仅在完整初始化后、屏幕亮着且被系统切走时拉回前台
        if (!isFullyInitialized) return
        if (!isNavigatingInternally && !isRequestingPermissions) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) {
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
    }

    override fun onResume() {
        super.onResume()
        if (isFullyInitialized) isNavigatingInternally = false
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
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        audioTimerHandler.removeCallbacks(audioTimerRunnable)
        voicePlayer?.release()
        shutterSound.release()
        mediaService?.removeListener(this)
        mediaService?.detachPreview()
        try { unbindService(mediaServiceConnection) } catch (_: Exception) {}
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
                String.format("%.2f, %.2f %s", locService.getLatitude(), locService.getLongitude(), locService.getProviderDesc())
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

        // 每 10 秒刷新一次电量和存储（避免每秒读取开销）
        val sec = now.time / 1000
        if (sec % 10 == 0L) {
            updateStorageInfo()
        }

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
        } catch (e: Throwable) {
            if (!watermarkEnabled) {
                Timber.w("硬件水印不可用: ${e.message}")
                watermarkEnabled = true // 只报一次
            }
        }
    }

    private fun updateDeviceInfo() {
        val displayCode = settings.customCode.ifEmpty {
            settings.deviceId.takeLast(7).ifEmpty { "未配置" }
        }
        findViewById<TextView>(R.id.tvDeviceId).text = displayCode

        val resolution = settings.videoResolution
        val bitrate = settings.videoBitrate
        findViewById<TextView>(R.id.tvEncoding).text = "H264 $resolution ${bitrate}k"

        Timber.i("设备信息加载: customCode=$displayCode, sipDeviceId=${settings.deviceId}, sipServer=${settings.sipServer}:${settings.sipPort}, resolution=$resolution, bitrate=${bitrate}k")

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
        updateBatteryInfo()
    }

    private fun updateBatteryInfo() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        val tvBattery = findViewById<TextView>(R.id.tvBattery)
        val iconRes = when {
            isCharging -> R.drawable.ic_battery_charging
            level <= 20 -> R.drawable.ic_battery_critical
            level <= 50 -> R.drawable.ic_battery_low
            level <= 80 -> R.drawable.ic_battery_medium
            else -> R.drawable.ic_battery_full
        }
        tvBattery.text = " ${level}%"
        tvBattery.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        tvBattery.setTextColor(
            if (level <= 20 && !isCharging) Color.parseColor("#FF5252") else Color.WHITE
        )
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
        isRequestingPermissions = false
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            if (denied.isEmpty()) {
                Timber.i("所有权限已授予: ${permissions.joinToString()}")
            } else {
                Timber.w("权限被拒绝: ${denied.joinToString()}")
                updateStreamStatus("权限不足", "#F44336")
            }
        }
    }

    // MediaCaptureService.Listener 回调 — Service 处理 StreamCallback，Activity 只更新 UI
    override fun onRegistered(deviceId: String) {
        runOnUiThread {
            updateStreamStatus("注册在线", "#4CAF50")
            checkPendingWorkTasks()
        }
    }

    override fun onRegistrationFailed(reason: String) {
        runOnUiThread { updateStreamStatus("断网", "#F44336") }
    }

    override fun onStreamStarted(channelId: String) {
        runOnUiThread { updateStreamStatus("推流中", "#2196F3") }
    }

    override fun onStreamStopped(channelId: String) {
        runOnUiThread { updateStreamStatus("注册在线", "#4CAF50") }
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
                UserOpLogger.record(
                    operationType = "SOS",
                    description = "触发紧急按钮",
                    critical = true
                )
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
                UserOpLogger.record(
                    operationType = "SOS",
                    description = "长按触发紧急按钮",
                    extraData = """{"strobe":"red_blue"}""",
                    critical = true
                )
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
                    UserOpLogger.record(
                        operationType = "PTTStart",
                        description = "PTT 发起对讲",
                        targetType = "sip",
                        targetId = targetUri
                    )
                }
            }
            HardwareKeyReceiver.KeyAction.PTT_UP -> {
                val sipManager = (application as EnforcementApp).sipManager
                if (sipManager.isInCall()) {
                    sipManager.hangup()
                    Timber.i("PTT: call ended")
                    UserOpLogger.record(
                        operationType = "PTTEnd",
                        description = "PTT 结束对讲"
                    )
                }
            }
            HardwareKeyReceiver.KeyAction.MARK_PRESS -> {
                Timber.i("Mark key pressed: timestamp=${System.currentTimeMillis()}")
            }
            HardwareKeyReceiver.KeyAction.MARK_LONG_PRESS -> showLightPanel()
            HardwareKeyReceiver.KeyAction.RECORD_PRESS -> toggleAudioRecording()
            HardwareKeyReceiver.KeyAction.RECORD_LONG_PRESS -> Timber.d("Record key long press ignored")
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
        if (mediaService?.isAudioRecording() == true) {
            Toast.makeText(this, "正在录音中，无法录像", Toast.LENGTH_SHORT).show()
            Timber.w("toggleLocalRecording rejected: audio recording in progress")
            return
        }
        if (isRecording) {
            val recordFile = currentRecordFile

            mediaService?.stopLocalRecording()
            isRecording = false
            currentRecordFile = null
            playVoice(R.raw.voice_stop_recording)
            showRecordingIndicator(false)
            Timber.i("Local recording stopped: ${recordFile?.name}")
            UserOpLogger.record(
                operationType = "StopRecording",
                description = "手动停止录像 ${recordFile?.name ?: "(unknown)"}",
                targetType = "recording",
                targetId = recordFile?.name,
                critical = true
            )

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
            UserOpLogger.record(
                operationType = "StartRecording",
                description = "手动开始录像 ${file.name}",
                targetType = "recording",
                targetId = file.name,
                critical = true
            )
        }
    }

    private fun toggleAudioRecording() {
        val now = System.currentTimeMillis()
        if (now - lastAudioToggleTime < DEBOUNCE_MS) return
        lastAudioToggleTime = now

        // 互斥门控：录像中禁止录音
        if (isRecording) {
            Toast.makeText(this, "正在录像中，无法录音", Toast.LENGTH_SHORT).show()
            Timber.w("toggleAudioRecording rejected: video recording in progress")
            return
        }

        val service = mediaService ?: run {
            Timber.w("toggleAudioRecording: mediaService is null")
            return
        }

        if (service.isAudioRecording()) {
            // ── 停止 ──
            val file = currentAudioFile
            val duration = service.stopAudioRecording()
            currentAudioFile = null
            playVoice(R.raw.voice_stop_audio)
            showAudioRecordingIndicator(false)
            Toast.makeText(this, "音频已保存 ${duration / 1000}s", Toast.LENGTH_SHORT).show()
            Timber.i("Audio recording stopped: file=${file?.name}, duration_ms=$duration")
            UserOpLogger.record(
                operationType = "StopAudioRecording",
                description = "手动停止录音 ${file?.name ?: "(unknown)"}",
                targetType = "audio",
                targetId = file?.name,
                critical = true
            )
            if (file != null && file.exists()) {
                // 音频上传走"服务端拉取"链路：FileListSyncService 已预置 audios/ 目录扫描
                // （fileType="audio"+duration），此处仅触发一次清单同步即可
                val app = application as EnforcementApp
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        app.fileSyncService.syncFileList()
                        Timber.i("录音完成触发文件清单同步: ${file.name}, ${file.length() / 1024}KB")
                    } catch (e: Exception) {
                        Timber.e(e, "录音完成后同步清单失败")
                    }
                }
            }
        } else {
            // ── 启动 ──
            val dir = File(filesDir, "audios").apply { mkdirs() }
            val file = File(dir, "audio_${System.currentTimeMillis()}.m4a")
            currentAudioFile = file
            try {
                service.startAudioRecording(file)
            } catch (e: Exception) {
                Toast.makeText(this, "录音启动失败", Toast.LENGTH_SHORT).show()
                Timber.e(e, "toggleAudioRecording: start failed")
                currentAudioFile = null
                return
            }
            audioTimerStart = System.currentTimeMillis()
            playVoice(R.raw.voice_start_audio)
            showAudioRecordingIndicator(true)
            Toast.makeText(this, "音频开始录制", Toast.LENGTH_SHORT).show()
            Timber.i("Audio recording started: file=${file.name}")
            UserOpLogger.record(
                operationType = "StartAudioRecording",
                description = "手动开始录音 ${file.name}",
                targetType = "audio",
                targetId = file.name,
                critical = true
            )
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

        // 自动分片：到达设定时长后自动切文件
        val segmentMin = getEffectiveSegmentMinutes()
        if (segmentMin > 0 && min >= segmentMin) {
            Timber.i("录像分片: 已达 ${segmentMin} 分钟，自动切文件")
            rotateRecordingSegment()
        }
    }

    private fun showAudioRecordingIndicator(show: Boolean) {
        val v = findViewById<TextView>(R.id.tvAudioRecordingIndicator)
        if (show) {
            v.visibility = View.VISIBLE
            audioTimerHandler.post(audioTimerRunnable)
        } else {
            v.visibility = View.GONE
            audioTimerHandler.removeCallbacks(audioTimerRunnable)
        }
    }

    private fun updateAudioRecordingTimer() {
        val v = findViewById<TextView>(R.id.tvAudioRecordingIndicator)
        val elapsed = (System.currentTimeMillis() - audioTimerStart) / 1000
        val min = elapsed / 60
        val sec = elapsed % 60
        v.text = String.format("🎙 录音中 %02d:%02d", min, sec)
        v.alpha = if ((elapsed % 2) == 0L) 1.0f else 0.75f
    }

    /** 获取生效的分片时长（远程优先，否则本地设置） */
    private fun getEffectiveSegmentMinutes(): Int {
        val remoteCfg = (application as EnforcementApp).remoteConfigManager.config.value
        val remote = remoteCfg.recordingSegmentMinutes
        return if (remote > 0) remote else settings.recordingSegmentMinutes
    }

    /** 无缝切换录制文件：停止当前 → 立即开始新文件 */
    private fun rotateRecordingSegment() {
        if (!isRecording) return
        val oldFile = currentRecordFile

        // 停止当前录制
        mediaService?.stopLocalRecording()
        Timber.i("分片录像停止: ${oldFile?.name}")

        // 立即开始新文件
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val newFile = File(dir, "rec_${System.currentTimeMillis()}.mp4")
        currentRecordFile = newFile
        mediaService?.startLocalRecording(newFile)
        recordingStartTime = System.currentTimeMillis()
        Timber.i("分片录像开始: ${newFile.name}")

        // 后台同步旧文件
        if (oldFile != null && oldFile.exists()) {
            val app = application as EnforcementApp
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.fileSyncService.syncFileList()
                    Timber.i("分片录像同步清单: ${oldFile.name}, ${oldFile.length() / 1024}KB")
                } catch (e: Exception) {
                    Timber.e(e, "分片录像同步清单失败")
                }
            }
        }
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
            UserOpLogger.record(
                operationType = "TakePhoto",
                description = "手动拍照 ${savedFile.name}",
                targetType = "photo",
                targetId = savedFile.name
            )
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

    /** 启动时从平台拉取最新 customCode（处理管理员改名/首次迁移回填） */
    private fun syncCustomCodeFromPlatform() {
        val apiUrl = settings.platformApiUrl.trimEnd('/')
        if (apiUrl.isEmpty()) return
        Thread {
            try {
                val imei = try {
                    val dm = android.app.devicemanager.DeviceManager.getInstance()
                    dm.imei?.takeIf { it.isNotBlank() } ?: ""
                } catch (_: Throwable) { "" }
                val sipDeviceId = settings.deviceId
                // IMEI 和 SIP DeviceId 都没有时跳过
                if (imei.isBlank() && sipDeviceId.isBlank()) return@Thread

                val urlBuilder = StringBuilder("$apiUrl/api/device/me?")
                if (imei.isNotBlank()) urlBuilder.append("imei=$imei&")
                if (sipDeviceId.isNotBlank()) urlBuilder.append("deviceId=$sipDeviceId")
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(urlBuilder.toString().trimEnd('&', '?'))
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                val json = response.body?.string() ?: ""
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                if (root.get("success")?.asBoolean != true) return@Thread

                val data = root.getAsJsonObject("data")
                val serverCode = data.get("customCodeDisplay")?.asString
                    ?: data.get("customCode")?.asString ?: return@Thread

                if (serverCode.isNotBlank() && serverCode != settings.customCode) {
                    settings.customCode = serverCode
                    Timber.i("同步 customCode 成功: $serverCode")
                    runOnUiThread { updateDeviceInfo() }
                }
            } catch (e: Exception) {
                Timber.w(e, "同步 customCode 失败（网络异常）")
            }
        }.start()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
