package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("lender_id") val lenderId: String,
    @SerialName("borrower_id") val borrowerId: String,
    @SerialName("principal_minor") val principalMinor: Long,
    @SerialName("currency") val currency: String,
    @SerialName("description") val description: String,
    @SerialName("status") val status: String,
    @SerialName("proposed_by") val proposedBy: String,
    @SerialName("lender_account") val lenderAccount: String,
    @SerialName("borrower_account") val borrowerAccount: String,
    @SerialName("repaid_minor") val repaidMinor: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("accepted_at") val acceptedAt: Long?,
    @SerialName("fully_repaid_at") val fullyRepaidAt: Long?,
    @SerialName("blockchain_hash") val blockchainHash: String?,
    @SerialName("blockchain_tx_hash") val blockchainTxHash: String?,
    @SerialName("blockchain_status") val blockchainStatus: String,
    @SerialName("sync_status") val syncStatus: String,
    @SerialName("version") val version: Int,
    @SerialName("supersedes") val supersedes: String?
)
