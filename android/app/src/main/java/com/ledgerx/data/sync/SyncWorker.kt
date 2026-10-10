package com.ledgerx.data.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.ledgerx.data.*
import com.ledgerx.data.repository.*
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

object LedgerRemote {
    // Serialize workers and foreground pulls, including account/session changes.
    private val mutex=Mutex()
    suspend fun sync(context: Context,owner: String): Boolean = mutex.withLock {
        SupabaseLedger.initialize(context)
        val api=checkNotNull(SupabaseLedger.client)
        api.auth.awaitInitialization()
        check(api.auth.currentUserOrNull()?.id==owner) { "Session changed." }
        val repo=TransactionRepository(LedgerDatabase.create(context,owner),owner)
        repo.initialize()
        var retry=false
        for(q in repo.db.syncQueueDao().all().sortedBy { it.createdAt }) {
            if(q.attempts>=3) continue
            check(api.auth.currentUserOrNull()?.id==owner)
            try {
                api.postgrest.rpc("ledger_command",buildJsonObject {
                    put("command_id",q.id); put("entity_type",q.entityType); put("entity_id",q.entityId)
                    put("action",q.action); put("payload",LedgerJson.parseToJsonElement(q.payload))
                })
                repo.db.syncQueueDao().delete(q.id)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                repo.db.withTransaction {
                    repo.db.syncQueueDao().put(q.copy(attempts=q.attempts+1,lastAttemptAt=System.currentTimeMillis()))
                    repo.db.transactionDao().all().firstOrNull { it.id==q.entityId }?.let { repo.db.transactionDao().put(it.copy(syncStatus="FAILED")) }
                    repo.db.loanDao().all().firstOrNull { it.id==q.entityId }?.let { repo.db.loanDao().put(it.copy(syncStatus="FAILED")) }
                    repo.db.loanEventDao().all().firstOrNull { it.id==q.entityId }?.let { repo.db.loanEventDao().put(it.copy(syncStatus="FAILED")) }
                }
                retry=retry || q.attempts+1<3
                // Preserve causal order (categories before transactions, proposals before decisions).
                break
            }
        }
        check(api.auth.currentUserOrNull()?.id==owner)
        val response=api.postgrest.rpc("ledger_snapshot").data
        val snapshot=LedgerJson.decodeFromString<LedgerSnapshot>(response)
        check(api.auth.currentUserOrNull()?.id==owner)
        repo.merge(snapshot)
        com.ledgerx.data.blockchain.ProofRepository(context,owner).retryPending()
        retry
    }
}
class SyncWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val owner=inputData.getString("owner")?:return Result.failure()
        SupabaseLedger.initialize(applicationContext)
        val auth=SupabaseLedger.client?.auth?:return Result.failure()
        auth.awaitInitialization()
        if(auth.currentUserOrNull()?.id!=owner) return Result.success()
        return try { if(LedgerRemote.sync(applicationContext,owner)) Result.retry() else Result.success() }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { if(runAttemptCount<2) Result.retry() else Result.failure() }
    }
}
