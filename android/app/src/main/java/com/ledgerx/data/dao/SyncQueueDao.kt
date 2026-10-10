package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAt, id") suspend fun all(): List<SyncQueueEntity>
    @Query("SELECT * FROM sync_queue ORDER BY createdAt, id") fun observe(): Flow<List<SyncQueueEntity>>
    @Upsert suspend fun put(rows: List<SyncQueueEntity>)
    @Upsert suspend fun put(row: SyncQueueEntity)
    @Query("DELETE FROM sync_queue WHERE id = :id") suspend fun delete(id: String)
}
