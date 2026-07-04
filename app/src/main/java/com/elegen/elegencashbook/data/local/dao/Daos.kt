package com.elegen.elegencashbook.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import com.elegen.elegencashbook.data.local.entity.BookEntity
import com.elegen.elegencashbook.data.local.entity.BusinessEntity
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

    @Query(
        """
        SELECT b.*,
          COALESCE(SUM(CASE WHEN t.type = 'CASH_IN'  AND t.deletedAt IS NULL THEN t.amountPaisa ELSE 0 END), 0) AS totalInPaisa,
          COALESCE(SUM(CASE WHEN t.type = 'CASH_OUT' AND t.deletedAt IS NULL THEN t.amountPaisa ELSE 0 END), 0) AS totalOutPaisa,
          COUNT(CASE WHEN t.deletedAt IS NULL THEN t.id END) AS entryCount,
          MAX(CASE WHEN t.deletedAt IS NULL THEN t.createdAt END) AS lastEntryAt
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
}
