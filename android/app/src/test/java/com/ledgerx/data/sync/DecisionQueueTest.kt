package com.ledgerx.data.sync

import com.ledgerx.data.entities.*
import com.ledgerx.data.repository.LedgerSnapshot
import org.junit.Assert.*
import org.junit.Test

class DecisionQueueTest {
    private fun decision(id: String, action: String, time: Long, entity: String = "event") =
        SyncQueueEntity(id, "LOAN_EVENT", entity, action, "{}", time, 0, null)
    private fun event(status: String) = LoanEventEntity("event", "loan", "REPAYMENT", 100, "other", null, status, 1, "SYNCED")

    @Test fun latestUnsentDecisionWinsWithoutDroppingOtherRecords() {
        val queue = listOf(decision("accept1", "ACCEPT", 1), decision("accept2", "ACCEPT", 2), decision("reject", "REJECT", 3),
            decision("other", "ACCEPT", 4, "other-event"), decision("record", "INSERT", 5))
        assertEquals(setOf("accept1", "accept2"), obsoleteDecisions(queue, LedgerSnapshot(events = listOf(event("PENDING")))))
    }
    @Test fun alreadyConfirmedDecisionsAreRemovedAfterLostResponse() {
        assertEquals(setOf("accept", "reject"), obsoleteDecisions(listOf(decision("accept", "ACCEPT", 1), decision("reject", "REJECT", 2)), LedgerSnapshot(events = listOf(event("CONFIRMED")))))
    }
    @Test fun missingRemoteProposalDoesNotDiscardOfflineWork() {
        assertTrue(obsoleteDecisions(listOf(decision("accept", "ACCEPT", 1)), LedgerSnapshot()).isEmpty())
    }
    @Test fun connectionAndServerErrorsDoNotExposeSensitiveMessages() {
        val secret = "Authorization: Bearer secret-token"
        assertFalse(syncIssue(java.io.IOException(secret)).contains(secret))
        assertFalse(serverSyncIssue(400, secret).contains(secret))
        assertTrue(serverSyncIssue(400, "Repayment exceeds balance").contains("remaining loan balance"))
        assertTrue(serverSyncIssue(404, "missing").contains("migrations"))
        assertTrue(isTransientSyncError(java.io.IOException()))
        assertFalse(isTransientSyncError(IllegalArgumentException()))
    }
}
