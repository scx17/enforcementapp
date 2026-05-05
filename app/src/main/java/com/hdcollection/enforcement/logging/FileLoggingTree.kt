package com.hdcollection.enforcement.logging

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 滚动到 logDir/app_yyyyMMdd.log。
 * 之前传 logFile 是 App.onCreate 时算的固定路径，进程长期存活（如老 2 实测连续 5 天）会一直
 * 写到第一天的文件里，单文件涨到 600MB+。
 *
 * 跨天 / 单文件超 100MB（容灾，比如机器卡死时间不变）触发切到新文件名（带 _002、_003 后缀）。
 */
class FileLoggingTree(private val logDir: File) : Timber.Tree() {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    private val sizeLimitBytes = 100L * 1024 * 1024
    private var currentFile: File? = null
    private var currentDateStr: String = ""

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "V"
        }
        val now = Date()
        val timestamp = timeFormat.format(now)
        val line = "$timestamp [$level/${tag ?: "App"}] $message\n"
        try {
            val file = resolveCurrentFile(now)
            file.appendText(line)
            t?.let { file.appendText("  Exception: ${it.message}\n") }
        } catch (_: Exception) {
            // 静默防递归
        }
    }

    @Synchronized
    private fun resolveCurrentFile(now: Date): File {
        val dateStr = dateFormat.format(now)
        val cur = currentFile
        // 跨天：直接切今天的主文件
        if (cur == null || dateStr != currentDateStr) {
            if (!logDir.exists()) logDir.mkdirs()
            currentDateStr = dateStr
            currentFile = File(logDir, "app_$dateStr.log")
            return currentFile!!
        }
        // 单文件超 100MB 容灾：切到 _NNN 后缀，避免单文件无限增长
        if (cur.length() >= sizeLimitBytes) {
            currentFile = nextRollFile(dateStr)
        }
        return currentFile!!
    }

    private fun nextRollFile(dateStr: String): File {
        var n = 2
        while (true) {
            val f = File(logDir, "app_${dateStr}_%03d.log".format(n))
            if (!f.exists() || f.length() < sizeLimitBytes) return f
            n++
            if (n > 999) return f   // 兜底，不会发生
        }
    }

    fun getCurrentLogFile(): File? = currentFile
}
