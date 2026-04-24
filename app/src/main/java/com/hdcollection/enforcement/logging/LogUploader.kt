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
    private val client: OkHttpClient,
    private val logDir: File
) {
    companion object {
        const val RETENTION_DAYS = 7
    }

    /** 上传单个日志文件 */
    suspend fun upload(logFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!logFile.exists() || logFile.length() == 0L) return@withContext false
        if (settings.platformApiUrl.isEmpty() || settings.deviceId.isEmpty()) {
            Timber.w("LogUploader: platformApiUrl or deviceId not configured, skipping")
            return@withContext false
        }
        try {
            // 从文件名提取日期 app_yyyyMMdd.log → yyyy-MM-dd
            val datePart = logFile.nameWithoutExtension.removePrefix("app_")
            val logDate = if (datePart.length == 8) {
                "${datePart.substring(0,4)}-${datePart.substring(4,6)}-${datePart.substring(6,8)}"
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            }

            Timber.i("LogUploader: 开始上传 ${logFile.name} (${logFile.length() / 1024}KB), date=$logDate")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", settings.deviceId)
                .addFormDataPart("logDate", logDate)
                .addFormDataPart("file", logFile.name, logFile.asRequestBody("text/plain".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("${settings.platformApiUrl}/api/device-log/upload")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful
                Timber.i("LogUploader: ${logFile.name} 上传${if (ok) "成功" else "失败(${response.code})"}")
                ok
            }
        } catch (e: Exception) {
            Timber.e(e, "LogUploader: ${logFile.name} 上传异常")
            false
        }
    }

    /** 上传所有未上传的历史日志（排除当天正在写入的文件） */
    suspend fun uploadPendingLogs() = withContext(Dispatchers.IO) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val logFiles = logDir.listFiles { f -> f.name.startsWith("app_") && f.name.endsWith(".log") && !f.name.contains(today) }
            ?.sortedBy { it.name } ?: return@withContext

        Timber.i("LogUploader: 发现 ${logFiles.size} 个历史日志文件待上传")
        for (file in logFiles) {
            upload(file)
        }
    }

    /** 管理端下发 PullLog 命令时调用：上传所有日志（含当天），用于远程排障。 */
    suspend fun uploadAllIncludingToday() = withContext(Dispatchers.IO) {
        val logFiles = logDir.listFiles { f -> f.name.startsWith("app_") && f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: return@withContext
        Timber.i("LogUploader: PullLog 触发，上传 ${logFiles.size} 个日志文件（含当天）")
        for (file in logFiles) {
            upload(file)
        }
    }

    /** 清理超过保留天数的旧日志文件 */
    fun cleanOldLogs() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS)
        val cutoffDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)

        val oldFiles = logDir.listFiles { f ->
            f.name.startsWith("app_") && f.name.endsWith(".log") &&
                f.nameWithoutExtension.removePrefix("app_") < cutoffDate
        } ?: return

        for (file in oldFiles) {
            Timber.d("LogUploader: 清理过期日志 ${file.name}")
            file.delete()
        }
        if (oldFiles.isNotEmpty()) {
            Timber.i("LogUploader: 清理了 ${oldFiles.size} 个过期日志文件（>${RETENTION_DAYS}天）")
        }
    }
}
