package com.hdcollection.enforcement.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Base64
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListSyncService(
    private val context: Context,
    private val settings: AppSettings,
    private val client: OkHttpClient
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    // 已同步过的文件（文件名→lastModified），避免重复上报缩略图
    private val syncedFiles = mutableMapOf<String, Long>()

    suspend fun syncFileList() = withContext(Dispatchers.IO) {
        val deviceId = settings.deviceId
        if (deviceId.isEmpty() || settings.platformApiUrl.isEmpty()) return@withContext

        val files = JSONArray()
        val allFileNames = JSONArray() // 设备上当前所有文件名

        // 扫描视频 — 只上报新增或修改的文件
        val recDir = File(context.filesDir, "recordings")
        recDir.listFiles()?.filter { it.extension in listOf("mp4", "3gp") }?.forEach { file ->
            allFileNames.put(file.name)
            val lastMod = syncedFiles[file.name]
            if (lastMod == null || lastMod != file.lastModified()) {
                val isNew = lastMod == null // 首次同步才带缩略图
                files.put(buildFileJson(file, "video", includeThumbnail = isNew))
                syncedFiles[file.name] = file.lastModified()
            }
        }

        // 扫描图片
        val photoDir = File(context.filesDir, "photos")
        photoDir.listFiles()?.filter { it.extension in listOf("jpg", "jpeg", "png") }?.forEach { file ->
            allFileNames.put(file.name)
            val lastMod = syncedFiles[file.name]
            if (lastMod == null || lastMod != file.lastModified()) {
                val isNew = lastMod == null
                files.put(buildFileJson(file, "image", includeThumbnail = isNew))
                syncedFiles[file.name] = file.lastModified()
            }
        }

        // 扫描音频 — 音频无缩略图，includeThumbnail 始终为 false
        val audioDir = File(context.filesDir, "audios")
        audioDir.listFiles()?.filter { it.extension in listOf("m4a", "aac", "mp3") }?.forEach { file ->
            allFileNames.put(file.name)
            val lastMod = syncedFiles[file.name]
            if (lastMod == null || lastMod != file.lastModified()) {
                files.put(buildFileJson(file, "audio", includeThumbnail = false))
                syncedFiles[file.name] = file.lastModified()
            }
        }

        // 清除 syncedFiles 中设备上已删除的文件缓存
        val currentNames = (0 until allFileNames.length()).map { allFileNames.getString(it) }.toSet()
        syncedFiles.keys.removeAll { it !in currentNames }

        if (files.length() == 0 && allFileNames.length() == 0) {
            Timber.d("FileListSync: 无文件，跳过")
            return@withContext
        }

        // 分批上报（每批最多 50 个，避免请求体过大）
        // 首批携带 allFileNames，后续批次不重复发送
        val batchSize = 50
        if (files.length() == 0) {
            // 无新增文件但仍需发送 allFileNames 通知服务端清除已删除的记录
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("files", JSONArray())
                put("allFileNames", allFileNames)
            }
            uploadBatch(body)
        } else {
            for (start in 0 until files.length() step batchSize) {
                val batch = JSONArray()
                for (j in start until minOf(start + batchSize, files.length())) {
                    batch.put(files.getJSONObject(j))
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("files", batch)
                    if (start == 0) put("allFileNames", allFileNames) // 仅首批携带
                }
                uploadBatch(body)
            }
        }

        Timber.i("FileListSync: 同步 ${files.length()} 个变更文件, 设备共 ${allFileNames.length()} 个文件")
    }

    private fun uploadBatch(body: JSONObject) {

        val request = Request.Builder()
            .url("${settings.platformApiUrl}/api/device-remote-file/sync")
            .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Timber.d("FileListSync batch: ${body.getJSONArray("files").length()} files, HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Timber.w(e, "FileListSync batch 失败")
        }
    }

    private fun buildFileJson(file: File, fileType: String, includeThumbnail: Boolean = true): JSONObject {
        val thumb = if (includeThumbnail) generateThumbnail(file, fileType) else null
        return JSONObject().apply {
            put("fileName", file.name)
            put("fileType", fileType)
            put("fileSize", file.length())
            put("recordTime", dateFormat.format(Date(file.lastModified())))
            if (fileType == "video" || fileType == "audio") put("duration", getMediaDuration(file))
            if (thumb != null) put("thumbnailBase64", thumb)
        }
    }

    @Suppress("DEPRECATION")
    private fun generateThumbnail(file: File, fileType: String): String? {
        return try {
            val bitmap = when (fileType) {
                "video" -> ThumbnailUtils.createVideoThumbnail(
                    file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND
                )
                "image" -> {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }
                else -> null
            } ?: return null

            val scaled = Bitmap.createScaledBitmap(bitmap, 160, 120, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "缩略图生成失败: ${file.name}")
            null
        }
    }

    private fun getMediaDuration(file: File): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            (duration?.toLongOrNull() ?: 0L).toInt() / 1000
        } catch (e: Exception) {
            Timber.w(e, "获取媒体时长失败: ${file.name}")
            0
        }
    }
}
