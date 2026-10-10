package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans") suspend fun all(): List<LoanEntity>
    @Query("SELECT * FROM loans") fun observe(): Flow<List<LoanEntity>>
    @Upsert suspend fun put(rows: List<LoanEntity>)
    @Upsert suspend fun put(row: LoanEntity)
    @Query("DELETE FROM loans WHERE id = :id") suspend fun delete(id: String)
}
