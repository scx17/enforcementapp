package com.hdcollection.enforcement.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 执法仪端用户操作审计日志本地队列。
 * 写入后根据 critical 决定是否立即 flush，失败保留并 retryCount++ 由 WorkManager 补传。
 * 成功上报后物理删除。
 */
@Entity(tableName = "user_oplog_queue")
data class UserOpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationType: String,          // Login / StartRecording / TakePhoto / SOS / PTTAnswer / ...
    val description: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val clientTime: Long,               // epoch ms，离线补传时才能排序
    val networkType: String? = null,    // wifi / 4g / offline
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val appVersion: String? = null,
    val extraData: String? = null,      // JSON 扩展
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
