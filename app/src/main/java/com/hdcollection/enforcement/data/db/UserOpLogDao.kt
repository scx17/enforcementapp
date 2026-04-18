package com.hdcollection.enforcement.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UserOpLogDao {
    @Insert
    suspend fun insert(entity: UserOpLogEntity): Long

    @Query("SELECT * FROM user_oplog_queue ORDER BY id ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<UserOpLogEntity>

    @Query("SELECT COUNT(*) FROM user_oplog_queue")
    suspend fun count(): Int

    @Query("DELETE FROM user_oplog_queue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE user_oplog_queue SET retryCount = retryCount + 1 WHERE id IN (:ids)")
    suspend fun incrementRetry(ids: List<Long>)
}
