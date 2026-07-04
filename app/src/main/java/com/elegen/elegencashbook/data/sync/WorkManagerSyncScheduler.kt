package com.elegen.elegencashbook.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import com.elegen.elegencashbook.worker.SyncPushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues [SyncPushWorker] to drain the outbox: network-constrained + exponential backoff
 * (spec §6.3). Unique work with APPEND_OR_REPLACE so a burst of writes coalesces into one drain,
 * while a write landing mid-run still guarantees a follow-up pass over the newly queued rows.
 */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    override fun requestPush() {
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "sync_push"
    }
}
