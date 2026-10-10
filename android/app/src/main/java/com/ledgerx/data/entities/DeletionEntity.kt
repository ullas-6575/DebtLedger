package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "deletions")
data class DeletionEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("deleted_at") val deletedAt: Long
)
