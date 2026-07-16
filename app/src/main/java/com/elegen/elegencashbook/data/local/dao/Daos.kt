package com.elegen.elegencashbook.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BookGrantEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
import com.elegen.elegencashbook.data.local.entity.BusinessMemberEntity
import com.elegen.elegencashbook.data.local.entity.HistoryEntity
import com.elegen.elegencashbook.data.local.entity.SyncQueueEntity
import com.elegen.elegencashbook.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class BusinessWithCountRow(
    @Embedded val business: BusinessEntity,
    val bookCount: Int,
)

@Dao
interface BusinessDao {
    @Upsert
    suspend fun upsert(entity: BusinessEntity)

    /**
     * Scoped to the active identity (guest sentinel or real uid) — the device's isolation gate.
     * Owner OR active business_members row: an owner=false shared business (P6/P7) still has to
     * show up here, not just businesses this device happens to own.
     */
    @Query(
        """
        SELECT b.*, COUNT(bk.id) AS bookCount
        FROM businesses b
        LEFT JOIN books bk ON bk.businessId = b.id AND bk.deletedAt IS NULL
        WHERE b.deletedAt IS NULL
          AND (b.ownerUid = :uid OR EXISTS (
            SELECT 1 FROM business_members m
            WHERE m.businessId = b.id AND m.userUid = :uid AND m.status = 'ACTIVE'
          ))
        GROUP BY b.id
        ORDER BY b.createdAt ASC
        """
    )
    fun observeWithBookCount(uid: String): Flow<List<BusinessWithCountRow>>

    @Query("SELECT * FROM businesses WHERE id = :id")
    suspend fun getById(id: String): BusinessEntity?

    /** One-shot snapshot of [observeWithBookCount]'s visibility predicate, minus the book-count join — used by the access-revocation reconciliation pass (spec §6.4). */
    @Query(
        """
        SELECT * FROM businesses b
        WHERE b.deletedAt IS NULL
          AND (b.ownerUid = :uid OR EXISTS (
            SELECT 1 FROM business_members m
            WHERE m.businessId = b.id AND m.userUid = :uid AND m.status = 'ACTIVE'
          ))
        """
    )
    suspend fun getAllVisible(uid: String): List<BusinessEntity>

    /**
     * Local-only tombstone for a business this identity can no longer see server-side (revoked
     * access) — deliberately does NOT touch syncState/version, so the outbox never mistakes this
     * for a real delete to push. Real deletes go through the normal write path; this just hides a
     * row the server stopped returning.
     */
    @Query("UPDATE businesses SET deletedAt = :now WHERE id = :id")
    suspend fun markAccessLost(id: String, now: Long)

    /** Local access-lost tombstones (not real deletes) not owned by this identity — candidates for the regained-access repair pass (spec §6.4). */
    @Query("SELECT * FROM businesses WHERE deletedAt IS NOT NULL AND ownerUid != :uid")
    suspend fun getAllTombstonedNotOwned(uid: String): List<BusinessEntity>

    /** Clears a local-only access-lost tombstone (counterpart to [markAccessLost]) — access was regained. */
    @Query("UPDATE businesses SET deletedAt = NULL WHERE id = :id")
    suspend fun clearAccessLost(id: String)

    /** Guest → uid on login: reassign ownership and re-queue for sync (spec §8.2 claim). */
    @Query("UPDATE businesses SET ownerUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE ownerUid = :guest")
    suspend fun claimOwner(guest: String, uid: String, now: Long)

