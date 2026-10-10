package com.ledgerx.ui

import android.content.Context
import com.ledgerx.data.*
import com.ledgerx.data.entities.*
import com.ledgerx.data.repository.*
import com.ledgerx.data.sync.*
import com.ledgerx.data.realtime.LoanRealtimeManager
import com.ledgerx.domain.Movement
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LedgerState(
    val owner: String="", val snapshot: LedgerSnapshot=LedgerSnapshot(),
    val legacy: List<Movement> = emptyList(), val queue: List<SyncQueueEntity> = emptyList(),
    val search: List<ProfileRow> = emptyList(), val ready: Boolean=false,
    val busy: Boolean=false, val message: String?=null
)
class LedgerController(private val context: Context,private val scope: CoroutineScope) {
    private val mutable=MutableStateFlow(LedgerState())
    val state=mutable.asStateFlow()
    private var repo: TransactionRepository?=null
    private var job: Job?=null
    private var realtime: Job?=null
    fun open(owner: String) {
        if(repo?.owner==owner) return
        close()
        val next=TransactionRepository(LedgerDatabase.create(context,owner),owner)
        repo=next; mutable.value=LedgerState(owner=owner)
        job=scope.launch {
            try {
                next.initialize(); next.compact()
                launch { ConnectivityObserver(context).connected.filter { it }.collect { SyncManager.enqueue(context,owner) } }
                SyncManager.schedule(context,owner)
                realtime=LoanRealtimeManager().start(scope,owner) { SyncManager.enqueue(context,owner) }
                combine(next.snapshot,next.db.syncQueueDao().observe()) { snapshot,queue -> snapshot to queue }.collect { (s,q) ->
                    if(repo===next) mutable.update { it.copy(snapshot=s,queue=q,legacy=next.legacy(),ready=true) }
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { mutable.update { it.copy(message="Could not open this ledger. Please restart the app.") } }
        }
    }
    fun close() {
        repo?.owner?.let { SyncManager.cancel(context,it) }
        job?.cancel(); realtime?.cancel(); repo=null; mutable.value=LedgerState()
    }
    private fun action(block: suspend (TransactionRepository)->Unit) {
        val target=repo?:return
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,message=null) }
        scope.launch {
            try {
                block(target)
                if(repo===target) { SyncManager.enqueue(context,target.owner); mutable.update { it.copy(message="Saved. Changes sync automatically when connected.") } }
            } catch(e: CancellationException) { throw e }
            catch(e: IllegalArgumentException) { if(repo===target) mutable.update { it.copy(message=e.message?:"Check the entered values.") } }
            catch(_: Exception) { if(repo===target) mutable.update { it.copy(message="Could not complete this action. Your saved records are safe.") } }
            finally { if(repo===target) mutable.update { it.copy(busy=false) } }
        }
    }
    fun add(type:String,amount:Long,account:String,destination:String?,category:String?,description:String) = action { it.add(type,amount,account,destination,category,description) }
    fun category(name:String,icon:String)=action { it.addCategory(name,icon) }
    fun loan(friend:String,amount:Long,borrow:Boolean,note:String,account:String)=action { LoanRepository(it).propose(friend,amount,borrow,note,account) }
    fun decideLoan(id:String,accept:Boolean,account:String)=action { LoanRepository(it).decide(id,accept,account) }
    fun event(loan:LoanEntity,amount:Long,correction:Boolean)=action { LoanRepository(it).event(loan,amount,correction) }
    fun decideEvent(id:String,accept:Boolean)=action { LoanRepository(it).decideEvent(id,accept) }
    fun requestFriend(id:String)=action { FriendRepository(it).request(id) }
    fun decideFriend(id:String,accept:Boolean)=action { FriendRepository(it).decide(id,accept) }
    fun search(username:String) {
        val target=repo?:return
        scope.launch {
            try {
                val rows=checkNotNull(SupabaseLedger.client).postgrest.from("profiles").select {
                    filter { eq("username",username.trim().removePrefix("@").lowercase()) }
                }.decodeList<ProfileRow>()
                if(repo===target) mutable.update { it.copy(search=rows.filter { r -> r.id!=target.owner },message=if(rows.isEmpty()) "No matching username." else null) }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(repo===target) mutable.update { it.copy(message="Friend search needs an internet connection.") } }
        }
    }
    fun importOffline()=action { it.importLegacy(LedgerRepository(LedgerDatabase.create(context)).records()) }
    fun retry()=action { r ->
        r.db.syncQueueDao().all().forEach { r.db.syncQueueDao().put(it.copy(attempts=0,lastAttemptAt=null)) }
    }
    fun sync()=action { LedgerRemote.sync(context,it.owner) }
}
