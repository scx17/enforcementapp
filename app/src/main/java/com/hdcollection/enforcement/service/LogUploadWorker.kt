package com.hdcollection.enforcement.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.logging.LogUploader
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager 定时任务：自动上传日志 + 清理过期日志
 * 由 EnforcementApp 注册，每 6 小时执行一次
 */
class LogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("LogUploadWorker: 开始执行")
        val settings = AppSettings(
            applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val logDir = applicationContext.getExternalFilesDir("logs") ?: File(applicationContext.filesDir, "logs")
        val uploader = LogUploader(settings, client, logDir)

        return try {
            // 上传历史日志
            uploader.uploadPendingLogs()
            // 清理 7 天前的日志
            uploader.cleanOldLogs()
            Timber.i("LogUploadWorker: 执行完成")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "LogUploadWorker: 执行失败")
            Result.retry()
        }
    }
}
