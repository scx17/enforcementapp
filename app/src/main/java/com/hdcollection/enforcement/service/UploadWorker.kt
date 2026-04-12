package com.hdcollection.enforcement.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.data.db.AppDatabase
import com.hdcollection.enforcement.upload.UploadService
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("UploadWorker doWork 开始, runAttemptCount=$runAttemptCount")
        val db = AppDatabase.getInstance(applicationContext)
        val settings = AppSettings(
            applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
        val service = UploadService(db.uploadQueueDao(), settings, client, applicationContext)
        return try {
            service.processPendingUploads()
            Timber.i("UploadWorker doWork 完成: 成功")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "UploadWorker doWork 完成: 失败, 将重试")
            Result.retry()
        }
    }
}
