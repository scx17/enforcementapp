package com.hdcollection.enforcement.upload

import android.content.Context
import com.hdcollection.enforcement.data.AppSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 直传 /api/snapshot/upload。
 * 远程拍照场景必须传 requestId，后端会用它通过 SignalR 回推 SnapshotResult 给前端。
 */
class SnapshotUploader(
    private val context: Context,
    private val settings: AppSettings
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 同步上传（调用方应在 IO 协程内调用）。
     * @param requestId 远程触发场景必传；手动拍照可传 null
     */
    fun upload(file: File, requestId: String? = null) {
        val baseUrl = settings.platformApiUrl.trimEnd('/')
        val deviceId = settings.deviceId
        if (baseUrl.isEmpty() || deviceId.isEmpty()) {
            Timber.w("SnapshotUploader 配置缺失: baseUrl=$baseUrl deviceId=$deviceId")
            return
        }
        if (!file.exists() || file.length() == 0L) {
            Timber.w("SnapshotUploader: 文件不存在或为空 ${file.absolutePath}")
            return
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("deviceId", deviceId)
            .addFormDataPart("channelId", deviceId)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        if (!requestId.isNullOrEmpty()) {
            builder.addFormDataPart("requestId", requestId)
        }
        val req = Request.Builder()
            .url("$baseUrl/api/snapshot/upload")
            .post(builder.build())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.w("snapshot upload failed: code=${resp.code} body=${resp.body?.string()}")
            } else {
                Timber.i("snapshot upload OK: code=${resp.code} requestId=$requestId file=${file.name}")
            }
        }
    }
}
