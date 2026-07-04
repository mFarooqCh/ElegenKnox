package com.elegen.elegencashbook.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.identity.IdentityManager
import com.elegen.elegencashbook.data.local.dao.SyncQueueDao
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.remote.supabase.RemotePush
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Drains the outbox to Supabase (spec §6.3). Network-constrained (set at enqueue, see
 * [com.elegen.elegencashbook.data.sync.WorkManagerSyncScheduler]).
 *
 * A plain [CoroutineWorker] pulling its dependencies through a Hilt [EntryPoint] — cheaper than
 * wiring HiltWorker + a custom WorkerFactory + the androidx.hilt artifact for one worker.
 *
 * Sync is paused while guest and drained on first login (a decided invariant): if the active
 * identity is the guest bucket, or no backend is configured, this is a no-op — rows wait on disk.
 */
class SyncPushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun remotePush(): RemotePush
        fun syncQueueDao(): SyncQueueDao
        fun activeIdentity(): ActiveIdentity
        fun logger(): Logger
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val push = deps.remotePush()
        val logger = deps.logger()
        // No backend, or still in the guest bucket → nothing to push yet.
        if (!push.isConfigured || deps.activeIdentity().current() == IdentityManager.GUEST_UID) {
            logger.debug("Sync", "Skipped: configured=${push.isConfigured} identity=${deps.activeIdentity().current()}")
            return Result.success()
        }

        val queue = deps.syncQueueDao()
        val pending = queue.pending()
        logger.debug("Sync", "Draining ${pending.size} pending rows: ${pending.map { "${it.entityType}(qid=${it.id})" }}")
        for (row in pending) {
            try {
                push.push(row)
                queue.delete(row.id) // SUCCESS rows are deleted, not kept (spec §6.3)
            } catch (e: Exception) {
                logger.warn("Sync", "Push failed for ${row.entityType} qid=${row.id}: ${e.message}")
                val attempts = row.retryCount + 1
                val now = System.currentTimeMillis()
                if (attempts >= row.maxRetry) {
                    // Give up on this row but don't drop it silently — surfaced for manual retry (P8).
                    queue.recordAttempt(row.id, attempts, now, SyncQueueEntity.STATE_DEAD_LETTER)
                    deps.logger().warn("Sync", "Outbox row dead-lettered after $attempts attempts")
                    // Continue draining the rest — one poison row must not block the queue forever.
                } else {
                    // Ordering matters (parent before child): stop here, let WorkManager back off and
                    // retry the whole tail from this row.
                    queue.recordAttempt(row.id, attempts, now, SyncQueueEntity.STATE_PENDING)
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }
}
