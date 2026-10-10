package com.ledgerx.data.repository

import androidx.room.withTransaction
import com.ledgerx.data.entities.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

class LoanRepository(private val repo: TransactionRepository) {
    suspend fun propose(friend: String, amount: Long, borrowing: Boolean, description: String, account: String) = repo.db.withTransaction {
        require(amount in 1..100_000_000_000L && description.length<=300)
        require(repo.db.friendshipDao().all().any { it.status=="ACCEPTED" && setOf(it.userId,it.friendId)==setOf(repo.owner,friend) }) { "Select an accepted friend." }
        val l=LoanEntity(UUID.randomUUID().toString(),if(borrowing) friend else repo.owner,if(borrowing) repo.owner else friend,amount,"BDT",description,"LOCAL_PENDING",repo.owner,if(borrowing) "CASH" else account,if(borrowing) account else "CASH",0,System.currentTimeMillis(),null,null,null,null,"NOT_REQUIRED","PENDING",1,null)
        repo.db.loanDao().put(l); repo.queue("LOAN",l.id,"PROPOSE",LedgerJson.encodeToString(l))
    }
    suspend fun decide(id: String, accept: Boolean, account: String) = repo.db.withTransaction {
        val loan = requireNotNull(repo.db.loanDao().all().find { it.id == id }) { "Loan no longer available." }
        require(loan.status == "UNDER_REVIEW" && loan.proposedBy != repo.owner) { "This loan is no longer awaiting your decision." }
        repo.queue("LOAN",id,if(accept) "ACCEPT" else "REJECT",buildJsonObject { put("account",account) }.toString())
    }
    suspend fun event(loan: LoanEntity, amount: Long, correction: Boolean) = repo.db.withTransaction {
        require(loan.status in setOf("ACTIVE","PARTIALLY_REPAID")) { "This loan is not active." }
        require(amount in 1..100_000_000_000L)
        require(if(correction) amount>=loan.repaidMinor else amount<=loan.principalMinor-loan.repaidMinor) { "Amount exceeds the outstanding loan or is below repayments." }
        val e=LoanEventEntity(UUID.randomUUID().toString(),loan.id,if(correction) "CORRECTION" else "REPAYMENT",amount,repo.owner,null,"LOCAL_PENDING",System.currentTimeMillis(),"PENDING")
        repo.db.loanEventDao().put(e); repo.queue("LOAN_EVENT",e.id,"PROPOSE",LedgerJson.encodeToString(e))
    }
    suspend fun decideEvent(id: String, accept: Boolean) = repo.db.withTransaction {
        val event = requireNotNull(repo.db.loanEventDao().all().find { it.id == id }) { "Proposal no longer available." }
        val loan = requireNotNull(repo.db.loanDao().all().find { it.id == event.loanId })
        require(event.status == "PENDING" && event.proposedBy != repo.owner) { "This proposal is no longer awaiting your decision." }
        require(loan.status in setOf("ACTIVE", "PARTIALLY_REPAID")) { "This loan has already been settled or replaced." }
        if (accept) {
            if (event.type == "REPAYMENT") require((event.amountMinor ?: 0) <= loan.principalMinor - loan.repaidMinor) { "Repayment exceeds the remaining balance. Reject this proposal and request the correct amount." }
            if (event.type == "CORRECTION") require((event.amountMinor ?: 0) >= loan.repaidMinor) { "The corrected principal cannot be below confirmed repayments." }
        }
        repo.queue("LOAN_EVENT", id, if (accept) "ACCEPT" else "REJECT", "{}")
    }
}
