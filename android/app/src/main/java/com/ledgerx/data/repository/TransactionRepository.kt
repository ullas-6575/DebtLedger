package com.ledgerx.data.repository

import androidx.room.withTransaction
import com.ledgerx.data.*
import com.ledgerx.data.entities.*
import com.ledgerx.domain.*
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.time.*
import java.util.UUID

val LedgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
val LedgerZone: ZoneId = ZoneId.of("Asia/Dhaka")
fun categoryKey(owner: String, slug: String) = "$owner:$slug"
fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

@Serializable
data class LedgerSnapshot(
    val transactions: List<TransactionEntity> = emptyList(),
    val summaries: List<MonthlySummaryEntity> = emptyList(),
    val loans: List<LoanEntity> = emptyList(),
    val events: List<LoanEventEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val friendships: List<FriendshipEntity> = emptyList(),
    val profiles: List<ProfileCacheEntity> = emptyList(),
    val deletions: List<DeletionEntity> = emptyList(),
    @SerialName("legacy_movements") val legacyMovements: List<CloudMovement> = emptyList()
)

class TransactionRepository(val db: LedgerDatabase, val owner: String) {
    val snapshot = combine(
        combine(db.transactionDao().observe(), db.monthlySummaryDao().observe(), db.loanDao().observe(), db.loanEventDao().observe()) { t, s, l, e -> LedgerSnapshot(t,s,l,e) },
        combine(db.categoryDao().observe(), db.friendshipDao().observe(), db.profileCacheDao().observe(), db.deletionDao().observe()) { c,f,p,d -> LedgerSnapshot(categories=c,friendships=f,profiles=p,deletions=d) }
    ) { a,b -> a.copy(categories=b.categories,friendships=b.friendships,profiles=b.profiles,deletions=b.deletions) }

    suspend fun initialize() = db.withTransaction {
        val firstOpen = db.categoryDao().all().isEmpty()
        if (firstOpen) DefaultCategories.all.forEach { seed ->
            val c = CategoryEntity(categoryKey(owner,seed.id),owner,seed.name,seed.icon,true,seed.sortOrder)
            db.categoryDao().put(c)
            queue("CATEGORY", c.id, "INSERT", LedgerJson.encodeToString(c))
        }
        // Names in v1 are not authenticated identities. Keep legacy loan movements private.
        val ids = db.transactionDao().all().map { it.id }.toSet() + db.deletionDao().all().map { it.id }
        db.movements().all().filter { it.kind in setOf("INCOME","EXPENSE","TRANSFER") && it.id !in ids }.forEach { m ->
            val slug = when(m.category) { "EAT" -> "eating"; "PAYMENT" -> "payment"; else -> "other" }
            val t = TransactionEntity(m.id,owner,m.kind,m.amount,m.account,m.destination,
                if(m.kind=="TRANSFER") null else categoryKey(owner,slug),m.note,m.occurredAt,
                "PENDING","ACTIVE",null,null,null,1,m.occurredAt,m.occurredAt)
            db.transactionDao().put(t)

        }
        if(firstOpen) {
            val originals=db.movements().all().map { CloudMovement.from(it.model()) }
            if(originals.isNotEmpty()) queue("LEGACY_IMPORT",UUID.randomUUID().toString(),"INSERT",LedgerJson.encodeToString(originals))
        }
    }

    suspend fun importLegacy(records: List<Movement>) = db.withTransaction {
        val existing=db.movements().all().map { it.id }.toSet()
        db.movements().merge(records.filter { it.id !in existing }.map(MovementEntity::from))
        // Initialize converts personal records without changing the original history.
        initialize()
        if(records.isNotEmpty()) queue("LEGACY_IMPORT",UUID.randomUUID().toString(),"INSERT",LedgerJson.encodeToString(records.map(CloudMovement::from)))
    }

    suspend fun current() = LedgerSnapshot(db.transactionDao().all(),db.monthlySummaryDao().all(),db.loanDao().all(),db.loanEventDao().all(),db.categoryDao().all(),db.friendshipDao().all(),db.profileCacheDao().all(),db.deletionDao().all())

