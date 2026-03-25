package com.hdcollection.enforcement.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.data.db.AppDatabase
import com.hdcollection.enforcement.upload.UploadService
import okhttp3.OkHttpClient
import timber.log.Timber

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val settings = AppSettings(
            applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        )
        val service = UploadService(db.uploadQueueDao(), settings, OkHttpClient())
        return try {
            service.processPendingUploads()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "UploadWorker failed")
            Result.retry()
        }
    }
}
