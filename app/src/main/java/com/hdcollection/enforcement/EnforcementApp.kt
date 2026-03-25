package com.hdcollection.enforcement

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hdcollection.enforcement.logging.FileLoggingTree
import com.hdcollection.enforcement.service.UploadWorker
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EnforcementApp : Application() {

    lateinit var logFile: File
        private set

    override fun onCreate() {
        super.onCreate()

        // 初始化日志文件（外部存储，便于导出）
        val logDir = getExternalFilesDir("logs") ?: filesDir
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        logFile = File(logDir, "app_$dateStr.log")

        // 挂载 Timber：Debug 模式同时输出到 Logcat，始终输出到文件
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(FileLoggingTree(logFile))

        Timber.i("EnforcementApp started, log file: ${logFile.absolutePath}")

        // 注册网络恢复时自动触发上传
        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "auto_upload", ExistingWorkPolicy.REPLACE, uploadRequest
        )
    }
}
