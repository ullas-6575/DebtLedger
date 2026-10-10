package com.ledgerx.data.realtime

import com.ledgerx.data.SupabaseLedger
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** RLS filters database notifications; refresh the authoritative snapshot on change. */
class LoanRealtimeManager {
    fun start(scope: CoroutineScope,owner: String,onChange: () -> Unit): Job = scope.launch {
        val api=SupabaseLedger.client?:return@launch
        val channel=api.channel("ledger-$owner")
        try {
            listOf("loans","loan_events","friendships").forEach { name ->
                channel.postgresChangeFlow<PostgresAction>(schema="public") { table=name }
                    .onEach { onChange() }.launchIn(this)
            }
            channel.subscribe()
            awaitCancellation()
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { /* Periodic WorkManager pull remains available. */ }
        finally { withContext(NonCancellable) { runCatching { api.realtime.removeChannel(channel) } } }
    }
}
