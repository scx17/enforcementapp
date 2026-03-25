package com.hdcollection.enforcement.logging

import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogUploader(
    private val settings: AppSettings,
    private val client: OkHttpClient
) {
    suspend fun upload(logFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!logFile.exists() || logFile.length() == 0L) return@withContext false
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.w("LogUploader: platformApiUrl or deviceId not configured, skipping")
            return@withContext false
        }
        try {
            val logDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", settings.deviceId)
                .addFormDataPart("logDate", logDate)
                .addFormDataPart(
                    "file", logFile.name,
                    logFile.asRequestBody("text/plain".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/device-log/upload")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                Timber.i("LogUploader: upload ${if (response.isSuccessful) "success" else "failed(${response.code})"}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Timber.e(e, "LogUploader: upload exception")
            false
        }
    }
}
