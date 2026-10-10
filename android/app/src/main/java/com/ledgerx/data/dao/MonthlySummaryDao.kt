package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlySummaryDao {
    @Query("SELECT * FROM monthly_summaries") suspend fun all(): List<MonthlySummaryEntity>
    @Query("SELECT * FROM monthly_summaries") fun observe(): Flow<List<MonthlySummaryEntity>>
    @Upsert suspend fun put(rows: List<MonthlySummaryEntity>)
    @Upsert suspend fun put(row: MonthlySummaryEntity)
    @Query("DELETE FROM monthly_summaries WHERE id = :id") suspend fun delete(id: String)
}
