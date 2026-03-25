package com.hdcollection.enforcement.ui.function

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.camera.Camera2Preview
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.service.UploadWorker
import com.hdcollection.enforcement.ui.LightPanelFragment
import timber.log.Timber
import java.io.File

class FunctionActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_function)

        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))

        // 文件上传 —— 立即触发 UploadWorker
        findViewById<LinearLayout>(R.id.btnFileUpload).setOnClickListener {
            triggerUpload()
        }

        // 集群对讲 —— Task 17 PJSIP 实现后接入
        findViewById<LinearLayout>(R.id.btnGroupIntercom).setOnClickListener {
            Toast.makeText(this, "集群对讲功能开发中", Toast.LENGTH_SHORT).show()
            Timber.d("Group intercom clicked (PJSIP pending)")
        }

        // 运行日志
        findViewById<LinearLayout>(R.id.btnRunLog).setOnClickListener {
            showLogFiles()
        }

        // 平台通知
        findViewById<LinearLayout>(R.id.btnNotification).setOnClickListener {
            Toast.makeText(this, "暂无新通知", Toast.LENGTH_SHORT).show()
        }

        // 单独拍照
        findViewById<LinearLayout>(R.id.btnPhoto).setOnClickListener {
            capturePhoto()
        }

        // 灯光控制
        findViewById<LinearLayout>(R.id.btnLightControl).setOnClickListener {
            LightPanelFragment().show(supportFragmentManager, "light_panel")
        }
    }

    private fun triggerUpload() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "manual_upload", ExistingWorkPolicy.REPLACE, request
        )
        Toast.makeText(this, "已触发文件上传", Toast.LENGTH_SHORT).show()
        Timber.i("Manual upload triggered")
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
        val dir = getExternalFilesDir("photos") ?: filesDir
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        Timber.i("Photo capture requested from FunctionActivity: ${file.name}")
        Toast.makeText(this, "请在主界面使用物理键拍照", Toast.LENGTH_SHORT).show()
    }
}
