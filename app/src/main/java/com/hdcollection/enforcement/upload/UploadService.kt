package com.hdcollection.enforcement.upload

import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.data.db.UploadQueueDao
import com.hdcollection.enforcement.data.db.UploadQueueEntity
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
import java.util.Date
import java.util.Locale

class UploadService(
    private val dao: UploadQueueDao,
    private val settings: AppSettings,
    private val client: OkHttpClient
) {

    suspend fun enqueue(
        deviceId: String,
        fileType: String,
        file: File,
        lat: Double?,
        lng: Double?,
        recordTime: Long
    ) {
        dao.enqueue(
            UploadQueueEntity(
                deviceId = deviceId,
                fileType = fileType,
                filePath = file.absolutePath,
                fileName = file.name,
                fileSize = file.length(),
                latitude = lat,
                longitude = lng,
                recordTime = recordTime
            )
        )
        Timber.d("Enqueued upload: ${file.name} ($fileType)")
    }

    suspend fun processPendingUploads() {
        val pending = dao.getPending()
        Timber.i("Processing ${pending.size} pending uploads")
        pending.forEach { item ->
            dao.updateStatus(item.id, "uploading")
            val success = uploadFile(item)
            if (success) {
                dao.delete(item.id)
                File(item.filePath).delete()
                Timber.i("Uploaded and deleted: ${item.fileName}")
            } else {
                dao.incrementRetry(item.id)
                dao.updateStatus(item.id, "pending")
                Timber.w("Upload failed (retry ${item.retryCount + 1}): ${item.fileName}")
            }
        }
    }

    private suspend fun uploadFile(item: UploadQueueEntity): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = File(item.filePath)
                if (!file.exists()) {
                    Timber.w("File not found, removing from queue: ${item.filePath}")
                    dao.delete(item.id)
                    return@withContext false
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("deviceId", item.deviceId)
                    .addFormDataPart("fileType", item.fileType)
                    .addFormDataPart("recordTime", dateFormat.format(Date(item.recordTime)))
                    .apply {
                        item.latitude?.let { addFormDataPart("latitude", it.toString()) }
                        item.longitude?.let { addFormDataPart("longitude", it.toString()) }
                    }
                    .addFormDataPart(
                        "file",
                        file.name,
                        file.asRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("${settings.platformApiUrl}/api/device-file/upload")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Upload HTTP ${response.code}: ${item.fileName}")
                    }
                    response.isSuccessful
                }
            } catch (e: Exception) {
                Timber.e(e, "Upload exception: ${item.fileName}")
                false
            }
        }
}
