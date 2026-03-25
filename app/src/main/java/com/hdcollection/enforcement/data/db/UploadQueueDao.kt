package com.hdcollection.enforcement.data.db

import androidx.room.*

@Dao
interface UploadQueueDao {
    @Insert
    suspend fun enqueue(item: UploadQueueEntity): Long

    @Query("SELECT * FROM upload_queue WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPending(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue ORDER BY createdAt DESC")
    suspend fun getAll(): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("UPDATE upload_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Int)

    @Query("DELETE FROM upload_queue WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = 'pending'")
    suspend fun pendingCount(): Int
}
