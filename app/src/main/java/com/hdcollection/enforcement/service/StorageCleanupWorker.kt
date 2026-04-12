package com.hdcollection.enforcement.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hdcollection.enforcement.EnforcementApp
import timber.log.Timber
import java.io.File

/**
 * 定期清理过期本地媒体文件（录像 + 照片）。
 *
 * 保留天数从 `RemoteConfigManager.config.value.storageAutoCleanDays` 读取，默认 30。
 * 扫描 `filesDir/recordings` 和 `filesDir/photos`，按 `lastModified` 判断过期。
 *
 * 注册方式：[EnforcementApp] 启动时通过 `WorkManager` 注册为每日一次的 PeriodicWorkRequest。
 */
class StorageCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext as? EnforcementApp
            val days = app?.remoteConfigManager?.config?.value?.storageAutoCleanDays ?: 30
            if (days <= 0) {
                Timber.i("StorageCleanup 跳过: autoCleanDays=$days (<=0 表示不清理)")
                return Result.success()
            }

            val cutoffMs = System.currentTimeMillis() - days.toLong() * 24L * 3600L * 1000L
            val targets = listOf(
                File(applicationContext.filesDir, "recordings"),
                File(applicationContext.filesDir, "photos")
            )

            var deletedCount = 0
            var deletedBytes = 0L
            var keptCount = 0

            for (dir in targets) {
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    if (file.lastModified() < cutoffMs) {
                        val size = file.length()
                        if (file.delete()) {
                            deletedCount++
                            deletedBytes += size
                            Timber.d("StorageCleanup 删除过期文件: ${file.name} (${size / 1024}KB)")
                        } else {
                            Timber.w("StorageCleanup 删除失败: ${file.absolutePath}")
                        }
                    } else {
                        keptCount++
                    }
                }
            }

            Timber.i(
                "StorageCleanup 完成: 保留天数=$days, 删除 $deletedCount 个文件共 ${deletedBytes / 1024 / 1024}MB, 保留 $keptCount 个"
            )
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "StorageCleanup 失败")
            Result.retry()
        }
    }
}
