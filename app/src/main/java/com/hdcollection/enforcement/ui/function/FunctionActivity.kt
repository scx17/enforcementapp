package com.hdcollection.enforcement.ui.function

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.service.UploadWorker
import com.hdcollection.enforcement.ui.LightPanelFragment
import com.hdcollection.enforcement.ui.notification.NotificationListActivity
import com.hdcollection.enforcement.ui.worktask.WorkTaskListActivity
import timber.log.Timber
import java.io.File

class FunctionActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_function)

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))

        // 返回按钮（设备无 Home 键，必须有显式返回）
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // 集群对讲 —— 呼叫指挥中心 SIP URI
        findViewById<LinearLayout>(R.id.btnGroupIntercom).setOnClickListener {
            val sipManager = (application as EnforcementApp).sipManager
            if (sipManager.isInCall()) {
                sipManager.hangup()
                Toast.makeText(this, "已挂断对讲", Toast.LENGTH_SHORT).show()
            } else {
                val settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
                val targetUri = "sip:commander@${settings.sipServer}"
                sipManager.makeCall(targetUri)
                Toast.makeText(this, "正在呼叫指挥中心...", Toast.LENGTH_SHORT).show()
                Timber.i("Group intercom: calling $targetUri")
            }
        }

        // 运行日志
        findViewById<LinearLayout>(R.id.btnRunLog).setOnClickListener {
            showLogFiles()
        }

        // 平台通知
        findViewById<LinearLayout>(R.id.btnNotification).setOnClickListener {
            startActivity(Intent(this, NotificationListActivity::class.java))
        }

        // 单独拍照
        findViewById<LinearLayout>(R.id.btnPhoto).setOnClickListener {
            capturePhoto()
        }

        // 灯光控制
        findViewById<LinearLayout>(R.id.btnLightControl).setOnClickListener {
            LightPanelFragment().show(supportFragmentManager, "light_panel")
        }

        // 作业工单
        findViewById<LinearLayout>(R.id.btnWorkTask).setOnClickListener {
            startActivity(Intent(this, WorkTaskListActivity::class.java))
        }
    }

    private fun showLogFiles() {
        val logDir = getExternalFilesDir("logs") ?: filesDir
        val logs = logDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
        val count = logs?.size ?: 0
        Toast.makeText(this, "共 $count 个日志文件", Toast.LENGTH_SHORT).show()
        // 完整日志页面在需要时扩展
        Timber.d("Log files: $count in ${logDir.absolutePath}")
    }

    private fun capturePhoto() {
        // Camera2Preview 需要 SurfaceView，从主界面调用
        // 此处通过广播通知 MainActivity 拍照
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        Timber.i("Photo capture requested from FunctionActivity: ${file.name}")
        Toast.makeText(this, "请在主界面使用物理键拍照", Toast.LENGTH_SHORT).show()
    }
}
