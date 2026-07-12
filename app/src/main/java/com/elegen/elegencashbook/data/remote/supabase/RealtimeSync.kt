package com.elegen.elegencashbook.data.remote.supabase

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.elegen.elegencashbook.core.common.AppScope
import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.sync.ConflictResolver
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground-only live updates (P7, spec §6.4): while a business is active and the *app* (not just
 * one Activity) is in the foreground, subscribe to Postgres changes on `books`/`transactions` and
 * merge them into Room the same way [RemotePull] merges a delta pull — same [ConflictResolver],
 * same JsonObject→Entity mappers, just pushed instead of polled. Backgrounded devices still get
 * the changes via the periodic pull worker (spec §6.4) — this is purely a latency improvement, not
 * a second source of truth.
 *
 * Driven off [ProcessLifecycleOwner], not a single Activity's onStop: real bug found on-device — a
 * shared viewer kept a book open in `BookDetailsActivity` while the owner added an entry elsewhere,
 * and it never arrived, even after backing out and reopening the book. Root cause was tying the
 * subscription to `MainActivity.onStop()` — navigating to ANY other Activity stops MainActivity,
 * killing the channel immediately even though the app itself was still fully foregrounded.
 *
 * Realtime's `postgres_changes` enforces each subscriber's own RLS (same `effective_perms()`
 * policies as PostgREST), so a shared/scoped user only ever receives rows they're allowed to see
 * — no extra client-side membership filtering needed here.
 */
@Singleton
class RealtimeSync @Inject constructor(
    private val holder: SupabaseClientHolder,
    private val bookDao: BookDao,
    private val transactionDao: TransactionDao,
    private val logger: Logger,
    @AppScope private val appScope: CoroutineScope,
) : DefaultLifecycleObserver {

    private var channel: RealtimeChannel? = null
    private var job: Job? = null

    /** The business the UI last asked for — restored automatically when the app returns to the foreground. */
    private var desiredBusinessId: String? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        desiredBusinessId?.let { startChannel(it) }
    }

    override fun onStop(owner: LifecycleOwner) {
        stopChannel()
    }

    /** Called from the UI on business switch/logout (not on Activity navigation). Idempotent — same [businessId] repeated is a harmless no-op. */
    fun setActiveBusiness(businessId: String?) {
        if (desiredBusinessId == businessId) return
        desiredBusinessId = businessId
        if (businessId != null) startChannel(businessId) else stopChannel()
    }

    private fun startChannel(businessId: String) {
        stopChannel()
        val client = holder.client ?: return
        val ch = client.channel("business-live:$businessId")
        channel = ch
        job = appScope.launch {
            try {
                val bookChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "books"
                    filter("business_id", FilterOperator.EQ, businessId)
                }
                val txChanges = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "transactions"
                    // no business_id column on transactions to filter by server-side; RLS still
                    // scopes what actually arrives to books this subscriber can view.
                }
                ch.subscribe()
                merge(
                    bookChanges.map { it to "books" },
                    txChanges.map { it to "transactions" },
                ).collect { (action, table) -> applyAction(action, table) }
            } catch (e: Exception) {
                logger.warn("Realtime", "Subscription failed for business $businessId", e)
            }
        }
    }

    private fun stopChannel() {
        job?.cancel()
        job = null
        channel?.let { ch -> appScope.launch { holder.client?.realtime?.removeChannel(ch) } }
        channel = null
    }

    private suspend fun applyAction(action: PostgresAction, table: String) {
        val record = when (action) {
            is PostgresAction.Insert -> action.record
            is PostgresAction.Update -> action.record
            is PostgresAction.Delete, is PostgresAction.Select -> return
        }
        val remoteUpdatedAt = parseTimestamp(record.str("updated_at"))
        val remoteDeviceId = record.strOrNull("device_id")
        when (table) {
            "books" -> {
                val id = record.str("id")
                val local = bookDao.getById(id)
                if (winner(local?.sync, remoteUpdatedAt, remoteDeviceId) == ConflictResolver.Winner.REMOTE) {
                    bookDao.upsert(record.toBookEntity(remoteUpdatedAt))
                }
            }
            "transactions" -> {
                val id = record.str("id")
                val local = transactionDao.getById(id)
                if (winner(local?.sync, remoteUpdatedAt, remoteDeviceId) == ConflictResolver.Winner.REMOTE) {
                    transactionDao.upsert(record.toTransactionEntity(remoteUpdatedAt))
                }
            }
        }
    }

    private fun winner(local: SyncEnvelope?, remoteUpdatedAt: Long, remoteDeviceId: String?) =
        ConflictResolver.resolve(local?.updatedAt, local?.deviceId, remoteUpdatedAt, remoteDeviceId)
}
