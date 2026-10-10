package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "monthly_summaries", indices = [Index(value = ["userId", "year", "month", "categoryId", "account"], unique = true)])
data class MonthlySummaryEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("year") val year: Int,
    @SerialName("month") val month: Int,
    @SerialName("category_id") val categoryId: String,
    @SerialName("account") val account: String,
    @SerialName("income_minor") val incomeMinor: Long,
    @SerialName("expense_minor") val expenseMinor: Long,
    @SerialName("transaction_count") val transactionCount: Int,
    @SerialName("generated_at") val generatedAt: Long,
    @SerialName("version") val version: Int
)
