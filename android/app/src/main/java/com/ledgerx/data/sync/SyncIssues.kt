package com.ledgerx.data.sync

import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

data class SyncProgress(val running: Boolean = false, val lastSuccessAt: Long? = null, val error: String? = null)

object SyncHealth {
    private val mutable = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    val states = mutable.asStateFlow()
    fun update(owner: String, change: (SyncProgress) -> SyncProgress) {
        mutable.update { it + (owner to change(it[owner] ?: SyncProgress())) }
    }
}

/** Never display exception.message: SDK exceptions can contain request headers/tokens. */
fun syncIssue(error: Exception): String = when (error) {
    is RestException -> serverSyncIssue(error.statusCode, error.error)
    is IOException -> "Connection interrupted. Your changes are saved and will retry when connected."
    else -> "Sync could not finish. Your changes are saved; retry shortly."
}

fun serverSyncIssue(status: Int, error: String): String = when {
    error.contains("Repayment exceeds", true) || error.contains("Invalid amount", true) ->
        "This repayment exceeds the remaining loan balance. Reject this proposal and request the correct amount."
    error.contains("Correction below", true) -> "The corrected principal cannot be below repayments already confirmed."
    error.contains("Insufficient borrower", true) -> "The borrower has insufficient funds in the loan's repayment account."
    error.contains("Insufficient lender", true) -> "The lender has insufficient funds in the selected account."
    error.contains("Insufficient", true) -> "This change exceeds the available account balance. Review your latest transactions."
    error.contains("Invalid event decision", true) || error.contains("counterparty", true) ->
        "This proposal has changed or can only be decided by the other participant. Refresh the loan."
    error.contains("friendship", true) -> "An accepted friendship is required. Check the friend request first."
    error.contains("collision", true) -> "The server has different data for this record. Your local change has been kept for review."
    status == 401 -> "Your session needs refreshing. Reconnect, then sign in again if this continues."
    status == 403 -> "The server denied this change. Check account permissions before retrying."
    status == 404 -> "The ledger API is missing. Apply the project's database migrations, then retry."
    status == 429 -> "The server is busy. Sync will retry automatically."
    status >= 500 -> "The server is temporarily unavailable. Sync will retry automatically."
    else -> "The server rejected this change (HTTP $status). The record is saved locally for review."
}

fun isTransientSyncError(error: Exception) = error is IOException ||
    (error is RestException && (error.statusCode == 429 || error.statusCode >= 500 || error.statusCode == 401))
