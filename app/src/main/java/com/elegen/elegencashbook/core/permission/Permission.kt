package com.elegen.elegencashbook.core.permission

/**
 * Two-level capability model (spec §8.3). Business role is the coarse default bundle;
 * per-book grants override it. Mirrors the Postgres `effective_perms(book_id)` SQL function
 * in `supabase/migrations/20260712000001_p6_rbac.sql` exactly — this is the client-side UX-gating
 * copy only (buttons enable/disable), never the security boundary. RLS + the enforce triggers are
 * server-authoritative; this resolver must never be trusted to allow/deny an actual write.
 */
enum class Permission {
    BOOK_VIEW, TX_ADD, TX_EDIT, TX_DELETE,
    BOOK_ADD, BOOK_EDIT, BOOK_DELETE,
    MEMBER_MANAGE, BUSINESS_EDIT, BUSINESS_DELETE,
}

enum class BusinessRole {
    OWNER, ADMIN, VIEWER;

    /** Default capability bundle for this role (spec §8.3 table), before any per-book override. */
    fun defaults(): Set<Permission> = when (this) {
        OWNER -> Permission.entries.toSet()
        ADMIN -> setOf(
            Permission.BOOK_VIEW, Permission.TX_ADD, Permission.TX_EDIT, Permission.TX_DELETE,
            Permission.BOOK_ADD, Permission.BOOK_EDIT, Permission.BOOK_DELETE, Permission.MEMBER_MANAGE,
        )
        VIEWER -> setOf(Permission.BOOK_VIEW)
    }
}

enum class GrantAccess { ALLOW, DENY }

/** Minimal shape [PermissionResolver.effective] needs from a book — not the full domain [com.elegen.elegencashbook.domain.model.Book]. */
data class PermissionBook(val businessId: String?, val ownerUid: String)

/** Minimal shape of the caller's own ACTIVE membership row, if any. */
data class PermissionMembership(val role: BusinessRole, val bookScoped: Boolean)

/** Minimal shape of the caller's own live (non-tombstoned) grant on a book, if any. */
data class PermissionGrant(val access: GrantAccess, val permsOverride: Set<Permission>?)

/**
 * Pure resolver — no Room/Android/Supabase imports (constitution: domain/core stay pure Kotlin).
 * Mirrors the `effective(user, book)` pseudocode (spec §8.3) and the Postgres `effective_perms`
 * function line for line, so a behavior change here should be made in both places.
 */
object PermissionResolver {
    fun effective(
        currentUid: String,
        book: PermissionBook,
        membership: PermissionMembership?,
        grant: PermissionGrant?,
    ): Set<Permission> {
        if (book.businessId == null) {
            // personal / individual book: access comes solely from the grant (owner has all)
            if (grant?.access == GrantAccess.DENY) return emptySet()
            grant?.permsOverride?.let { return it }
            return if (book.ownerUid == currentUid) Permission.entries.toSet() else emptySet()
        }

        if (membership == null) return emptySet()
        if (grant?.access == GrantAccess.DENY) return emptySet()

        // "only these books": any scoped member (not just ADMIN — a VIEWER invited to a single
        // book via share_book is scoped too) needs an explicit ALLOW grant on this book, or they'd
        // see every other book in the business through their role defaults instead.
        if (membership.bookScoped && grant?.access != GrantAccess.ALLOW) {
            return emptySet()
        }

        return grant?.permsOverride ?: membership.role.defaults()
    }
}
