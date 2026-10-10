package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileCacheDao {
    @Query("SELECT * FROM profiles_cache") suspend fun all(): List<ProfileCacheEntity>
    @Query("SELECT * FROM profiles_cache") fun observe(): Flow<List<ProfileCacheEntity>>
    @Upsert suspend fun put(rows: List<ProfileCacheEntity>)
    @Upsert suspend fun put(row: ProfileCacheEntity)
    @Query("DELETE FROM profiles_cache WHERE id = :id") suspend fun delete(id: String)
}
