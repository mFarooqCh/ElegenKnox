package com.elegen.elegencashbook.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.elegen.elegencashbook.domain.repository.SyncScheduler
import com.elegen.elegencashbook.worker.CleanupWorker
import com.elegen.elegencashbook.worker.SyncPullWorker
import com.elegen.elegencashbook.worker.SyncPushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private val networkConstraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestPush() {
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PUSH_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun requestPull() {
        val request = OneTimeWorkRequestBuilder<SyncPullWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(PULL_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodicPull() {
        // 15 minutes is WorkManager's minimum periodic interval — Realtime (P7) covers the
        // foreground/instant case, this is just the background/missed-update backstop.
        val request = PeriodicWorkRequestBuilder<SyncPullWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_PULL_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Drives pull-to-refresh spinners (spec §6.4 latency fix) — true while the one-off requestPull() work is enqueued/running, false once it settles. */
    override fun observePullActive(): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PULL_WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING } }

    override fun scheduleCleanup() {
        val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiresDeviceIdle(true).setRequiresCharging(true).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(CLEANUP_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PUSH_WORK_NAME = "sync_push"
        const val PULL_WORK_NAME = "sync_pull"
        const val PERIODIC_PULL_WORK_NAME = "sync_pull_periodic"
        const val CLEANUP_WORK_NAME = "cleanup"
    }
}
