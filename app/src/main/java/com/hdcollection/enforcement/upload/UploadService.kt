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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UploadService(
    private val dao: UploadQueueDao,
    private val settings: AppSettings,
    private val client: OkHttpClient,
    private val context: android.content.Context? = null
) {
    companion object {
        const val CHUNK_SIZE = 5 * 1024 * 1024L // 5MB per chunk
    }

    /** 检查当前是否在 WiFi 网络下 */
    private fun isOnWifi(): Boolean {
        val ctx = context ?: return true  // 无 context 时默认允许
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** 读取远程配置中的上传参数 */
    private fun remoteConfig(): com.hdcollection.enforcement.config.RemoteConfig? {
        val app = context?.applicationContext as? com.hdcollection.enforcement.EnforcementApp
        return app?.remoteConfigManager?.config?.value
    }

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

        val cfg = remoteConfig()
        val wifiOnly = cfg?.uploadWifiOnly ?: false
        val maxRetries = cfg?.uploadMaxRetries ?: 3

        if (wifiOnly && !isOnWifi()) {
            Timber.w("上传跳过: wifiOnly=true 且当前不在 WiFi 网络下（待网络恢复后再试）")
            return
        }

        reportStatusToPlatform(pending, statusCode = 0)

        pending.forEach { item ->
            // 超过最大重试次数则直接作废该条目
            if (item.retryCount >= maxRetries) {
                Timber.w("上传放弃: ${item.fileName} 已重试 ${item.retryCount} 次 >= maxRetries=$maxRetries")
                dao.updateStatus(item.id, "failed")
                reportStatusToPlatform(listOf(item), statusCode = 3)
                return@forEach
            }
            dao.updateStatus(item.id, "uploading")
            reportStatusToPlatform(listOf(item), statusCode = 1)
            val success = uploadFile(item)
            if (success) {
                reportStatusToPlatform(listOf(item), statusCode = 2)
                dao.updateStatus(item.id, "done")
                // 不删除本地文件 — 服务器拉取模式下文件保留在设备端
                Timber.i("Uploaded: ${item.fileName} (本地文件保留)")
            } else {
                dao.incrementRetry(item.id)
                dao.updateStatus(item.id, "pending")
                reportStatusToPlatform(listOf(item), statusCode = 3)
                Timber.w("Upload failed (retry ${item.retryCount + 1}/$maxRetries): ${item.fileName}")
            }
        }
    }

    private suspend fun reportStatusToPlatform(items: List<UploadQueueEntity>, statusCode: Int, uploadedSizeOverride: Long? = null) {
        withContext(Dispatchers.IO) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val jsonArray = items.joinToString(",", "[", "]") { item ->
                    val uploaded = uploadedSizeOverride ?: if (statusCode == 2) item.fileSize else 0L
                    """{"deviceId":"${item.deviceId}","fileName":"${item.fileName}","fileSize":${item.fileSize},"uploadedSize":$uploaded,"status":$statusCode,"fileType":"${item.fileType}","recordTime":"${dateFormat.format(Date(item.recordTime))}"}"""
                }
                val body = jsonArray.toByteArray().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${settings.platformApiUrl}/api/device-file/report-upload-status")
                    .post(body)
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Timber.w(e, "Report upload status failed")
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

                // 分片上传
                val totalSize = file.length()
                val totalChunks = ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
                Timber.i("Starting chunked upload: ${item.fileName}, ${totalSize / 1024}KB, $totalChunks chunks")

                // 1. 查询已上传的分片（断点续传）
                val uploadedChunks = queryUploadedChunks(item.deviceId, item.fileName)
                Timber.i("Already uploaded chunks: ${uploadedChunks.size}/$totalChunks")

                // 2. 逐片上传
                val raf = RandomAccessFile(file, "r")
                val buffer = ByteArray(CHUNK_SIZE.toInt())

                var uploadedBytes = 0L
                for (i in 0 until totalChunks) {
                    if (i in uploadedChunks) {
                        uploadedBytes += if (i == totalChunks - 1) totalSize - i * CHUNK_SIZE else CHUNK_SIZE
                        Timber.d("Skip chunk $i (already uploaded)")
                        continue
                    }

                    raf.seek(i * CHUNK_SIZE)
                    val bytesRead = raf.read(buffer)
                    if (bytesRead <= 0) break

                    val chunkData = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    val success = uploadChunk(item.deviceId, item.fileName, i, totalChunks, totalSize, chunkData)
                    if (!success) {
                        raf.close()
                        Timber.w("Chunk $i upload failed, aborting: ${item.fileName}")
                        return@withContext false
                    }

                    uploadedBytes += bytesRead

                    // 每 5 片上报一次进度（避免太频繁）
                    if ((i + 1) % 5 == 0 || i == totalChunks - 1) {
                        reportStatusToPlatform(listOf(item), statusCode = 1, uploadedSizeOverride = uploadedBytes)
                    }

                    val pct = (i + 1) * 100 / totalChunks
                    Timber.d("Upload progress: ${item.fileName} chunk ${i + 1}/$totalChunks ($pct%)")
                }
                raf.close()

                // 3. 通知服务端合并分片
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val mergeSuccess = mergeChunks(
                    deviceId = item.deviceId,
                    fileName = item.fileName,
                    totalChunks = totalChunks,
                    fileType = item.fileType,
                    recordTime = dateFormat.format(Date(item.recordTime)),
                    latitude = item.latitude,
                    longitude = item.longitude
                )

                if (mergeSuccess) {
                    Timber.i("Chunked upload complete: ${item.fileName}")
                } else {
                    Timber.w("Merge failed: ${item.fileName}")
                }
                mergeSuccess
            } catch (e: Exception) {
                Timber.e(e, "Upload exception: ${item.fileName}")
                false
            }
        }

    /** 查询已上传的分片索引 */
    private fun queryUploadedChunks(deviceId: String, fileName: String): Set<Int> {
        return try {
            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/device-file/upload-chunks?deviceId=$deviceId&fileName=$fileName")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptySet()
                val json = JSONObject(response.body?.string() ?: "{}")
                val arr = json.optJSONArray("chunks") ?: return emptySet()
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            }
        } catch (e: Exception) {
            Timber.w(e, "Query uploaded chunks failed")
            emptySet()
        }
    }

    /** 上传单个分片 */
    private fun uploadChunk(
        deviceId: String, fileName: String,
        chunkIndex: Int, totalChunks: Int, totalSize: Long,
        chunkData: ByteArray
    ): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart("fileName", fileName)
                .addFormDataPart("chunkIndex", chunkIndex.toString())
                .addFormDataPart("totalChunks", totalChunks.toString())
                .addFormDataPart("totalSize", totalSize.toString())
                .addFormDataPart("chunk", "chunk_$chunkIndex",
                    chunkData.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/device-file/upload-chunk")
                .post(body)
                .build()

            // 单片最多重试 3 次
            for (attempt in 1..3) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) return true
                        Timber.w("Chunk $chunkIndex HTTP ${response.code} (attempt $attempt)")
                    }
                } catch (e: Exception) {
                    Timber.w("Chunk $chunkIndex attempt $attempt failed: ${e.message}")
                    if (attempt == 3) return false
                    Thread.sleep(2000L * attempt) // 退避重试
                }
            }
            false
        } catch (e: Exception) {
            Timber.e(e, "uploadChunk exception: chunk $chunkIndex")
            false
        }
    }

    /** 通知服务端合并所有分片 */
    private fun mergeChunks(
        deviceId: String, fileName: String, totalChunks: Int,
        fileType: String, recordTime: String,
        latitude: Double?, longitude: Double?
    ): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("fileName", fileName)
                put("totalChunks", totalChunks)
                put("fileType", fileType)
                put("recordTime", recordTime)
                latitude?.let { put("latitude", it) }
                longitude?.let { put("longitude", it) }
            }
            val body = json.toString().toByteArray().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/device-file/merge-chunks")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(300) ?: ""
                    Timber.w("Merge HTTP ${response.code}: $errBody")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Timber.e(e, "mergeChunks exception")
            false
        }
    }
}
