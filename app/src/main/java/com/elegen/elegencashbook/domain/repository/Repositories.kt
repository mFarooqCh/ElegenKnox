package com.elegen.elegencashbook.domain.repository

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.GrantAccess
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.domain.model.Book
import com.elegen.elegencashbook.domain.model.BookGrantInfo
import com.elegen.elegencashbook.domain.model.BusinessMember
import com.elegen.elegencashbook.domain.model.BookWithBalance
import com.elegen.elegencashbook.domain.model.Business
import com.elegen.elegencashbook.domain.model.BusinessOverview
import com.elegen.elegencashbook.domain.model.EntryType
import com.elegen.elegencashbook.domain.model.HistoryEntityType
import com.elegen.elegencashbook.domain.model.HistoryEntry
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
    suspend fun rename(businessId: String, name: String)
    /** Soft delete: sets tombstone, business (and its books, on the next observe) disappears from observe flows (spec §6.6). */
    suspend fun softDelete(businessId: String)
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

/** Read-only edit-history trail (edit-history feature). Writes are internal to Book/TransactionRepositoryImpl — they already own the mutation's transaction boundary. */
interface HistoryRepository {
    fun observeForEntity(entityType: HistoryEntityType, entityId: String): Flow<List<HistoryEntry>>
}

/**
 * Read-only UX permission cache (P6, spec §8.3). Writes to business_members/book_grants are
 * RPC-only (data/remote), never through this interface — this only resolves the caller's own
 * effective capability set on a book, for enabling/disabling buttons AND for Book/TransactionRepositoryImpl's
 * pre-write local guard (offline-first: Room never refuses a write on its own, so without this
 * check a denied mutation "succeeds" locally and either silently disappears on the next pull or
 * gets stuck un-pushed forever — see [com.elegen.elegencashbook.domain.model.PermissionDeniedException]).
 * Never trust this for security; RLS + the Postgres enforce triggers are the real gate.
 */
interface PermissionRepository {
    fun observeEffectivePermissions(bookId: String): Flow<Set<Permission>>

    /** One-shot variant for a repository's own pre-write check (no need to collect a Flow for a single read). */
    suspend fun effectivePermissions(bookId: String): Set<Permission>

    /**
     * Caller's own ACTIVE role in a business, straight from the local mirror — display only (e.g.
     * the business switcher's role badge), never for gating an action (that mirror can lag a pull
     * cycle behind; see [effectiveBusinessCapabilities] and [effectivePermissions], which both bake
     * in an owner fallback for exactly that reason). Null while the mirror hasn't caught up yet or
     * the caller isn't a member — callers should render that as blank, not default to a role.
     */
    suspend fun myBusinessRole(businessId: String): BusinessRole?

    /**
     * Business-level capabilities for actions not yet tied to an existing book (e.g. creating a
     * new one). Falls back to full access when the caller is the business's own raw owner and the
     * local business_members mirror hasn't caught up yet — same pull-cycle-lag class of bug
     * already hit (and fixed) in the sharing UI; a business's own creator must never be locked out
     * of their own just-created business by a mirror that hasn't synced yet.
     */
    suspend fun effectiveBusinessCapabilities(businessId: String): Set<Permission>
}

/**
 * Sharing/collaboration writes (P7, spec §8.4) — every function here is a network RPC call;
 * there is no local/offline path (constitution: sharing requires online, no fake success). Reads
 * (member/grant lists) are one-shot suspend calls too, not cached Flows — the member list is a
 * small, occasionally-viewed screen, not something that needs continuous Room-backed reactivity.
 * @throws com.elegen.elegencashbook.domain.model.SharingException on any failure (offline, forbidden, business-rule rejection).
 */
interface SharingRepository {
    suspend fun inviteToBusiness(
        businessId: String,
        emailOrPhone: String,
        role: BusinessRole,
        bookScope: List<String>? = null,
        perBookPerms: Set<Permission>? = null,
    ): String

    suspend fun shareBook(bookId: String, emailOrPhone: String, perms: Set<Permission>? = null): String
    suspend fun updateMemberRole(businessId: String, targetUid: String, role: BusinessRole)
    suspend fun setBookGrant(bookId: String, targetUid: String, access: GrantAccess, permsOverride: Set<Permission>? = null)
    suspend fun revokeMember(businessId: String, targetUid: String)
    suspend fun revokeBookGrant(bookId: String, targetUid: String)
    suspend fun listBusinessMembers(businessId: String): List<BusinessMember>
    suspend fun listBookGrants(bookId: String): List<BookGrantInfo>
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

    /**
     * Sends a password-reset email carrying a 6-digit recovery code (spec: no magic-link tap,
     * the code is entered back into the app by [resetPasswordWithCode]).
     * @throws com.elegen.elegencashbook.domain.model.AuthException on failure.
     */
    suspend fun requestPasswordReset(email: String)

    /**
     * Verifies the recovery code from [requestPasswordReset]'s email (this signs the user in —
     * verifying a recovery OTP establishes a session by design) and immediately sets the new
     * password on that session, so the caller lands authenticated with the new password already live.
     * @throws com.elegen.elegencashbook.domain.model.AuthException on invalid/expired code or failure.
     */
    suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String)

    /**
     * Sets a new password on the currently active session (no separate reauth — the session
     * itself proves identity, same as the Supabase dashboard/JS client behavior).
     * @throws com.elegen.elegencashbook.domain.model.AuthException on failure or no active session.
     */
    suspend fun updatePassword(newPassword: String)

    /**
     * Signs in (or auto-provisions on first use) via a Google OIDC ID token obtained from
     * Credential Manager. Covers both sign-up and sign-in — Supabase creates the user on first call.
     * @throws com.elegen.elegencashbook.domain.model.AuthException on failure.
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String)
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

    /** True while a requested pull is enqueued/running — drives pull-to-refresh spinners. */
    fun observePullActive(): Flow<Boolean>
}
