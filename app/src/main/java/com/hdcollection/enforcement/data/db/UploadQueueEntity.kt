package com.hdcollection.enforcement.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_queue")
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val deviceId: String,
    val fileType: String,       // "video" | "image" | "log"
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val latitude: Double?,
    val longitude: Double?,
    val recordTime: Long,        // epoch millis
    val status: String = "pending",   // "pending" | "uploading" | "done" | "failed"
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