    /** Marks SYNCED only if the row hasn't been re-edited since it was pushed (version guard). */
    @Query("UPDATE businesses SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)

    /** CleanupWorker: hard-purge tombstones older than the retention cutoff (spec §6.6). */
    @Query("DELETE FROM businesses WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstones(cutoff: Long)
}

data class BookWithBalanceRow(
    @Embedded val book: BookEntity,
    val totalInPaisa: Long,
    val totalOutPaisa: Long,
    val entryCount: Int,
    val lastEntryAt: Long?,
)

@Dao
interface BookDao {
    @Upsert
    suspend fun upsert(entity: BookEntity)

    /**
     * lastEntryAt is the real wall-clock last-write time (sync.updatedAt), not an entry's
     * user-picked date (createdAt) — a backdated entry must not make the book look stale.
     */
    @Query(
        """
        SELECT b.*,
          COALESCE(SUM(CASE WHEN t.type = 'CASH_IN'  AND t.deletedAt IS NULL THEN t.amountPaisa ELSE 0 END), 0) AS totalInPaisa,
          COALESCE(SUM(CASE WHEN t.type = 'CASH_OUT' AND t.deletedAt IS NULL THEN t.amountPaisa ELSE 0 END), 0) AS totalOutPaisa,
          COUNT(CASE WHEN t.deletedAt IS NULL THEN t.id END) AS entryCount,
          MAX(b.updatedAt, COALESCE(MAX(CASE WHEN t.deletedAt IS NULL THEN t.updatedAt END), 0)) AS lastEntryAt
        FROM books b
        LEFT JOIN transactions t ON t.bookId = b.id
        WHERE b.businessId = :businessId AND b.deletedAt IS NULL
        GROUP BY b.id
        ORDER BY b.createdAt DESC
        """
    )
    fun observeWithBalance(businessId: String): Flow<List<BookWithBalanceRow>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    /** Reactive single-book lookup — permission resolution needs to react to businessId/ownerUid pulls. */
    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    /** Non-deleted local books in any of the given (still-accessible) businesses — access-revocation reconciliation pass (spec §6.4). */
    @Query("SELECT * FROM books WHERE businessId IN (:businessIds) AND deletedAt IS NULL")
    suspend fun getAllForBusinesses(businessIds: List<String>): List<BookEntity>

    /** Local-only tombstone, see [BusinessDao.markAccessLost] — never touches syncState/version. */
    @Query("UPDATE books SET deletedAt = :now WHERE id = :id")
    suspend fun markAccessLost(id: String, now: Long)

    /** See [BusinessDao.getAllTombstonedNotOwned] — same regained-access repair, for books. */
    @Query("SELECT * FROM books WHERE deletedAt IS NOT NULL AND ownerUid != :uid")
    suspend fun getAllTombstonedNotOwned(uid: String): List<BookEntity>

    /** Clears a local-only access-lost tombstone (counterpart to [markAccessLost]) — access was regained. */
    @Query("UPDATE books SET deletedAt = NULL WHERE id = :id")
    suspend fun clearAccessLost(id: String)

    @Query("UPDATE books SET ownerUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE ownerUid = :guest")
    suspend fun claimOwner(guest: String, uid: String, now: Long)

    @Query("UPDATE books SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)

    @Query("DELETE FROM books WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstones(cutoff: Long)
}

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(entity: TransactionEntity)

    /** Chronological, stable tiebreak so running balances are deterministic. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE bookId = :bookId AND deletedAt IS NULL
        ORDER BY createdAt ASC, rowid ASC
        """
    )
    fun observeActiveByBook(bookId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    /** Live single-entry view for the Entry Details screen; null once soft-deleted. */
    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    /** Snapshot for book duplication — live (non-tombstoned) entries only. */
    @Query("SELECT * FROM transactions WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun getAllActiveByBook(bookId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET createdByUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE createdByUid = :guest")
    suspend fun claimCreator(guest: String, uid: String, now: Long)

    @Query("UPDATE transactions SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)

    @Query("DELETE FROM transactions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstones(cutoff: Long)
}

@Dao
interface SyncQueueDao {
    /** Idempotent: a duplicate {entityId}:{version} key is ignored (unique index, spec §6.5). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SyncQueueEntity)

    /**
     * Drain order: entity-type tier (BUSINESS, BOOK, TRANSACTION) first, insertion id within a
     * tier second — parent-before-child (spec §6.3). Tiering, not just id order, matters because
     * a backfilled row (pre-existing local data enqueued after the fact, see MIGRATION_2_3) can
     * land with a *higher* id than an already-queued child row; plain id order would keep retrying
     * that child (FK violation against a not-yet-pushed parent) forever instead of pushing the
     * parent first.
     */
    @Query(
        """
        SELECT * FROM sync_queue WHERE status = 'PENDING'
        ORDER BY CASE entityType WHEN 'BUSINESS' THEN 0 WHEN 'BOOK' THEN 1 WHEN 'TRANSACTION' THEN 2 ELSE 3 END, id ASC
        """
    )
    suspend fun pending(): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: Long)

    /** Bump retry + timestamp; caller flips status to DEAD_LETTER once retryCount >= maxRetry. */
    @Query("UPDATE sync_queue SET retryCount = :retryCount, lastAttempt = :now, status = :status WHERE id = :id")
    suspend fun recordAttempt(id: Long, retryCount: Int, now: Long, status: String)

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}

@Dao
interface HistoryDao {
    /** IGNORE, not ABORT: a pulled echo of a row this device already created (same id) must not crash the pull. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: HistoryEntity)

    @Query("SELECT * FROM history_log WHERE entityType = :entityType AND entityId = :entityId ORDER BY at DESC")
    fun observeForEntity(entityType: String, entityId: String): Flow<List<HistoryEntity>>

    /** Outbox push re-read (spec §6.3 pattern) — history rows carry no envelope, so no markSynced counterpart. */
    @Query("SELECT * FROM history_log WHERE id = :id")
    suspend fun getById(id: String): HistoryEntity?
}

@Dao
interface BusinessMemberDao {
    @Upsert
    suspend fun upsert(entity: BusinessMemberEntity)

    /** Caller's own ACTIVE membership row for a business, or null if not a member (VIEWER included). */
    @Query("SELECT * FROM business_members WHERE businessId = :businessId AND userUid = :userUid AND status = 'ACTIVE'")
    suspend fun getActiveMembership(businessId: String, userUid: String): BusinessMemberEntity?

    @Query("SELECT * FROM business_members WHERE businessId = :businessId AND status = 'ACTIVE'")
    fun observeActiveMembers(businessId: String): Flow<List<BusinessMemberEntity>>
}

@Dao
interface BookGrantDao {
    @Upsert
    suspend fun upsert(entity: BookGrantEntity)

    /** Caller's own live (non-tombstoned) grant for a book, or null if ungranted. */
    @Query("SELECT * FROM book_grants WHERE bookId = :bookId AND userUid = :userUid AND deletedAt IS NULL")
    suspend fun getActiveGrant(bookId: String, userUid: String): BookGrantEntity?

    @Query("SELECT * FROM book_grants WHERE bookId = :bookId AND deletedAt IS NULL")
    fun observeActiveGrants(bookId: String): Flow<List<BookGrantEntity>>
}
