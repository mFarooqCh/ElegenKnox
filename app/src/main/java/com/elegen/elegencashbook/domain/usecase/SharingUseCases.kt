package com.elegen.elegencashbook.domain.usecase

import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.GrantAccess
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.domain.model.BookGrantInfo
import com.elegen.elegencashbook.domain.model.BusinessMember
import com.elegen.elegencashbook.domain.model.SharingException
import com.elegen.elegencashbook.domain.repository.SharingRepository
import javax.inject.Inject

/** Sharing/collaboration use cases (P7, spec §8.4). All network-only — see [SharingRepository]. */

/** Same normalization spec §8.1 requires everywhere else (email lowercased/trimmed, phone E.164) — an unparsable address fails fast client-side instead of round-tripping to the server first. */
internal fun normalizeEmailOrPhone(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.contains('@')) return normalizeEmail(trimmed) ?: throw SharingException("Enter a valid email")
    return normalizePhone(trimmed) ?: throw SharingException("Enter a valid email or phone number")
}

class InviteToBusiness @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(
        businessId: String,
        emailOrPhone: String,
        role: BusinessRole,
        bookScope: List<String>? = null,
        perBookPerms: Set<Permission>? = null,
    ): String = repo.inviteToBusiness(businessId, normalizeEmailOrPhone(emailOrPhone), role, bookScope, perBookPerms)
}

class ShareBook @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(bookId: String, emailOrPhone: String, perms: Set<Permission>? = null): String =
        repo.shareBook(bookId, normalizeEmailOrPhone(emailOrPhone), perms)
}

class UpdateMemberRole @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(businessId: String, targetUid: String, role: BusinessRole) =
        repo.updateMemberRole(businessId, targetUid, role)
}

class SetBookGrant @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(bookId: String, targetUid: String, access: GrantAccess, permsOverride: Set<Permission>? = null) =
        repo.setBookGrant(bookId, targetUid, access, permsOverride)
}

class RevokeMember @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(businessId: String, targetUid: String) = repo.revokeMember(businessId, targetUid)
}

class RevokeBookGrant @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(bookId: String, targetUid: String) = repo.revokeBookGrant(bookId, targetUid)
}

class ListBusinessMembers @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(businessId: String): List<BusinessMember> = repo.listBusinessMembers(businessId)
}

class ListBookGrants @Inject constructor(private val repo: SharingRepository) {
    suspend operator fun invoke(bookId: String): List<BookGrantInfo> = repo.listBookGrants(bookId)
}
