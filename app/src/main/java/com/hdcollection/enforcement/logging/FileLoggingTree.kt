package com.hdcollection.enforcement.logging

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileLoggingTree(private val logFile: File) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "V"
        }
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp [$level/${tag ?: "App"}] $message\n"
        try {
            logFile.appendText(line)
            t?.let { logFile.appendText("  Exception: ${it.message}\n") }
        } catch (e: Exception) {
            // 日志写入失败时静默处理，避免无限递归
        }
    }

    fun getLogFile(): File = logFile
}
