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
        SyncHealth.update(owner) { it.copy(running = true, error = null) }
        try {
            SupabaseLedger.initialize(context)
            val api = checkNotNull(SupabaseLedger.client)
            api.auth.awaitInitialization()
            fun checkOwner() = check(api.auth.currentUserOrNull()?.id == owner) { "Session changed." }
            checkOwner()
            val repo = TransactionRepository(LedgerDatabase.create(context, owner), owner)
            repo.initialize()
            suspend fun refresh() {
                checkOwner()
                val snapshot = LedgerJson.decodeFromString<LedgerSnapshot>(api.postgrest.rpc("ledger_snapshot").data)
                checkOwner()
                repo.merge(snapshot)
                reconcileDecisions(repo, snapshot)
            }
            // Decisions must use current server state, including changes made by the other person.
            refresh()
            var retry = false
            for (q in repo.db.syncQueueDao().all().sortedBy { it.createdAt }) {
                if (q.attempts >= 3) continue
                checkOwner()
                // A foreground tap may have replaced this decision since the list was read.
                if (repo.db.syncQueueDao().all().none { it.id == q.id }) continue
                try {
                    api.postgrest.rpc("ledger_command", buildJsonObject {
                        put("command_id", q.id); put("entity_type", q.entityType); put("entity_id", q.entityId)
                        put("action", q.action); put("payload", LedgerJson.parseToJsonElement(q.payload))
                    })
                    repo.db.syncQueueDao().delete(q.id)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val transient = isTransientSyncError(e)
                    repo.db.withTransaction {
                        // Do not resurrect a decision replaced while this request was running.
                        if (repo.db.syncQueueDao().all().any { it.id == q.id }) {
                            repo.db.syncQueueDao().put(q.copy(
                                attempts = if (transient) q.attempts else 3,
                                lastAttemptAt = System.currentTimeMillis(), lastError = syncIssue(e)
                            ))
                        }
                    }
                    if (transient) { retry = true; break }
                    // A rejected decision must not block unrelated rejections or records.
                }
            }
            refresh()
            val remaining = repo.db.syncQueueDao().all()
            SyncHealth.update(owner) { it.copy(lastSuccessAt = System.currentTimeMillis(), error = remaining.firstNotNullOfOrNull { q -> q.lastError }) }
            com.ledgerx.data.blockchain.ProofRepository(context, owner).retryPending()
            retry
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            SyncHealth.update(owner) { it.copy(error = syncIssue(e)) }
            throw e
        } finally {
            SyncHealth.update(owner) { it.copy(running = false) }
        }
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
