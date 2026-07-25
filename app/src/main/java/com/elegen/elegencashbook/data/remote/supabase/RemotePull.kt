package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.core.logging.Logger
import com.elegen.elegencashbook.data.identity.ActiveIdentity
import com.elegen.elegencashbook.data.identity.IdentityManager
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
import io.github.jan.supabase.SupabaseClient
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
    private val identity: ActiveIdentity,
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
        // audit_log doubles as history_log's remote store (P8) — also holds RBAC rows
        // (BUSINESS_MEMBER/BOOK_GRANT entity types with no book_id); only BOOK/TRANSACTION rows
        // are edit-history. IGNORE-conflict insert means a device pulling its own pushed row back
        // is a harmless no-op (keeps this device's own original `at`, not the server's push-time one).
        pullTable(TYPE_HISTORY, "audit_log", cursorColumn = "at") { row ->
            val entityType = row.str("entity_type")
            if (entityType == HistoryEntity.TYPE_BOOK || entityType == HistoryEntity.TYPE_TRANSACTION) {
                historyDao.insert(row.toHistoryEntity(parseTimestamp(row.str("at"))))
            }
        }
        reconcileLostAccess(client)
    }

    /**
     * Revocation-sync (spec §6.4 gap): a delta pull can only ADD/UPDATE rows the server still
     * returns — it has no way to tell the client "you lost access to X", since once RLS excludes a
     * business/book, it just silently stops appearing in every future delta pull (including the
     * business_members/book_grants rows that would have told the client *why*). The only way to
     * detect a revocation is to compare the FULL current visible set against what's cached
     * locally. Real gap found and flagged during on-device testing 2026-07-12: a device that had
     * already pulled a since-revoked business/book kept showing it forever, even after the server
     * fix landed, until a full reinstall.
     *
     * Local-only tombstone (`markAccessLost`, never touches syncState/version) — this never
     * pushes anything, it just hides a row the server stopped returning. Never touches a business
     * this identity actually owns (`ownerUid == myUid`) — losing your OWN data to a reconciliation
     * bug would be far worse than a stale row lingering an extra pull cycle.
     *
     * Also repairs the reverse case: regained access (e.g. a revoked member reactivated). A
     * reactivated `business_members`/`book_grants` row doesn't touch the business/book's OWN
     * `updated_at`, so the normal delta-pull cursor would never re-fetch it — real bug found
     * on-device, a reactivated shared admin still didn't see the business after restart+refresh.
     * `deleted_at` is fetched alongside each id specifically to tell "still hidden by a genuine
     * remote delete" apart from "visible again, local tombstone is now stale" — `businesses`'/`books`'
     * own SELECT RLS doesn't filter on deleted_at at all, so a genuinely self-deleted row is still
     * selectable here too.
     */
    private suspend fun reconcileLostAccess(client: SupabaseClient) {
        val myUid = identity.current()
        if (myUid == IdentityManager.GUEST_UID) return
        val now = System.currentTimeMillis()

        val visibleBusinessRows = client.postgrest.from("businesses").select().decodeList<JsonObject>()
        val visibleBusinessDeletedAt = visibleBusinessRows.associate { it.str("id") to it.longOrNull("deleted_at") }
        val visibleBusinessIds = visibleBusinessDeletedAt.keys
        val localBusinesses = businessDao.getAllVisible(myUid)
        for (biz in localBusinesses) {
            if (biz.ownerUid == myUid) continue
            if (biz.id !in visibleBusinessIds) businessDao.markAccessLost(biz.id, now)
        }
        for (biz in businessDao.getAllTombstonedNotOwned(myUid)) {
            if (visibleBusinessDeletedAt.containsKey(biz.id) && visibleBusinessDeletedAt[biz.id] == null) {
                businessDao.clearAccessLost(biz.id)
            }
        }
        // Hydrate businesses I can see but never pulled: a membership grant doesn't bump the
        // business's own updated_at, so if it predates my delta cursor the delta pull can never
        // surface it (same gained-access gap as [markAccessLost]'s reverse case, but for a row that
        // was never local to begin with, so there's nothing to un-tombstone).
        for (row in visibleBusinessRows) {
            if (businessDao.getById(row.str("id")) == null) {
                businessDao.upsert(row.toBusinessEntity(parseTimestamp(row.str("updated_at"))))
            }
        }

        val visibleBookRows = client.postgrest.from("books").select().decodeList<JsonObject>()
        val visibleBookDeletedAt = visibleBookRows.associate { it.str("id") to it.longOrNull("deleted_at") }
        val visibleBookIds = visibleBookDeletedAt.keys
        // Re-read: a business just hydrated above must count as accessible for the book pass below.
        val stillAccessibleBusinessIds = businessDao.getAllVisible(myUid).map { it.id }.filter { it in visibleBusinessIds }
        if (stillAccessibleBusinessIds.isNotEmpty()) {
            val localBooks = bookDao.getAllForBusinesses(stillAccessibleBusinessIds)
            for (book in localBooks) {
                if (book.ownerUid == myUid) continue
                if (book.id !in visibleBookIds) bookDao.markAccessLost(book.id, now)
            }
        }
        for (book in bookDao.getAllTombstonedNotOwned(myUid)) {
            if (visibleBookDeletedAt.containsKey(book.id) && visibleBookDeletedAt[book.id] == null) {
                bookDao.clearAccessLost(book.id)
            }
        }
        // Hydrate books (+ their transactions) I can see but never pulled — the reported bug: a
        // VIEWER granted an *existing* book saw 0 books and no entries, because a book_grant leaves
        // the book's and its transactions' updated_at untouched, so every one of those rows sits
        // below my delta cursor and the delta pull skips them permanently. Transactions are pulled
        // cursor-free, scoped to the freshly hydrated book.
        for (row in visibleBookRows) {
            val bookId = row.str("id")
            if (bookDao.getById(bookId) == null) {
                bookDao.upsert(row.toBookEntity(parseTimestamp(row.str("updated_at"))))
                hydrateTransactions(client, bookId)
                hydrateHistory(client, bookId)
            }
        }
    }

    /** Full (cursor-independent) transaction pull for a single book — used when reconcile hydrates a newly-granted book whose rows predate the delta cursor. */
    private suspend fun hydrateTransactions(client: SupabaseClient, bookId: String) {
        val rows = client.postgrest.from("transactions").select {
            filter { eq("book_id", bookId) }
        }.decodeList<JsonObject>()
        for (t in rows) {
            if (transactionDao.getById(t.str("id")) == null) {
                transactionDao.upsert(t.toTransactionEntity(parseTimestamp(t.str("updated_at"))))
            }
        }
    }

    /**
     * Full (cursor-independent) edit-history pull for a single book — the newly-granted book's own
     * book/entry history rows in audit_log also predate the delta cursor, so a book/entry viewer
     * would otherwise never see any history that happened before they were granted access. Same
     * BOOK/TRANSACTION-only filter as the delta history pull; insert is IGNORE-on-conflict.
     */
    private suspend fun hydrateHistory(client: SupabaseClient, bookId: String) {
        val rows = client.postgrest.from("audit_log").select {
            filter { eq("book_id", bookId) }
        }.decodeList<JsonObject>()
        for (row in rows) {
            val entityType = row.str("entity_type")
            if (entityType == HistoryEntity.TYPE_BOOK || entityType == HistoryEntity.TYPE_TRANSACTION) {
                historyDao.insert(row.toHistoryEntity(parseTimestamp(row.str("at"))))
            }
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

    /** [cursorColumn]: audit_log's timestamp column is `at`, not `updated_at` (spec P8 reuse). */
    private suspend fun pullTable(type: String, table: String, cursorColumn: String = "updated_at", apply: suspend (JsonObject) -> Unit) {
        val client = holder.client ?: return
        val cursor = prefs.lastPulledAt(type)
        val rows = client.postgrest.from(table).select {
            filter { gt(cursorColumn, Instant.ofEpochMilli(cursor).toString()) }
            order(cursorColumn, Order.ASCENDING)
        }.decodeList<JsonObject>()
        var maxSeen = cursor
        for (row in rows) {
            apply(row)
            val ts = parseTimestamp(row.str(cursorColumn))
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
        const val TYPE_HISTORY = "HISTORY_PULL"
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

internal fun JsonObject.toBusinessEntity(updatedAt: Long) = BusinessEntity(
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

private fun JsonObject.toHistoryEntity(at: Long) = HistoryEntity(
    id = str("id"),
    entityType = str("entity_type"),
    entityId = str("entity_id"),
    bookId = str("book_id"),
    action = str("action"),
    changes = strOrNull("changes"),
    actorUid = str("actor_uid"),
    deviceId = strOrNull("device_id") ?: "",
    at = at,
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
