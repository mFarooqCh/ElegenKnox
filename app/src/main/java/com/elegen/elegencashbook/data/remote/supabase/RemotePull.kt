package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.data.sync.ConflictResolver
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delta pull (spec §6.4): fetches rows where `updated_at > lastPulledAt` per table, resolves each
 * against the local copy via [ConflictResolver], and upserts the winner into Room. RLS already
 * scopes results to rows the caller may read, so the query itself needs no owner filter.
 *
 * A cursor of 0 (never pulled) naturally pulls every row — that's the fresh-install/first-login
 * full hydration, no special-cased code path needed.
 */
@Singleton
class RemotePull @Inject constructor(
    private val holder: SupabaseClientHolder,
    private val businessDao: BusinessDao,
    private val bookDao: BookDao,
    private val transactionDao: TransactionDao,
    private val prefs: AppPreferences,
    private val logger: Logger,
) {
    val isConfigured: Boolean get() = holder.isConfigured

    suspend fun pull() {
        val client = holder.client ?: return
        pullTable(TYPE_BUSINESS, "businesses") { row ->
            val remoteUpdatedAt = parseTimestamp(row.str("updated_at"))
            val local = businessDao.getById(row.str("id"))
            if (winner(local?.sync, remoteUpdatedAt, row.strOrNull("device_id")) == ConflictResolver.Winner.REMOTE) {
                businessDao.upsert(row.toBusinessEntity(remoteUpdatedAt))
            }
        }
        pullTable(TYPE_BOOK, "books") { row ->
            val remoteUpdatedAt = parseTimestamp(row.str("updated_at"))
            val local = bookDao.getById(row.str("id"))
            if (winner(local?.sync, remoteUpdatedAt, row.strOrNull("device_id")) == ConflictResolver.Winner.REMOTE) {
                bookDao.upsert(row.toBookEntity(remoteUpdatedAt))
            }
        }
        pullTable(TYPE_TRANSACTION, "transactions") { row ->
            val remoteUpdatedAt = parseTimestamp(row.str("updated_at"))
            val local = transactionDao.getById(row.str("id"))
            if (winner(local?.sync, remoteUpdatedAt, row.strOrNull("device_id")) == ConflictResolver.Winner.REMOTE) {
                transactionDao.upsert(row.toTransactionEntity(remoteUpdatedAt))
            }
        }
    }

    private fun winner(local: SyncEnvelope?, remoteUpdatedAt: Long, remoteDeviceId: String?) =
        ConflictResolver.resolve(local?.updatedAt, local?.deviceId, remoteUpdatedAt, remoteDeviceId)

    private suspend fun pullTable(type: String, table: String, apply: suspend (JsonObject) -> Unit) {
        val client = holder.client ?: return
        val cursor = prefs.lastPulledAt(type)
        val rows = client.postgrest.from(table).select {
            filter { gt("updated_at", Instant.ofEpochMilli(cursor).toString()) }
            order("updated_at", Order.ASCENDING)
        }.decodeList<JsonObject>()
        var maxSeen = cursor
        for (row in rows) {
            apply(row)
            val ts = parseTimestamp(row.str("updated_at"))
            if (ts > maxSeen) maxSeen = ts
        }
        if (rows.isNotEmpty()) prefs.setLastPulledAt(type, maxSeen)
        logger.debug("Sync", "Pulled ${rows.size} $table rows")
    }

    private companion object {
        const val TYPE_BUSINESS = "BUSINESS_PULL"
        const val TYPE_BOOK = "BOOK_PULL"
        const val TYPE_TRANSACTION = "TRANSACTION_PULL"
    }
}

/** Postgres timestamptz comes back as ISO-8601 with an offset — parse via OffsetDateTime, not Instant (no bare 'Z' guarantee). */
private fun parseTimestamp(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.strOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
private fun JsonObject.longOrNull(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

private fun JsonObject.toBusinessEntity(updatedAt: Long) = BusinessEntity(
    id = str("id"),
    name = str("name"),
    ownerUid = str("owner_uid"),
    currency = str("currency"),
    createdAt = long("created_at"),
    sync = SyncEnvelope(
        version = long("version"),
        updatedAt = updatedAt,
        deviceId = strOrNull("device_id") ?: "",
        deletedAt = longOrNull("deleted_at"),
        syncState = SyncEnvelope.STATE_SYNCED,
    ),
)

private fun JsonObject.toBookEntity(updatedAt: Long) = BookEntity(
    id = str("id"),
    businessId = strOrNull("business_id"),
    ownerUid = str("owner_uid"),
    name = str("name"),
    currency = str("currency"),
    createdAt = long("created_at"),
    sync = SyncEnvelope(
        version = long("version"),
        updatedAt = updatedAt,
        deviceId = strOrNull("device_id") ?: "",
        deletedAt = longOrNull("deleted_at"),
        syncState = SyncEnvelope.STATE_SYNCED,
    ),
)

private fun JsonObject.toTransactionEntity(updatedAt: Long) = TransactionEntity(
    id = str("id"),
    bookId = str("book_id"),
    type = str("type"),
    amountPaisa = long("amount_paisa"),
    categoryId = strOrNull("category_id"),
    description = str("description"),
    createdAt = long("created_at"),
    createdByUid = str("created_by_uid"),
    sync = SyncEnvelope(
        version = long("version"),
        updatedAt = updatedAt,
        deviceId = strOrNull("device_id") ?: "",
        deletedAt = longOrNull("deleted_at"),
        syncState = SyncEnvelope.STATE_SYNCED,
    ),
)
