package com.elegen.elegencashbook.domain.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.SessionState
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
    suspend fun rename(bookId: String, name: String)
    /** Soft delete: sets tombstone, book disappears from observe flows (spec §6.6). */
    suspend fun softDelete(bookId: String)
    /** Clears tombstone; book reappears. */
    suspend fun restore(bookId: String)
    /** Copies the book (new id) plus its live entries into the same business. */
    suspend fun duplicate(bookId: String): Book
    suspend fun move(bookId: String, targetBusinessId: String)
}

interface TransactionRepository {
    /** Non-deleted entries of a book, chronological (oldest first, stable tiebreak). */
    fun observeEntries(bookId: String): Flow<List<Transaction>>
    /** Single entry for the Entry Details screen; emits null once soft-deleted. */
    fun observeById(id: String): Flow<Transaction?>
    suspend fun add(bookId: String, type: EntryType, amount: Money, description: String, createdAt: Long): Transaction
    suspend fun update(transaction: Transaction)
    /** Soft delete: sets tombstone, entry disappears from observe flows (spec §6.6). */
    suspend fun softDelete(id: String)
    /** Clears tombstone; entry reappears. */
    suspend fun restore(id: String)
    /** Moves the entry to another book (original book loses it). */
    suspend fun move(id: String, targetBookId: String)
    /** Copies the entry into another book; the original stays where it is. */
    suspend fun copyTo(id: String, targetBookId: String): Transaction
}

interface SettingsRepository {
    val activeBusinessId: Flow<String?>
    suspend fun setActiveBusinessId(id: String)
    suspend fun clearActiveBusinessId()

    /** True once the user explicitly picked "continue without account" (or signed out). */
    val guestModeChosen: Flow<Boolean>
    suspend fun setGuestModeChosen(chosen: Boolean)
}

/**
 * Auth contract (spec §8.2). Impl talks to Supabase Auth; session material is stored
 * encrypted (Keystore + Tink + DataStore, spec §9). Auth is OPTIONAL — the app is fully
 * usable as guest (constitution §1); sync/sharing simply stay off.
 */
interface AuthRepository {
    val sessionState: Flow<SessionState>

    /** False when the build has no Supabase endpoint configured — login UI degrades to guest-only. */
    val isConfigured: Boolean

    /** @throws com.elegen.elegencashbook.domain.model.AuthException on failure. */
    suspend fun signIn(email: String, password: String)

    /**
     * @return true when a session is active immediately (email confirmation disabled → auto-login);
     *   false when the project requires email confirmation before sign-in.
     * @throws com.elegen.elegencashbook.domain.model.AuthException on failure.
     */
    suspend fun register(email: String, password: String, displayName: String?, phone: String?): Boolean

    /** Clears session material; local Room data is retained (spec §8.2). Never throws on network failure. */
    suspend fun signOut()
}

/** Destructive local maintenance. Only invoked from the explicit "sign out & remove data" flow. */
interface LocalDataMaintenance {
    /** Wipes Room + preferences back to fresh-install state. */
    suspend fun wipeAll()
}

/**
 * Requests outbox drain / remote pull (spec §6.3, §6.4). Fire-and-forget: impls enqueue
 * network-constrained background workers, so callers never block on the network (offline-safe).
 * Kept as a pure interface so repositories (and their unit tests) don't depend on WorkManager.
 */
interface SyncScheduler {
    fun requestPush()

    /** One-off delta pull (spec §6.4) — called after login for initial hydration. */
    fun requestPull()

    /** Periodic delta poll for background/missed updates; call once at process start. */
    fun schedulePeriodicPull()

    /** Periodic tombstone purge (spec §6.6); call once at process start. */
    fun scheduleCleanup()
}