    suspend fun add(type: String, amount: Long, account: String, destination: String?, category: String?, description: String) = db.withTransaction {
        require(type in setOf("INCOME","EXPENSE","TRANSFER"))
        require(amount in 1..100_000_000_000L) { "Enter a valid amount." }
        require(description.length <= 300) { "Description must be at most 300 characters." }
        require(account in Account.entries.map { it.name })
        require(if(type=="TRANSFER") destination != account && destination in Account.entries.map { it.name } else destination==null)
        require(type=="TRANSFER" || db.categoryDao().all().any { it.id==category }) { "Choose a category." }
        if(type!="INCOME") require(balances(current(), legacy(), owner).getValue(account)>=amount) { "Insufficient account balance." }
        val now=System.currentTimeMillis()
        val row=TransactionEntity(UUID.randomUUID().toString(),owner,type,amount,account,destination,category,description.trim(),now,"PENDING","ACTIVE",null,null,null,1,now,now)
        db.transactionDao().put(row)
        queue("TRANSACTION",row.id,"INSERT",LedgerJson.encodeToString(row))
    }
    suspend fun legacy(): List<Movement> = db.movements().all().filter { it.kind !in setOf("INCOME","EXPENSE","TRANSFER") }.map { it.model() }

    suspend fun addCategory(name: String, icon: String) = db.withTransaction {
        require(name.trim().length in 1..40 && icon.length in 1..16)
        val c=CategoryEntity(UUID.randomUUID().toString(),owner,name.trim(),icon,false,db.categoryDao().all().size)
        db.categoryDao().put(c); queue("CATEGORY",c.id,"INSERT",LedgerJson.encodeToString(c))
    }
    suspend fun queue(type: String,id: String,action: String,payload: String) {
        val createdAt=maxOf(System.currentTimeMillis(),(db.syncQueueDao().all().maxOfOrNull { it.createdAt } ?: 0L)+1)
        db.syncQueueDao().put(SyncQueueEntity(UUID.randomUUID().toString(),type,id,action,payload,createdAt,0,null))
    }

    suspend fun compact(now: Long = System.currentTimeMillis()) = db.withTransaction {
        val cutoff=Instant.ofEpochMilli(now).atZone(LedgerZone).withDayOfMonth(1).toLocalDate().atStartOfDay(LedgerZone).toInstant().toEpochMilli()
        val eligible=db.transactionDao().all().filter { it.summaryStatus=="ACTIVE" && it.type!="TRANSFER" && it.occurredAt<cutoff }
        eligible.groupBy { t ->
            val d=Instant.ofEpochMilli(t.occurredAt).atZone(LedgerZone)
            listOf(d.year.toString(),d.monthValue.toString(),t.categoryId?:categoryKey(owner,"other"),t.account)
        }.forEach { (key,rows) ->
            val existing=db.monthlySummaryDao().all().firstOrNull { it.year==key[0].toInt() && it.month==key[1].toInt() && it.categoryId==key[2] && it.account==key[3] }
            val s=existing?:MonthlySummaryEntity(stableId("$owner:${key.joinToString(":")}"),owner,key[0].toInt(),key[1].toInt(),key[2],key[3],0,0,0,now,0)
            db.monthlySummaryDao().put(s.copy(incomeMinor=Math.addExact(s.incomeMinor,rows.filter { it.type=="INCOME" }.sumOf { it.amountMinor }),expenseMinor=Math.addExact(s.expenseMinor,rows.filter { it.type=="EXPENSE" }.sumOf { it.amountMinor }),transactionCount=s.transactionCount+rows.size,version=s.version+1))
            rows.forEach { db.transactionDao().put(it.copy(syncStatus="PENDING",summaryStatus="TRASHED",summaryId=s.id,trashedAt=now,deleteAfter=now+TRASH_RETENTION_MS)) }
        }
        // SYNCED Trash rows and summaries have been acknowledged together by the server.
        db.transactionDao().all().filter { it.summaryStatus=="TRASHED" && it.syncStatus=="SYNCED" && (it.deleteAfter ?: Long.MAX_VALUE)<=now }.forEach {
            db.deletionDao().put(DeletionEntity(it.id,owner,now))
        }
        // Never delete unacknowledged offline data.
        val deleted=db.deletionDao().all().map { it.id }.toSet()
        deleted.forEach { id -> db.transactionDao().delete(id); db.movements().delete(id) }
        db.syncQueueDao().all().forEach { q ->
            if(q.entityType=="TRANSACTION" && q.entityId in deleted) db.syncQueueDao().delete(q.id)
            if(q.entityType=="LEGACY_IMPORT") {
                val original=LedgerJson.decodeFromString<List<CloudMovement>>(q.payload)
                val retained=original.filter { it.id !in deleted }
                if(retained.isEmpty()) db.syncQueueDao().delete(q.id)
                else if(retained.size!=original.size) db.syncQueueDao().put(q.copy(payload=LedgerJson.encodeToString(retained)))
            }
        }
    }

