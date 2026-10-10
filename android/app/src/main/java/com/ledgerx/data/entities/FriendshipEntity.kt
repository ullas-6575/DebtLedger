package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "friendships")
data class FriendshipEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("friend_id") val friendId: String,
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: Long
)
