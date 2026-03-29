package com.hdcollection.enforcement.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.media.MediaPlayer
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
import com.hdcollection.enforcement.camera.Camera2Preview
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
import com.hdcollection.enforcement.receiver.UsbStateReceiver
import com.hdcollection.enforcement.ui.settings.SettingsActivity
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
    private lateinit var camera: Camera2Preview
    private lateinit var alarmReporter: AlarmReporter

    private var isNavigatingInternally = false
    private var isRecording = false
    private var isFlashOn = false
    private var currentRecordFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val hardwareKeyReceiver = HardwareKeyReceiver()
    private val usbStateReceiver = UsbStateReceiver()

    // 语音提示播放器
    private var voicePlayer: MediaPlayer? = null

    // 系统快门音
    private val shutterSound = MediaActionSound()

    // 按键防抖：设备同时发 KeyEvent + 广播，防止同一操作触发两次
    private var lastRecordingToggleTime = 0L
    private var lastPhotoTime = 0L
    private val DEBOUNCE_MS = 800L

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

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        alarmReporter = AlarmReporter(settings)
        try {
            gb28181Manager = GB28181Manager(settings, this)
            Timber.i("GB28181Manager 初始化成功")
        } catch (e: Exception) {
            Timber.e(e, "GB28181Manager 初始化失败")
            throw e
        }

        val surfaceView = findViewById<SurfaceView>(R.id.surfacePreview)
        try {
            camera = Camera2Preview(this, surfaceView)
            Timber.i("Camera2Preview 初始化成功")
        } catch (e: Exception) {
            Timber.e(e, "Camera2Preview 初始化失败")
            throw e
        }

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

        // 注册 SIP 来电监听
        (application as EnforcementApp).sipManager.onIncomingCall = { call ->
            runOnUiThread { showIncomingCallDialog(call) }
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
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        voicePlayer?.release()
        shutterSound.release()
        gb28181Manager.unregister()
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
            camera.switchCamera()
            Timber.i("摄像头切换: front=${camera.isFrontCamera()}")
        }
    }

    private fun startGB28181() {
        updateStreamStatus("注册中", "#FF9800")
        gb28181Manager.register()
    }

    private var watermarkEnabled = false

    private fun updateClock() {
        val now = Date()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tvTime).text = sdf.format(now)

        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        findViewById<TextView>(R.id.tvDate).text = dateSdf.format(now)

        // 更新 GPS 信息
        val locService = (application as EnforcementApp).locationService
        val gpsText = if (locService.getLatitude() != 0.0) {
            String.format("%.6f, %.6f %s", locService.getLatitude(), locService.getLongitude(), locService.getProviderDesc())
        } else {
            "定位中..."
        }
        findViewById<TextView>(R.id.tvGps)?.text = gpsText

        // 更新摄像头画面水印（硬件 OSD，直接烧录到视频帧中）
        updateCameraWatermark(now)
    }

    private fun updateCameraWatermark(now: Date) {
        try {
            val dm = android.app.devicemanager.DeviceManager.getInstance()
            if (dm == null) {
                if (!watermarkEnabled) { Timber.w("DeviceManager 为 null，水印不可用"); watermarkEnabled = true }
                return
            }
            if (!watermarkEnabled) {
                dm.setCameraWaterMarkEnable(true)
                watermarkEnabled = true
                Timber.i("摄像头硬件水印已开启")
            }
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            dm.setCameraWaterMarkText(0, timeFmt.format(now))
            dm.setCameraWaterMarkText(1, settings.deviceId)
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
        runOnUiThread { updateStreamStatus("注册在线", "#4CAF50") }
    }

    override fun onRegistrationFailed(reason: String) {
        Timber.w("GB28181 registration failed: $reason")
        runOnUiThread { updateStreamStatus("断网", "#F44336") }
    }

    override fun onStreamStartRequested(channelId: String, rtpIp: String, rtpPort: Int, ssrc: Int) {
        Timber.i("Stream start requested: $channelId -> $rtpIp:$rtpPort ssrc=$ssrc")
        runOnUiThread { updateStreamStatus("推流中", "#2196F3") }
        camera.startEncoding(rtpIp, rtpPort, ssrc)
    }

    override fun onStreamStopRequested(channelId: String) {
        Timber.i("Stream stop requested: $channelId")
        camera.stopEncoding()
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
            HardwareKeyReceiver.KeyAction.VIDEO_LONG_PRESS -> toggleLocalRecording()
            HardwareKeyReceiver.KeyAction.PHOTO_PRESS -> capturePhoto()
            HardwareKeyReceiver.KeyAction.PHOTO_LONG_PRESS -> capturePhoto()
            HardwareKeyReceiver.KeyAction.PHOTO_LONG_PRESS_CANCELED -> {}
            HardwareKeyReceiver.KeyAction.SOS_PRESS -> {
                toggleFlashLight()
                // 上报 SOS 告警
                val app = application as EnforcementApp
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
                LightState.strobeRedBlueOn = true
                DeviceHardwareManager.setStrobeRedBlueBlink()
                Timber.i("SOS: strobe red-blue blink activated")
                // 上报 SOS 长按告警
                val app = application as EnforcementApp
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
            // 先保存文件引用（stopLocalRecording 后 camera 内部引用会被清除）
            val recordFile = currentRecordFile
            val startTime = recordingStartTime

            camera.stopLocalRecording()
            isRecording = false
            currentRecordFile = null
            playVoice(R.raw.voice_stop_recording)
            showRecordingIndicator(false)
            Timber.i("Local recording stopped")

            // 将录像文件加入上传队列
            if (recordFile != null && recordFile.exists()) {
                val app = application as EnforcementApp
                val locService = app.locationService
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val uploadClient = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                            .build()
                        val uploadService = com.hdcollection.enforcement.upload.UploadService(
                            com.hdcollection.enforcement.data.db.AppDatabase.getInstance(applicationContext).uploadQueueDao(),
                            com.hdcollection.enforcement.data.AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE)),
                            uploadClient
                        )
                        uploadService.enqueue(
                            deviceId = settings.deviceId,
                            fileType = "video",
                            file = recordFile,
                            lat = locService.getLatitude().takeIf { it != 0.0 },
                            lng = locService.getLongitude().takeIf { it != 0.0 },
                            recordTime = startTime
                        )
                        Timber.i("录像已加入上传队列: ${recordFile.name}, ${recordFile.length() / 1024}KB")
                    } catch (e: Exception) {
                        Timber.e(e, "录像入队失败")
                    }
                }

                // 触发 WorkManager 立即执行上传任务
                androidx.work.WorkManager.getInstance(applicationContext)
                    .enqueueUniqueWork(
                        "auto_upload",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        androidx.work.OneTimeWorkRequestBuilder<com.hdcollection.enforcement.service.UploadWorker>()
                            .setConstraints(
                                androidx.work.Constraints.Builder()
                                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                    .build()
                            )
                            .build()
                    )
            }
        } else {
            val dir = getExternalFilesDir("recordings") ?: filesDir
            val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
            currentRecordFile = file
            camera.startLocalRecording(file)
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
        val dir = getExternalFilesDir("photos") ?: filesDir
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        // 播放快门声
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        camera.capturePhoto(file) { savedFile ->
            Timber.i("Photo captured: ${savedFile.name}")
            runOnUiThread { playVoice(R.raw.voice_photo_taken) }
        }
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
