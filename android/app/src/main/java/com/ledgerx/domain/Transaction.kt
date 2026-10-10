package com.ledgerx.domain

/**
 * Domain model for personal transactions (income, expenses, transfers).
 *
 * Loans are tracked separately in [Loan]. This type covers everyday
 * financial movements that participate in monthly compaction.
 */
data class Transaction(
    val id: String,
    val userId: String,
    val type: TransactionType,
    val amountMinor: Long,
    val account: String,
    val destination: String? = null,
    val categoryId: String? = null,
    val description: String = "",
    val occurredAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val summaryStatus: SummaryStatus = SummaryStatus.ACTIVE,
    val summaryId: String? = null,
    val trashedAt: Long? = null,
    val deleteAfter: Long? = null,
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class TransactionType(val label: String) {
    INCOME("Income"),
    EXPENSE("Expense"),
    TRANSFER("Transfer")
}

/** Sync status for offline-first records. */
enum class SyncStatus {
    /** Saved locally, not yet pushed to Supabase. */
    PENDING,
    /** Successfully synchronized with Supabase. */
    SYNCED,
    /** Push attempt failed; will retry. */
    FAILED
}

/** Whether a transaction is visible in the normal ledger or has been compacted. */
enum class SummaryStatus {
    /** Visible in the current ledger view. */
    ACTIVE,
    /** Replaced by a monthly summary in the UI; retained in Trash for 30 days. */
    TRASHED
}
