package com.ledgerx.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.ledgerx.data.LedgerDatabase
import com.ledgerx.data.migration.Migration2to3
import com.ledgerx.data.migration.Migration1to2
import com.ledgerx.data.entities.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class TransactionRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: TransactionRepository
    private val owner="11111111-1111-1111-1111-111111111111"
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDatabase::class.java).allowMainThreadQueries().build()
        repo=TransactionRepository(db,owner)
    }
    @After fun close() { db.close() }
    private fun oldRow()=TransactionEntity(UUID.randomUUID().toString(),owner,"INCOME",12345,"BANK",null,categoryKey(owner,"other"),"private detail",1600000000000,"PENDING","ACTIVE",null,null,null,1,1600000000000,1600000000000)
    @Test fun compactionIsIdempotentAndUnsyncedDetailsSurvive()=runBlocking {
        repo.initialize()
        val t=oldRow(); db.transactionDao().put(t)
        repo.compact(1791500000000)
        val first=repo.current()
        assertEquals(12345L,balances(first).getValue("BANK"))
        val deadline=first.transactions.single().deleteAfter
        repo.compact(1791500000000+TRASH_RETENTION_MS+1)
        val second=repo.current()
        assertEquals(1,second.summaries.single().transactionCount)
        assertEquals(deadline,second.transactions.single().deleteAfter)
        assertEquals("PENDING",second.transactions.single().syncStatus)
    }
    @Test fun syncedTrashExpiresWithoutChangingTotals()=runBlocking {
        repo.initialize(); db.transactionDao().put(oldRow()); repo.compact(1791500000000)
        val t=db.transactionDao().all().single()
        db.transactionDao().put(t.copy(syncStatus="SYNCED"))
        repo.compact(t.deleteAfter!!-1)
        assertEquals(1,db.transactionDao().all().size)
        repo.compact(t.deleteAfter)
        assertTrue(db.transactionDao().all().isEmpty())
        assertEquals(1,db.deletionDao().all().size)
        assertEquals(12345L,balances(repo.current()).getValue("BANK"))
    }
    @Test fun queuedDependenciesKeepStrictOrder()=runBlocking {
        repo.initialize()
        repo.queue("TRANSACTION","income","INSERT","{}")
        repo.queue("TRANSACTION","expense","INSERT","{}")
        val rows=db.syncQueueDao().all()
        assertTrue(rows.zipWithNext().all { (a,b) -> a.createdAt<b.createdAt })
        assertEquals("income",rows[rows.size-2].entityId)
        assertEquals("expense",rows.last().entityId)
    }
    @Test fun transferNeverCompacts()=runBlocking {
        repo.initialize()
        db.transactionDao().put(oldRow().copy(type="TRANSFER",destination="CASH",categoryId=null))
        repo.compact(1791500000000)
        assertTrue(db.monthlySummaryDao().all().isEmpty())
        assertEquals("ACTIVE",db.transactionDao().all().single().summaryStatus)
    }
    @Test fun pendingLocalMonthSurvivesRemoteSnapshot()=runBlocking {
        repo.initialize(); val t=oldRow(); db.transactionDao().put(t)
        repo.queue("TRANSACTION",t.id,"INSERT","{}")
        repo.compact()
        repo.merge(LedgerSnapshot())
        assertEquals(12345L,balances(repo.current()).getValue("BANK"))
        assertEquals(1,db.monthlySummaryDao().all().single().transactionCount)
    }
    @Test fun serverTombstoneRemovesLegacyBackupAndDoesNotResurrect()=runBlocking {
        repo.initialize(); val t=oldRow();db.transactionDao().put(t)
        repo.queue("TRANSACTION",t.id,"INSERT","private detail")
        repo.merge(LedgerSnapshot(deletions=listOf(DeletionEntity(t.id,owner,1791500000000))))
        assertTrue(db.transactionDao().all().isEmpty())
        assertFalse(db.syncQueueDao().all().any { it.entityId==t.id })
        repo.initialize()
        assertTrue(db.transactionDao().all().isEmpty())
    }
    @Test fun repeatedDecisionKeepsReceiptAndOppositeDecisionReplacesIt()=runBlocking {
        repo.queue("LOAN_EVENT","event","ACCEPT","{}")
        val original=db.syncQueueDao().all().single()
        db.syncQueueDao().put(original.copy(attempts=3,lastError="Old failure"))
        repo.queue("LOAN_EVENT","event","ACCEPT","{}")
        val repeated=db.syncQueueDao().all().single()
        assertEquals(original.id,repeated.id)
        assertEquals(0,repeated.attempts)
        assertNull(repeated.lastError)
        repo.queue("LOAN_EVENT","event","REJECT","{}")
        val replaced=db.syncQueueDao().all().single()
        assertEquals("REJECT",replaced.action)
        assertNotEquals(original.id,replaced.id)
    }
    @Test fun excessiveRepaymentCannotBeAcceptedButCanBeRejected()=runBlocking {
        val loan=LoanEntity("loan",owner,"other",1000,"BDT","","PARTIALLY_REPAID","other","CASH","CASH",900,1,1,null,null,null,"NOT_REQUIRED","SYNCED",1,null)
        db.loanDao().put(loan)
        val event=LoanEventEntity("event",loan.id,"REPAYMENT",200,"other",null,"PENDING",2,"SYNCED")
        db.loanEventDao().put(event)
        val loans=LoanRepository(repo)
        try { loans.decideEvent(event.id,true); fail("Must reject overpayment") } catch(_:IllegalArgumentException) { }
        assertTrue(db.syncQueueDao().all().isEmpty())
        loans.decideEvent(event.id,false)
        assertEquals("REJECT",db.syncQueueDao().all().single().action)
        assertEquals(900L,db.loanDao().all().single().repaidMinor)
    }
    @Test fun migration2PreservesFailedCommandsAndFinancialData()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration2-${UUID.randomUUID()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object: SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE movements (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,amount INTEGER NOT NULL,account TEXT NOT NULL,destination TEXT,category TEXT,person TEXT,note TEXT NOT NULL,occurredAt INTEGER NOT NULL)")
                Migration1to2.migrate(db)
                db.execSQL("INSERT INTO sync_queue VALUES ('receipt','LOAN_EVENT','event','REJECT','{}',100,3,200)")
                db.execSQL("INSERT INTO movements VALUES ('income','INCOME',100000,'CASH',NULL,NULL,NULL,'original',1600000000000)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) {}
        }).build())
        helper.writableDatabase; helper.close()
        val migrated=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(Migration2to3).allowMainThreadQueries().build()
        try {
            val row=migrated.syncQueueDao().all().single()
            assertEquals("receipt",row.id)
            assertEquals("REJECT",row.action)
            assertEquals(0,row.attempts)
            assertNull(row.lastError)
            assertEquals(100000L,migrated.movements().all().single().amount)
        } finally { migrated.close();context.deleteDatabase(name) }
    }
    @Test fun migrationPreservesAllOldRowsAndImportsOnlyOnce()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${UUID.randomUUID()}.db"
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object: SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE movements (id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,amount INTEGER NOT NULL,account TEXT NOT NULL,destination TEXT,category TEXT,person TEXT,note TEXT NOT NULL,occurredAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO movements VALUES ('income','INCOME',100000,'CASH',NULL,NULL,NULL,'original',1600000000000)")
                db.execSQL("INSERT INTO movements VALUES ('loan','LEND',20000,'CASH',NULL,NULL,'Rahim','private',1600000000001)")
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int) {}
        }).build())
        helper.writableDatabase; helper.close()
        val migrated=Room.databaseBuilder(context,LedgerDatabase::class.java,name).addMigrations(Migration1to2, Migration2to3).allowMainThreadQueries().build()
        try {
            val r=TransactionRepository(migrated,owner);r.initialize();r.initialize()
            assertEquals(2,migrated.movements().all().size)
            assertEquals(1,migrated.transactionDao().all().size)
            assertEquals(1,r.legacy().size)
            assertTrue(migrated.loanDao().all().isEmpty()) // No invented shared identity/approval.
            assertEquals(1,migrated.syncQueueDao().all().count { it.entityType=="LEGACY_IMPORT" })
            assertEquals(80000L,balances(r.current(),r.legacy(),owner).getValue("CASH"))
        } finally { migrated.close();context.deleteDatabase(name) }
    }
}
