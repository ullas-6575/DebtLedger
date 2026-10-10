package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletionDao {
    @Query("SELECT * FROM deletions") suspend fun all(): List<DeletionEntity>
    @Query("SELECT * FROM deletions") fun observe(): Flow<List<DeletionEntity>>
    @Upsert suspend fun put(rows: List<DeletionEntity>)
    @Upsert suspend fun put(row: DeletionEntity)
    @Query("DELETE FROM deletions WHERE id = :id") suspend fun delete(id: String)
}
