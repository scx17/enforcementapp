package com.hdcollection.enforcement.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.SurfaceView
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
    private var wakeLock: PowerManager.WakeLock? = null

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

        setupBottomButtons()
        updateDeviceInfo()
        updateStreamStatus("初始化", "#9E9E9E")

        if (hasRequiredPermissions()) {
            startGB28181()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }

        // 注册 SIP 来电监听
        (application as EnforcementApp).sipManager.onIncomingCall = { call ->
            runOnUiThread { showIncomingCallDialog(call) }
        }
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
    }

    private fun startGB28181() {
        updateStreamStatus("注册中", "#FF9800")
        gb28181Manager.register()
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.tvTime).text = sdf.format(Date())

        val dateSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        findViewById<TextView>(R.id.tvDate).text = dateSdf.format(Date())
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
        // SIP 对讲 UI 在 Task 17 实现
    }

    // 物理按键绑定（DSJ-Z6 执法仪）
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            // 录像键：KEYCODE_CAMERA 或执法仪自定义键码 293
            KeyEvent.KEYCODE_CAMERA, 293 -> {
                toggleLocalRecording()
                true
            }
            // 截图键：KEYCODE_FOCUS 或执法仪自定义键码 294
            KeyEvent.KEYCODE_FOCUS, 294 -> {
                capturePhoto()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showLightPanel() {
        LightPanelFragment().show(supportFragmentManager, "light_panel")
    }

    private fun toggleLocalRecording() {
        if (isRecording) {
            camera.stopLocalRecording()
            isRecording = false
            Timber.i("Local recording stopped")
        } else {
            val dir = getExternalFilesDir("recordings") ?: filesDir
            val file = File(dir, "rec_${System.currentTimeMillis()}.mp4")
            camera.startLocalRecording(file)
            isRecording = true
            Timber.i("Local recording started: ${file.name}")
        }
    }

    private fun capturePhoto() {
        val dir = getExternalFilesDir("photos") ?: filesDir
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        camera.capturePhoto(file) { savedFile ->
            Timber.i("Photo captured: ${savedFile.name}")
            // 上传队列在 Task 15 实现
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
