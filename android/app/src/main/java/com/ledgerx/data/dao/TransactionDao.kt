package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions") suspend fun all(): List<TransactionEntity>
    @Query("SELECT * FROM transactions") fun observe(): Flow<List<TransactionEntity>>
    @Upsert suspend fun put(rows: List<TransactionEntity>)
    @Upsert suspend fun put(row: TransactionEntity)
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun delete(id: String)
}
