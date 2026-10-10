package com.ledgerx.data

import android.content.Context
import androidx.room.*
import com.ledgerx.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Entity(tableName = "movements")
data class MovementEntity(
    @PrimaryKey val id: String,
    val kind: String, val amount: Long, val account: String,
    val destination: String?, val category: String?, val person: String?,
    val note: String, val occurredAt: Long
) {
    fun model() = Movement(id, Kind.valueOf(kind), amount, Account.valueOf(account), destination?.let(Account::valueOf), category?.let(Category::valueOf), person, note, occurredAt)
    companion object { fun from(m: Movement) = MovementEntity(m.id, m.kind.name, m.amount, m.account.name, m.destination?.name, m.category?.name, m.person, m.note, m.occurredAt) }
}
@Dao
interface MovementDao {
    @Query("SELECT * FROM movements ORDER BY occurredAt DESC, rowid DESC") fun observe(): Flow<List<MovementEntity>>
    @Query("SELECT * FROM movements ORDER BY occurredAt ASC, rowid ASC") suspend fun all(): List<MovementEntity>
    @Insert suspend fun insert(movement: MovementEntity)
    @Upsert suspend fun merge(movements: List<MovementEntity>)
}
@Database(entities = [MovementEntity::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun movements(): MovementDao
    companion object {
        private val instances = mutableMapOf<String, LedgerDatabase>()
        fun create(context: Context, owner: String? = null): LedgerDatabase = synchronized(this) {
            val name = if (owner == null) "ledgerx.db" else {
                val scope = UUID.nameUUIDFromBytes((com.ledgerx.BuildConfig.SUPABASE_URL + ":" + owner).toByteArray())
                "ledgerx-$scope.db"
            }
            instances.getOrPut(name) {
                Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name).build()
            }
        }
    }
}
class LedgerRepository(private val db: LedgerDatabase) {
    private val writes = Mutex()
    val movements = db.movements().observe().map { rows -> rows.map { it.model() } }
    suspend fun records(): List<Movement> = db.movements().all().map { it.model() }
    suspend fun add(m: Movement) = writes.withLock { db.withTransaction {
        val existing = db.movements().all().map { it.model() }
        // Reuse spelling for a known contact, preventing Rahim / rahim split balances.
        val name = m.person?.trim()?.replace(Regex("\\s+"), " ")
        val canonical = existing.mapNotNull { it.person }.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        val normalized = m.copy(person = canonical, note = m.note.trim())
        Ledger.validate(normalized, existing)
        db.movements().insert(MovementEntity.from(normalized))
    } }

    suspend fun sync(exchange: suspend (List<Movement>) -> List<Movement>): Int = writes.withLock {
        val local = db.movements().all().map { it.model() }
        val remote = exchange(local)
        val remoteIds = remote.map { it.id }.toSet()
        require(remoteIds.size == remote.size && local.all { it.id in remoteIds }) {
            "Incomplete sync response. Your local records have been kept."
        }
        Ledger.summarize(remote) // Reject aggregate overflow before touching the local ledger.
        db.withTransaction { db.movements().merge(remote.map(MovementEntity::from)) }
        remote.size
    }
}
