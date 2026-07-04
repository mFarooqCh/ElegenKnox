package com.elegen.elegencashbook.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
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

    /** Scoped to the active identity (guest sentinel or real uid) — the device's isolation gate. */
    @Query(
        """
        SELECT b.*, COUNT(bk.id) AS bookCount
        FROM businesses b
        LEFT JOIN books bk ON bk.businessId = b.id AND bk.deletedAt IS NULL
        WHERE b.ownerUid = :uid AND b.deletedAt IS NULL
        GROUP BY b.id
        ORDER BY b.createdAt ASC
        """
    )
    fun observeWithBookCount(uid: String): Flow<List<BusinessWithCountRow>>

    @Query("SELECT * FROM businesses WHERE id = :id")
    suspend fun getById(id: String): BusinessEntity?

    /** Guest → uid on login: reassign ownership and re-queue for sync (spec §8.2 claim). */
    @Query("UPDATE businesses SET ownerUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE ownerUid = :guest")
    suspend fun claimOwner(guest: String, uid: String, now: Long)

    /** Marks SYNCED only if the row hasn't been re-edited since it was pushed (version guard). */
    @Query("UPDATE businesses SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)
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

    @Query("UPDATE books SET ownerUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE ownerUid = :guest")
    suspend fun claimOwner(guest: String, uid: String, now: Long)

    @Query("UPDATE books SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)
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

    /** Snapshot for book duplication — live (non-tombstoned) entries only. */
    @Query("SELECT * FROM transactions WHERE bookId = :bookId AND deletedAt IS NULL")
    suspend fun getAllActiveByBook(bookId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET createdByUid = :uid, updatedAt = :now, syncState = 'PENDING' WHERE createdByUid = :guest")
    suspend fun claimCreator(guest: String, uid: String, now: Long)

    @Query("UPDATE transactions SET syncState = 'SYNCED' WHERE id = :id AND version = :version")
    suspend fun markSynced(id: String, version: Long)
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
        ORDER BY CASE entityType WHEN 'BUSINESS' THEN 0 WHEN 'BOOK' THEN 1 ELSE 2 END, id ASC
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
