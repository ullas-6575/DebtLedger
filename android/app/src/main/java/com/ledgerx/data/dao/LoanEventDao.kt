package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanEventDao {
    @Query("SELECT * FROM loan_events") suspend fun all(): List<LoanEventEntity>
    @Query("SELECT * FROM loan_events") fun observe(): Flow<List<LoanEventEntity>>
    @Upsert suspend fun put(rows: List<LoanEventEntity>)
    @Upsert suspend fun put(row: LoanEventEntity)
    @Query("DELETE FROM loan_events WHERE id = :id") suspend fun delete(id: String)
}
