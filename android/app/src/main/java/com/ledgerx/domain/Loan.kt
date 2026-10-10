package com.ledgerx.domain

/**
 * A shared loan between two LedgerX users.
 *
 * Loans are identified by UUID-linked user IDs, not names.
 * Both parties must confirm before the debt becomes active.
 * Outstanding loans are never compacted into monthly summaries.
 */
data class Loan(
    val id: String,
    val lenderId: String,
    val borrowerId: String,
    val principalMinor: Long,
    val currency: String = "BDT",
    val description: String = "",
    val status: LoanStatus = LoanStatus.UNDER_REVIEW,
    val createdAt: Long = System.currentTimeMillis(),
    val acceptedAt: Long? = null,
    val fullyRepaidAt: Long? = null,
    val blockchainHash: String? = null,
    val blockchainTxHash: String? = null,
    val blockchainStatus: BlockchainStatus = BlockchainStatus.NOT_REQUIRED,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val version: Int = 1
) {
    /** True when this user created the loan (is the lender). */
    fun isLender(userId: String) = lenderId == userId

    /** The outstanding balance after subtracting confirmed repayments. */
    fun outstanding(repaidMinor: Long): Long =
        (principalMinor - repaidMinor).coerceAtLeast(0)
}

/** Lifecycle of a shared loan. */
enum class LoanStatus(val label: String) {
    /** Proposal sent, waiting for borrower's confirmation. */
    UNDER_REVIEW("Under Review"),
    /** Both parties confirmed; debt is active. */
    ACTIVE("Active"),
    /** Some repayment confirmed; balance remains. */
    PARTIALLY_REPAID("Partially Repaid"),
    /** Fully repaid; no outstanding balance. */
    REPAID("Repaid"),
    /** Borrower rejected the proposal. */
    REJECTED("Rejected"),
    /** Lender cancelled before confirmation. */
    CANCELLED("Cancelled"),
    /** Replaced by a correction (new version). */
    SUPERSEDED("Superseded")
}

/**
 * Blockchain anchoring is independent of the loan's operational status.
 * A Polygon testnet outage should never block the finance app.
 */
enum class BlockchainStatus {
    /** Loan doesn't need blockchain proof (e.g. small amount). */
    NOT_REQUIRED,
    /** Ready to submit but not yet sent. */
    WAITING,
    /** Transaction submitted, awaiting on-chain confirmation. */
    SUBMITTED,
    /** On-chain confirmation received; tx hash stored. */
    CONFIRMED,
    /** Submission or confirmation failed; will retry. */
    FAILED
}
