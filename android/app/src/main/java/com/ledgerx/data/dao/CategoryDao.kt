package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories") suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM categories") fun observe(): Flow<List<CategoryEntity>>
    @Upsert suspend fun put(rows: List<CategoryEntity>)
    @Upsert suspend fun put(row: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: String)
}
