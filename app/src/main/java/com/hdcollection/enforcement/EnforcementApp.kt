package com.hdcollection.enforcement

import android.app.Application
import com.hdcollection.enforcement.logging.FileLoggingTree
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
    }
}
