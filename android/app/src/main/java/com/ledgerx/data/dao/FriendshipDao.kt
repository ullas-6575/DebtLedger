package com.ledgerx.data.dao

import androidx.room.*
import com.ledgerx.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FriendshipDao {
    @Query("SELECT * FROM friendships") suspend fun all(): List<FriendshipEntity>
    @Query("SELECT * FROM friendships") fun observe(): Flow<List<FriendshipEntity>>
    @Upsert suspend fun put(rows: List<FriendshipEntity>)
    @Upsert suspend fun put(row: FriendshipEntity)
    @Query("DELETE FROM friendships WHERE id = :id") suspend fun delete(id: String)
}
