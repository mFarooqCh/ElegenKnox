package com.elegen.elegencashbook.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Purges tombstoned rows past the retention window (spec §6.5/§6.6). Outbox rows are already
 * deleted the moment they push successfully (see [SyncPushWorker]), so there's no separate
 * SUCCESS-row purge to do here — only the entity tombstones need aging out.
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun businessDao(): BusinessDao
        fun bookDao(): BookDao
        fun transactionDao(): TransactionDao
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        deps.businessDao().purgeTombstones(cutoff)
        deps.bookDao().purgeTombstones(cutoff)
        deps.transactionDao().purgeTombstones(cutoff)
        return Result.success()
    }

    private companion object {
        val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(30)
    }
}
