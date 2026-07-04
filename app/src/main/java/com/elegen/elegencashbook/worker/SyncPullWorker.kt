package com.elegen.elegencashbook.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.identity.IdentityManager
import com.elegen.elegencashbook.data.remote.supabase.RemotePull
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Delta-pulls remote changes into Room (spec §6.4). Same guard as [SyncPushWorker]: no-op while
 * guest or unconfigured — nothing to pull for a bucket that was never pushed anywhere.
 */
class SyncPullWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun remotePull(): RemotePull
        fun activeIdentity(): ActiveIdentity
        fun logger(): Logger
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val pull = deps.remotePull()
        if (!pull.isConfigured || deps.activeIdentity().current() == IdentityManager.GUEST_UID) {
            return Result.success()
        }
        return try {
            pull.pull()
            Result.success()
        } catch (e: Exception) {
            deps.logger().warn("Sync", "Pull failed: ${e.message}")
            Result.retry()
        }
    }
}
