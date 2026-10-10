package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("type") val type: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("account") val account: String,
    @SerialName("destination") val destination: String?,
    @SerialName("category_id") val categoryId: String?,
    @SerialName("description") val description: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("sync_status") val syncStatus: String,
    @SerialName("summary_status") val summaryStatus: String,
    @SerialName("summary_id") val summaryId: String?,
    @SerialName("trashed_at") val trashedAt: Long?,
    @SerialName("delete_after") val deleteAfter: Long?,
    @SerialName("version") val version: Int,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)
