package com.elegen.elegencashbook.domain.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Repository contracts (spec §4 rule 4). Implementations live in data/repository and are
 * Room-only: Room is the single source of truth; sync happens elsewhere (spec §6.1).
 * All flows emit domain models only (rule 5).
 */

interface BusinessRepository {
    /** All non-deleted businesses with live book counts, oldest first. */
    fun observeBusinesses(): Flow<List<BusinessOverview>>
    suspend fun create(name: String): Business
}

interface BookRepository {
    /** Non-deleted books of a business with live balances, newest first. */
    fun observeBooksWithBalance(businessId: String): Flow<List<BookWithBalance>>
    suspend fun create(businessId: String, name: String): Book
}

interface TransactionRepository {
    /** Non-deleted entries of a book, chronological (oldest first, stable tiebreak). */
    fun observeEntries(bookId: String): Flow<List<Transaction>>
    suspend fun add(bookId: String, type: EntryType, amount: Money, description: String, createdAt: Long): Transaction
    suspend fun update(transaction: Transaction)
    /** Soft delete: sets tombstone, entry disappears from observe flows (spec §6.6). */
    suspend fun softDelete(id: String)
    /** Clears tombstone; entry reappears. */
    suspend fun restore(id: String)
}

interface SettingsRepository {
    val activeBusinessId: Flow<String?>
    suspend fun setActiveBusinessId(id: String)
}
