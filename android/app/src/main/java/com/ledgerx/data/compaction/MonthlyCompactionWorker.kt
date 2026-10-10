package com.ledgerx.data.compaction

import android.content.Context
import androidx.work.*
import com.ledgerx.data.*
import com.ledgerx.data.repository.TransactionRepository
import kotlinx.coroutines.CancellationException

class MonthlyCompactionWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val owner=inputData.getString("owner")?:return Result.failure()
        return try {
            val repo=TransactionRepository(LedgerDatabase.create(applicationContext,owner),owner)
            repo.initialize(); repo.compact(); Result.success()
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { Result.retry() }
    }
}
