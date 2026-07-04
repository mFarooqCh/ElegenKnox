package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes one outbox row to Supabase via PostgREST upsert (spec §6.3). The row only references an
 * entity; current Room state is re-read here, so a tombstoned row uploads as an upsert with
 * deleted_at set — CREATE/UPDATE/DELETE all reduce to "upsert current state". Idempotent by
 * primary key. `updated_at` is intentionally NOT sent: a Postgres trigger stamps it server-side
 * (device clocks lie — spec §6.6). `syncState` is a client-only column, never sent.
 */
@Singleton
class RemotePush @Inject constructor(
    private val holder: SupabaseClientHolder,
    private val businessDao: BusinessDao,
    private val bookDao: BookDao,
    private val transactionDao: TransactionDao,
) {
    val isConfigured: Boolean get() = holder.isConfigured

    /** Uploads the row's entity. Throws on network/server error (caller handles retry/dead-letter). */
    suspend fun push(row: SyncQueueEntity) {
        val client = holder.client ?: return
        when (row.entityType) {
            SyncQueueEntity.TYPE_BUSINESS -> businessDao.getById(row.entityId)?.let { e ->
                client.postgrest.from("businesses").upsert(e.toJson())
                businessDao.markSynced(e.id, e.sync.version)
            }
            SyncQueueEntity.TYPE_BOOK -> bookDao.getById(row.entityId)?.let { e ->
                client.postgrest.from("books").upsert(e.toJson())
                bookDao.markSynced(e.id, e.sync.version)
            }
            SyncQueueEntity.TYPE_TRANSACTION -> transactionDao.getById(row.entityId)?.let { e ->
                client.postgrest.from("transactions").upsert(e.toJson())
                transactionDao.markSynced(e.id, e.sync.version)
            }
            // Unknown type: nothing local to push; treat as done so the row is cleared.
        }
    }
}

private fun BusinessEntity.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("owner_uid", ownerUid)
    put("currency", currency)
    put("created_at", createdAt)
    put("version", sync.version)
    put("device_id", sync.deviceId)
    put("deleted_at", sync.deletedAt)
}

private fun BookEntity.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("business_id", businessId)
    put("owner_uid", ownerUid)
    put("name", name)
    put("currency", currency)
    put("created_at", createdAt)
    put("version", sync.version)
    put("device_id", sync.deviceId)
    put("deleted_at", sync.deletedAt)
}

private fun TransactionEntity.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("book_id", bookId)
    put("type", type)
    put("amount_paisa", amountPaisa)
    put("category_id", categoryId)
    put("description", description)
    put("created_at", createdAt)
    put("created_by_uid", createdByUid)
    put("version", sync.version)
    put("device_id", sync.deviceId)
    put("deleted_at", sync.deletedAt)
}
