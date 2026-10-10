package com.ledgerx.data.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {
    fun enqueue(context: Context, owner: String) {
        val data=workDataOf("owner" to owner)
        val request=OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("ledger-$owner").build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-$owner",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun schedule(context: Context,owner: String) {
        val work=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("ledger-$owner").build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic-$owner",ExistingPeriodicWorkPolicy.KEEP,work)
        val cleanup=PeriodicWorkRequestBuilder<com.ledgerx.data.compaction.MonthlyCompactionWorker>(12,TimeUnit.HOURS)
            .setInputData(workDataOf("owner" to owner)).addTag("ledger-$owner").build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("compact-$owner",ExistingPeriodicWorkPolicy.KEEP,cleanup)
        enqueue(context,owner)
    }
    fun cancel(context: Context,owner: String) { WorkManager.getInstance(context).cancelAllWorkByTag("ledger-$owner") }
}
