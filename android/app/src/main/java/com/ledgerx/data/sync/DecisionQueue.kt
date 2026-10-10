package com.ledgerx.data.sync

import androidx.room.withTransaction
import com.ledgerx.data.entities.SyncQueueEntity
import com.ledgerx.data.repository.LedgerSnapshot
import com.ledgerx.data.repository.TransactionRepository

/** A tap represents one decision. Later unsent decisions replace earlier taps on that proposal. */
fun obsoleteDecisions(queue: List<SyncQueueEntity>, remote: LedgerSnapshot): Set<String> {
    val decisions = queue.filter { it.action in setOf("ACCEPT", "REJECT") }
    val obsolete = mutableSetOf<String>()
    decisions.groupBy { it.entityType to it.entityId }.values.forEach { group ->
        val ordered = group.sortedWith(compareBy<SyncQueueEntity> { it.createdAt }.thenBy { it.id })
        ordered.dropLast(1).forEach { obsolete += it.id }
        val latest = ordered.last()
        val resolved = when (latest.entityType) {
            "LOAN" -> remote.loans.find { it.id == latest.entityId }?.let { it.status != "UNDER_REVIEW" } ?: false
            "LOAN_EVENT" -> remote.events.find { it.id == latest.entityId }?.let { event ->
                event.status != "PENDING" || remote.loans.find { it.id == event.loanId }?.status in setOf("SUPERSEDED", "REPAID", "REJECTED", "CANCELLED")
            } ?: false
            "FRIENDSHIP" -> remote.friendships.find { it.id == latest.entityId }?.let { it.status != "PENDING" } ?: false
            else -> false
        }
        if (resolved) obsolete += latest.id
    }
    return obsolete
}

suspend fun reconcileDecisions(repo: TransactionRepository, remote: LedgerSnapshot) = repo.db.withTransaction {
    obsoleteDecisions(repo.db.syncQueueDao().all(), remote).forEach { repo.db.syncQueueDao().delete(it) }
}