    suspend fun merge(remote: LedgerSnapshot) = db.withTransaction {
        require(remote.transactions.all { it.userId==owner } && remote.summaries.all { it.userId==owner } && remote.categories.all { it.userId==owner } && remote.deletions.all { it.userId==owner })
        require(remote.legacyMovements.all { it.ownerId==owner })
        db.movements().merge(remote.legacyMovements.map { MovementEntity.from(it.model()) })
        require(remote.loans.all { it.lenderId==owner || it.borrowerId==owner })
        val queued=db.syncQueueDao().all().map { it.entityId }.toSet()
        val remoteIds=remote.transactions.map { it.id }.toSet()
        val pending=db.transactionDao().all().filter { (it.id in queued || it.syncStatus!="SYNCED") && it.id !in remoteIds }
        db.monthlySummaryDao().all().forEach { db.monthlySummaryDao().delete(it.id) }
        db.monthlySummaryDao().put(remote.summaries)
        db.transactionDao().put(remote.transactions.map { it.copy(syncStatus="SYNCED") })
        db.transactionDao().put(pending.map { it.copy(summaryStatus="ACTIVE",summaryId=null,trashedAt=null,deleteAfter=null) })
        val localLoans=db.loanDao().all().associateBy { it.id }
        db.loanDao().put(remote.loans.map { row ->
            val proof=localLoans[row.id]?.takeIf { com.ledgerx.data.blockchain.sameAgreement(it,row) && it.blockchainHash!=null }
            if(proof==null) row.copy(syncStatus="SYNCED") else row.copy(syncStatus="SYNCED",blockchainHash=proof.blockchainHash,blockchainTxHash=proof.blockchainTxHash,blockchainStatus=proof.blockchainStatus)
        })
        db.loanEventDao().put(remote.events.map { it.copy(syncStatus="SYNCED") })
        db.categoryDao().put(remote.categories); db.friendshipDao().put(remote.friendships); db.profileCacheDao().put(remote.profiles)
        db.deletionDao().put(remote.deletions)
        db.deletionDao().all().forEach { db.transactionDao().delete(it.id); db.movements().delete(it.id) }
        compact()
    }
}

fun balances(s: LedgerSnapshot, legacy: List<Movement> = emptyList(), owner: String = ""): Map<String,Long> {
    val result=Account.entries.associate { it.name to 0L }.toMutableMap()
    fun add(account: String,amount: Long) { result[account]=Math.addExact(result.getValue(account),amount) }
    Ledger.summarize(legacy).accounts.forEach { (a,v) -> add(a.name,v) }
    s.summaries.forEach { add(it.account,Math.subtractExact(it.incomeMinor,it.expenseMinor)) }
    s.transactions.filter { it.summaryStatus=="ACTIVE" }.forEach { t ->
        add(t.account,if(t.type=="INCOME") t.amountMinor else -t.amountMinor)
        if(t.type=="TRANSFER") add(checkNotNull(t.destination),t.amountMinor)
    }
    s.loans.filter { it.status in setOf("ACTIVE","PARTIALLY_REPAID","REPAID") }.forEach { l ->
        val lender=l.lenderId==owner
        add(if(lender) l.lenderAccount else l.borrowerAccount, (l.principalMinor-l.repaidMinor)*(if(lender) -1 else 1))
    }
    return result
}
