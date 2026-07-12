package com.elegen.elegencashbook.core.permission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Table-driven mirror of the pgTAP suite for `effective_perms()` (spec §13) — same expectations,
 * checked against the pure Kotlin resolver instead of Postgres. Keep both in sync.
 */
class PermissionResolverTest {

    private val bizBook = PermissionBook(businessId = "biz1", ownerUid = "owner-uid")
    private val personalBook = PermissionBook(businessId = null, ownerUid = "owner-uid")

    @Test
    fun `non-member of the book's business is denied everything`() {
        val result = PermissionResolver.effective("stranger", bizBook, membership = null, grant = null)
        assertEquals(emptySet<Permission>(), result)
    }

    @Test
    fun `OWNER gets every permission`() {
        val result = PermissionResolver.effective(
            "owner-uid", bizBook,
            membership = PermissionMembership(BusinessRole.OWNER, bookScoped = false),
            grant = null,
        )
        assertEquals(Permission.entries.toSet(), result)
    }

    @Test
    fun `ADMIN gets all but BUSINESS_EDIT and BUSINESS_DELETE`() {
        val result = PermissionResolver.effective(
            "admin-uid", bizBook,
            membership = PermissionMembership(BusinessRole.ADMIN, bookScoped = false),
            grant = null,
        )
        assertEquals(BusinessRole.ADMIN.defaults(), result)
        assert(Permission.BUSINESS_EDIT !in result)
        assert(Permission.BUSINESS_DELETE !in result)
    }

    @Test
    fun `VIEWER gets BOOK_VIEW only, no write caps`() {
        val result = PermissionResolver.effective(
            "viewer-uid", bizBook,
            membership = PermissionMembership(BusinessRole.VIEWER, bookScoped = false),
            grant = null,
        )
        assertEquals(setOf(Permission.BOOK_VIEW), result)
        assert(Permission.TX_ADD !in result)
    }

    @Test
    fun `DENY grant hides the book even from a member with a role`() {
        val result = PermissionResolver.effective(
            "viewer-uid", bizBook,
            membership = PermissionMembership(BusinessRole.VIEWER, bookScoped = false),
            grant = PermissionGrant(GrantAccess.DENY, permsOverride = null),
        )
        assertEquals(emptySet<Permission>(), result)
    }

    @Test
    fun `scoped ADMIN without an ALLOW grant on this book is denied (allow-list)`() {
        val result = PermissionResolver.effective(
            "admin-uid", bizBook,
            membership = PermissionMembership(BusinessRole.ADMIN, bookScoped = true),
            grant = null,
        )
        assertEquals(emptySet<Permission>(), result)
    }

    @Test
    fun `scoped ADMIN with an ALLOW grant on this book gets role defaults`() {
        val result = PermissionResolver.effective(
            "admin-uid", bizBook,
            membership = PermissionMembership(BusinessRole.ADMIN, bookScoped = true),
            grant = PermissionGrant(GrantAccess.ALLOW, permsOverride = null),
        )
        assertEquals(BusinessRole.ADMIN.defaults(), result)
    }

    @Test
    fun `scoped VIEWER without an ALLOW grant on this book is denied (real bug - share_book creates a scoped VIEWER)`() {
        val result = PermissionResolver.effective(
            "viewer-uid", bizBook,
            membership = PermissionMembership(BusinessRole.VIEWER, bookScoped = true),
            grant = null,
        )
        assertEquals(emptySet<Permission>(), result)
    }

    @Test
    fun `scoped VIEWER with an ALLOW grant on this book gets BOOK_VIEW only`() {
        val result = PermissionResolver.effective(
            "viewer-uid", bizBook,
            membership = PermissionMembership(BusinessRole.VIEWER, bookScoped = true),
            grant = PermissionGrant(GrantAccess.ALLOW, permsOverride = null),
        )
        assertEquals(setOf(Permission.BOOK_VIEW), result)
    }

    @Test
    fun `per-book perms_override replaces role defaults entirely`() {
        val custom = setOf(Permission.BOOK_VIEW, Permission.TX_ADD)
        val result = PermissionResolver.effective(
            "admin-uid", bizBook,
            membership = PermissionMembership(BusinessRole.ADMIN, bookScoped = false),
            grant = PermissionGrant(GrantAccess.ALLOW, permsOverride = custom),
        )
        assertEquals(custom, result)
    }

    @Test
    fun `unscoped ADMIN needs no grant row at all`() {
        val result = PermissionResolver.effective(
            "admin-uid", bizBook,
            membership = PermissionMembership(BusinessRole.ADMIN, bookScoped = false),
            grant = null,
        )
        assertEquals(BusinessRole.ADMIN.defaults(), result)
    }

    @Test
    fun `personal book owner gets everything with no grant row`() {
        val result = PermissionResolver.effective("owner-uid", personalBook, membership = null, grant = null)
        assertEquals(Permission.entries.toSet(), result)
    }

    @Test
    fun `personal book non-owner with no grant is denied`() {
        val result = PermissionResolver.effective("stranger", personalBook, membership = null, grant = null)
        assertEquals(emptySet<Permission>(), result)
    }

    @Test
    fun `personal book shared to a non-owner via share_book grant gets that grant's perms`() {
        val shared = setOf(Permission.BOOK_VIEW, Permission.TX_ADD, Permission.TX_EDIT)
        val result = PermissionResolver.effective(
            "friend-uid", personalBook,
            membership = null,
            grant = PermissionGrant(GrantAccess.ALLOW, permsOverride = shared),
        )
        assertEquals(shared, result)
    }

    @Test
    fun `personal book owner can still be DENYed on their own grant row (defensive symmetry)`() {
        val result = PermissionResolver.effective(
            "owner-uid", personalBook,
            membership = null,
            grant = PermissionGrant(GrantAccess.DENY, permsOverride = null),
        )
        assertEquals(emptySet<Permission>(), result)
    }
}
