package com.elegen.elegencashbook.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities (spec §7). Local source of truth. Every entity carries the sync envelope;
 * filled locally in P2, consumed by the outbox/pull workers from P4 on.
 * Entities never leave data/ — mappers convert to domain models (spec §4 rules 2–3).
 */

/** Sync envelope columns, embedded in every synced entity (spec §7). */
data class SyncEnvelope(
    val version: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long?,
    /** PENDING = not yet pushed; SYNCED = acknowledged by server (P4+). */
    val syncState: String,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SYNCED = "SYNCED"
    }
}

@Entity(tableName = "businesses")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Local placeholder until auth (P3) assigns a real uid. */
    val ownerUid: String,
    val currency: String,
    val createdAt: Long,
    @Embedded val sync: SyncEnvelope,
)

@Entity(
    tableName = "books",
    indices = [Index("businessId")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    /** null = personal book (spec §7). */
    val businessId: String?,
    val ownerUid: String,
    val name: String,
    val currency: String,
    val createdAt: Long,
    @Embedded val sync: SyncEnvelope,
)

@Entity(
    tableName = "transactions",
    indices = [Index("bookId", "createdAt"), Index("bookId", "type")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** CASH_IN | CASH_OUT (EntryType name). */
    val type: String,
    val amountPaisa: Long,
    val categoryId: String?,
    val description: String,
    val createdAt: Long,
    val createdByUid: String,
    @Embedded val sync: SyncEnvelope,
)

/**
 * The outbox (spec §6.5). One row per local write that still needs pushing. The row only
 * *references* the entity (type + id + version); the pusher re-reads current Room state at
 * push time, so a soft-deleted (tombstoned) row pushes as an upsert with deletedAt set —
 * CREATE/UPDATE/DELETE all collapse to "upsert current state" (spec §6.3).
 *
 * `id` is the monotonic sequence (auto-increment) — draining in id order gives
 * parent-before-child ordering for free (a book is enqueued before its transactions).
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index("status", "id"),
        // {entityId}:{version} — a given write is enqueued at most once (idempotent replay, spec §6.5).
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idempotencyKey: String,
    /** BUSINESS | BOOK | TRANSACTION. */
    val entityType: String,
    val entityId: String,
    /** CREATE | UPDATE | DELETE — informational; the push is a state upsert regardless. */
    val operation: String,
    val payloadVersion: Long,
    val retryCount: Int = 0,
    val maxRetry: Int = 5,
    val lastAttempt: Long? = null,
    /** PENDING | UPLOADING | DEAD_LETTER (SUCCESS rows are deleted, spec §6.3). */
    val status: String = STATE_PENDING,
) {
    companion object {
        const val TYPE_BUSINESS = "BUSINESS"
        const val TYPE_BOOK = "BOOK"
        const val TYPE_TRANSACTION = "TRANSACTION"

        const val OP_CREATE = "CREATE"
        const val OP_UPDATE = "UPDATE"
        const val OP_DELETE = "DELETE"

        const val STATE_PENDING = "PENDING"
        const val STATE_UPLOADING = "UPLOADING"
        const val STATE_DEAD_LETTER = "DEAD_LETTER"
    }
}

/**
 * Local mirror of Postgres `business_members` (P6, spec §8.5). Pull-only — client never writes
 * this table directly; all writes go through the SECURITY DEFINER RPCs (invite_to_business,
 * update_member_role, revoke_member), so there's no local-edit/conflict path and no envelope
 * (version/deviceId/syncState) needed, just the pull cursor's own `updatedAt`.
 */
@Entity(
    tableName = "business_members",
    indices = [Index("businessId"), Index("userUid")],
)
data class BusinessMemberEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val userUid: String,
    val role: String,
    val status: String,
    /** True = this admin is restricted to an allow-list of books (spec §8.3 adminIsScoped). */
    val bookScoped: Boolean,
    val invitedByUid: String?,
    val joinedAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_VIEWER = "VIEWER"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_REVOKED = "REVOKED"
    }
}

/**
 * Local mirror of Postgres `book_grants` (P6, spec §8.5). Pull-only, same reasoning as
 * [BusinessMemberEntity] — writes go through share_book/set_book_grant/revoke_book_grant RPCs.
 * `deletedAt` here IS meaningful (unlike BusinessMemberEntity's status-only revoke): revoke is a
 * tombstone, not a hard delete, so a revoked grant still round-trips through delta pull.
 */
@Entity(
    tableName = "book_grants",
    indices = [Index("bookId"), Index("userUid")],
)
data class BookGrantEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val userUid: String,
    val access: String,
    /** JSON array of Permission names; null = inherit business-role default (spec §8.3). */
    val permsOverride: String?,
    val grantedByUid: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    companion object {
        const val ACCESS_ALLOW = "ALLOW"
        const val ACCESS_DENY = "DENY"
    }
}

/**
 * Append-only edit-history trail (edit-history feature). One row per mutation on a book or
 * transaction — never updated or hard-deleted itself, so no envelope/version needed. [bookId] is
 * the entity's own id for BOOK rows, or the parent book for TRANSACTION rows — lets the book's
 * "Activity" view query its own history plus every entry's history in one indexed query.
 */
@Entity(
    tableName = "history_log",
    indices = [Index("entityType", "entityId", "at"), Index("bookId", "at")],
)
data class HistoryEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val bookId: String,
    val action: String,
    /** "field=old→new;field2=old2→new2" — plain delimited diff, null for CREATED/DELETED/RESTORED. */
    val changes: String?,
    val actorUid: String,
    val deviceId: String,
    val at: Long,
) {
    companion object {
        const val TYPE_BOOK = "BOOK"
        const val TYPE_TRANSACTION = "TRANSACTION"

        const val ACTION_CREATED = "CREATED"
        const val ACTION_UPDATED = "UPDATED"
        const val ACTION_RENAMED = "RENAMED"
        const val ACTION_MOVED = "MOVED"
        const val ACTION_COPIED = "COPIED"
        const val ACTION_DELETED = "DELETED"
        const val ACTION_RESTORED = "RESTORED"
        const val ACTION_CONFLICT_OVERWRITTEN = "CONFLICT_OVERWRITTEN"
    }
}
