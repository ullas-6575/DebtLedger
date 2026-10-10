package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "loan_events")
data class LoanEventEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("loan_id") val loanId: String,
    @SerialName("type") val type: String,
    @SerialName("amount_minor") val amountMinor: Long?,
    @SerialName("proposed_by") val proposedBy: String,
    @SerialName("approved_by") val approvedBy: String?,
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("sync_status") val syncStatus: String
)
