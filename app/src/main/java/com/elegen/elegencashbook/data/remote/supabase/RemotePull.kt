package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.local.dao.BookDao
import com.elegen.elegencashbook.data.local.dao.BookGrantDao
import com.elegen.elegencashbook.data.local.dao.BusinessDao
import com.elegen.elegencashbook.data.local.dao.BusinessMemberDao
import com.elegen.elegencashbook.data.local.dao.HistoryDao
import com.elegen.elegencashbook.data.local.dao.TransactionDao
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BookGrantEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.BusinessMemberEntity
import com.elegen.elegencashbook.data.local.entity.HistoryEntity
import com.elegen.elegencashbook.data.local.entity.SyncEnvelope
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.elegen.elegencashbook.data.repository.buildChanges
import com.elegen.elegencashbook.data.sync.ConflictResolver
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
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
    private val historyDao: HistoryDao,
    private val businessMemberDao: BusinessMemberDao,
    private val bookGrantDao: BookGrantDao,
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
                val remote = row.toBookEntity(remoteUpdatedAt)
                logOverwriteIfPendingLocal(HistoryEntity.TYPE_BOOK, remote.id, remote.id, local?.sync, local?.ownerUid.orEmpty()) {
                    buildChanges(Triple("name", local?.name, remote.name), Triple("businessId", local?.businessId, remote.businessId))
                }
                bookDao.upsert(remote)
            }
        }
        pullTable(TYPE_TRANSACTION, "transactions") { row ->
            val remoteUpdatedAt = parseTimestamp(row.str("updated_at"))
            val local = transactionDao.getById(row.str("id"))
            if (winner(local?.sync, remoteUpdatedAt, row.strOrNull("device_id")) == ConflictResolver.Winner.REMOTE) {
                val remote = row.toTransactionEntity(remoteUpdatedAt)
                logOverwriteIfPendingLocal(HistoryEntity.TYPE_TRANSACTION, remote.id, remote.bookId, local?.sync, local?.createdByUid.orEmpty()) {
                    buildChanges(
                        Triple("amountPaisa", local?.amountPaisa, remote.amountPaisa),
                        Triple("description", local?.description, remote.description),
                        Triple("bookId", local?.bookId, remote.bookId),
                    )
                }
                transactionDao.upsert(remote)
            }
        }
        // Server-authoritative, client never writes these rows (RPC-only) — plain upsert, no LWW.
        pullTable(TYPE_BUSINESS_MEMBER, "business_members") { row ->
            businessMemberDao.upsert(row.toBusinessMemberEntity(parseTimestamp(row.str("updated_at"))))
        }
        pullTable(TYPE_BOOK_GRANT, "book_grants") { row ->
            bookGrantDao.upsert(row.toBookGrantEntity(parseTimestamp(row.str("updated_at"))))
        }
    }

    private fun winner(local: SyncEnvelope?, remoteUpdatedAt: Long, remoteDeviceId: String?) =
        ConflictResolver.resolve(local?.updatedAt, local?.deviceId, remoteUpdatedAt, remoteDeviceId)

    /**
     * A remote-wins pull only means "genuine conflict, your edit got clobbered" when the local row
     * had an un-pushed edit sitting in it (PENDING) — remote catching up over an already-SYNCED
     * local row is normal replication, not something to surface to the user.
     */
    private suspend fun logOverwriteIfPendingLocal(entityType: String, entityId: String, bookId: String, localSync: SyncEnvelope?, actorUid: String, changes: () -> String?) {
        if (localSync?.syncState != SyncEnvelope.STATE_PENDING) return
        historyDao.insert(
            HistoryEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                bookId = bookId,
                action = HistoryEntity.ACTION_CONFLICT_OVERWRITTEN,
                changes = changes(),
                actorUid = actorUid,
                deviceId = localSync.deviceId,
                at = System.currentTimeMillis(),
            )
        )
    }

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
        const val TYPE_BUSINESS_MEMBER = "BUSINESS_MEMBER_PULL"
        const val TYPE_BOOK_GRANT = "BOOK_GRANT_PULL"
    }
}

/**
 * Postgres timestamptz comes back as ISO-8601 with an offset — parse via OffsetDateTime, not
 * Instant (no bare 'Z' guarantee). Internal (not private): [RealtimeSync] reuses this and the
 * JsonObject helpers/entity mappers below — both parse the same postgrest/realtime row shape.
 */
internal fun parseTimestamp(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.strOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
internal fun JsonObject.longOrNull(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull
private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
/** jsonb columns arrive already parsed as a JsonElement — re-serialize to text for Room's TEXT column. */
private fun JsonObject.rawJsonOrNull(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.toString()

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

internal fun JsonObject.toBookEntity(updatedAt: Long) = BookEntity(
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

internal fun JsonObject.toTransactionEntity(updatedAt: Long) = TransactionEntity(
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

private fun JsonObject.toBusinessMemberEntity(updatedAt: Long) = BusinessMemberEntity(
    id = str("id"),
    businessId = str("business_id"),
    userUid = str("user_uid"),
    role = str("role"),
    status = str("status"),
    bookScoped = bool("book_scoped"),
    invitedByUid = strOrNull("invited_by_uid"),
    joinedAt = parseTimestamp(str("joined_at")),
    updatedAt = updatedAt,
)

private fun JsonObject.toBookGrantEntity(updatedAt: Long) = BookGrantEntity(
    id = str("id"),
    bookId = str("book_id"),
    userUid = str("user_uid"),
    access = str("access"),
    permsOverride = rawJsonOrNull("perms_override"),
    grantedByUid = strOrNull("granted_by_uid"),
    createdAt = parseTimestamp(str("created_at")),
    updatedAt = updatedAt,
    deletedAt = longOrNull("deleted_at"),
)
