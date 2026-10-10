package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("action") val action: String,
    @SerialName("payload") val payload: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("attempts") val attempts: Int,
    @SerialName("last_attempt_at") val lastAttemptAt: Long?
)
