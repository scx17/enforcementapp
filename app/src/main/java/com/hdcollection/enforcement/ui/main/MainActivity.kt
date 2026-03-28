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
import com.hdcollection.enforcement.ui.settings.SettingsActivity
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), StreamCallback {

    private lateinit var settings: AppSettings
    private lateinit var gb28181Manager: GB28181Manager
    private lateinit var camera: Camera2Preview

    private var isRecording = false
    private var isFlashOn = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val hardwareKeyReceiver = HardwareKeyReceiver()

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
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EnforcementApp::MainWakeLock")
        wakeLock?.acquire()

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        gb28181Manager = GB28181Manager(settings, this)

        val surfaceView = findViewById<SurfaceView>(R.id.surfacePreview)
        camera = Camera2Preview(this, surfaceView)

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
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        updateDeviceInfo()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hardwareKeyReceiver) } catch (_: Exception) {}
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        voicePlayer?.release()
        shutterSound.release()
        gb28181Manager.unregister()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun setupBottomButtons() {
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnLight).setOnClickListener {
            showLightPanel()
        }
        findViewById<ImageButton>(R.id.btnPlayback).setOnClickListener {
            startActivity(Intent(this, PlaybackActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnFunction).setOnClickListener {
            startActivity(Intent(this, FunctionActivity::class.java))
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startGB28181()
            } else {
                updateStreamStatus("权限不足", "#F44336")
                Timber.w("Required permissions denied")
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

    // 物理按键绑定（DSJ-Z6 执法仪）
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
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
            HardwareKeyReceiver.KeyAction.SOS_PRESS -> toggleFlashLight()
            HardwareKeyReceiver.KeyAction.SOS_LONG_PRESS -> {
                LightState.strobeRedBlueOn = true
                DeviceHardwareManager.setStrobeRedBlueBlink()
                Timber.i("SOS: strobe red-blue blink activated")
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
            HardwareKeyReceiver.KeyAction.FN_PRESS -> startActivity(Intent(this, FunctionActivity::class.java))
            HardwareKeyReceiver.KeyAction.FN_LONG_PRESS -> startActivity(Intent(this, SettingsActivity::class.java))
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
            camera.stopLocalRecording()
            isRecording = false
            playVoice(R.raw.voice_stop_recording)
            showRecordingIndicator(false)
            Timber.i("Local recording stopped")
        } else {
            val dir = getExternalFilesDir("recordings") ?: filesDir
            val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
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
        val view = layoutInflater.inflate(R.layout.dialog_incoming_call, null)
        view.findViewById<TextView>(R.id.tvCaller).text = callerUri

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<Button>(R.id.btnAccept).setOnClickListener {
            (application as EnforcementApp).sipManager.acceptCall()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnDecline).setOnClickListener {
            (application as EnforcementApp).sipManager.declineCall()
            dialog.dismiss()
        }
        dialog.show()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
