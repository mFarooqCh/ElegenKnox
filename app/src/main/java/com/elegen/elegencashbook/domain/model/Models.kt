package com.elegen.elegencashbook.domain.model

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.GrantAccess
import com.elegen.elegencashbook.core.permission.Permission

/** Domain models (spec §7). Pure Kotlin — no Room, no Supabase, no Android (spec §4 rule 1). */

data class Business(
    val id: String,
    val name: String,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BusinessOverview(
    val business: Business,
    val bookCount: Int,
)

data class Book(
    val id: String,
    /** null = personal book (spec §7); P2 books always belong to a business. */
    val businessId: String?,
    val name: String,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class BookWithBalance(
    val book: Book,
    val totalIn: Money,
    val totalOut: Money,
    val entryCount: Int,
    val lastEntryAt: Long?,
) {
    val net: Money get() = totalIn - totalOut
}

enum class EntryType { CASH_IN, CASH_OUT }

data class Transaction(
    val id: String,
    val bookId: String,
    val type: EntryType,
    val amount: Money,
    val description: String,
    /** User-facing entry date/time (picker-set), also the chronological sort key (spec §7 index). */
    val createdAt: Long,
    val updatedAt: Long,
)

data class BalanceSummary(
    val totalIn: Money,
    val totalOut: Money,
) {
    val net: Money get() = totalIn - totalOut
}

enum class HistoryEntityType { BOOK, TRANSACTION }

enum class HistoryAction { CREATED, UPDATED, RENAMED, MOVED, COPIED, DELETED, RESTORED, CONFLICT_OVERWRITTEN }

data class HistoryEntry(
    val id: String,
    val entityType: HistoryEntityType,
    val entityId: String,
    val bookId: String,
    val action: HistoryAction,
    /** "field=old→new;field2=old2→new2", null for CREATED/DELETED/RESTORED. */
    val changes: String?,
    val actorUid: String,
    val at: Long,
)

enum class MembershipStatus { ACTIVE, REVOKED }

/** One row of a business's member list (P7, spec §8.4) — email/displayName resolved server-side via list_business_members RPC, since the users table isn't otherwise selectable for anyone but yourself. */
data class BusinessMember(
    val userUid: String,
    val email: String,
    val displayName: String?,
    val role: BusinessRole,
    val status: MembershipStatus,
    val bookScoped: Boolean,
    val joinedAt: Long,
)

/** One row of a book's grant list (P7, spec §8.4) — via list_book_grants RPC. */
data class BookGrantInfo(
    val userUid: String,
    val email: String,
    val displayName: String?,
    val access: GrantAccess,
    val permsOverride: Set<Permission>?,
)

/** Thrown by [com.elegen.elegencashbook.domain.repository.SharingRepository] on any RPC failure — offline, forbidden, or a business-rule rejection (USER_NOT_REGISTERED, LAST_OWNER). */
class SharingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown by Book/TransactionRepositoryImpl when the caller's effective capability set (spec §8.3)
 * doesn't include what the mutation needs. Local-write-layer enforcement — RLS + the Postgres
 * enforce triggers are the real security boundary, but without this check a local write always
 * "succeeds" (offline-first: Room never refuses a write), then either silently vanishes on the
 * next pull (LWW loses to the untouched server row) or gets permanently stuck un-pushed in the
 * outbox — either way the user sees no error unless this throws early instead.
 */
class PermissionDeniedException(message: String = "You don't have permission to do that") : Exception(message)
