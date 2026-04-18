package com.hdcollection.enforcement.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.logging.UserOpLogger
import timber.log.Timber

/**
 * 周期补传 UserOpLogger 本地队列。
 * 关键事件写入后会立即 flush；此 Worker 只负责兜底非关键事件 / 历史失败的补传。
 */
class UserOpLogUploadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val sent = UserOpLogger.flush()
            if (sent > 0) Timber.i("UserOpLogUploadWorker: 补传 $sent 条")
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "UserOpLogUploadWorker 异常")
            Result.retry()
        }
    }
}
