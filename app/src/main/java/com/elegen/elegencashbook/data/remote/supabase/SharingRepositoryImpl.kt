package com.elegen.elegencashbook.data.remote.supabase

import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.GrantAccess
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.domain.model.BookGrantInfo
import com.elegen.elegencashbook.domain.model.BusinessMember
import com.elegen.elegencashbook.domain.model.MembershipStatus
import com.elegen.elegencashbook.domain.model.SharingException
import com.elegen.elegencashbook.domain.repository.SharingRepository
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sharing writes/reads (P7, spec §8.4) — every call here goes straight to the SECURITY DEFINER
 * RPCs from `supabase/migrations/20260712000001_p6_rbac.sql` / `..._p7_list_rpcs.sql`. No local
 * Room path at all: sharing requires online (constitution), and there's nothing to cache safely
 * — the server is the sole source of truth for who has access to what.
 */
@Singleton
class SharingRepositoryImpl @Inject constructor(
    private val holder: SupabaseClientHolder,
) : SharingRepository {

    override suspend fun inviteToBusiness(
        businessId: String,
        emailOrPhone: String,
        role: BusinessRole,
        bookScope: List<String>?,
        perBookPerms: Set<Permission>?,
    ): String = call {
        val client = requireClient()
        val result = client.postgrest.rpc(
            "invite_to_business",
            buildJsonObject {
                put("p_business_id", businessId)
                put("p_email_or_phone", emailOrPhone)
                put("p_role", role.name)
                bookScope?.let { put("p_book_scope", JsonArray(it.map(::JsonPrimitive))) }
                perBookPerms?.let { put("p_per_book_perms", JsonArray(it.map { p -> JsonPrimitive(p.name) })) }
            },
        )
        result.decodeAs<String>()
    }

    override suspend fun shareBook(bookId: String, emailOrPhone: String, perms: Set<Permission>?): String = call {
        val client = requireClient()
        val result = client.postgrest.rpc(
            "share_book",
            buildJsonObject {
                put("p_book_id", bookId)
                put("p_email_or_phone", emailOrPhone)
                perms?.let { put("p_perms", JsonArray(it.map { p -> JsonPrimitive(p.name) })) }
            },
        )
        result.decodeAs<String>()
    }

    override suspend fun updateMemberRole(businessId: String, targetUid: String, role: BusinessRole) {
        call {
            requireClient().postgrest.rpc(
                "update_member_role",
                buildJsonObject {
                    put("p_business_id", businessId)
                    put("p_target_uid", targetUid)
                    put("p_role", role.name)
                },
            )
        }
    }

    override suspend fun setBookGrant(bookId: String, targetUid: String, access: GrantAccess, permsOverride: Set<Permission>?) {
        call {
            requireClient().postgrest.rpc(
                "set_book_grant",
                buildJsonObject {
                    put("p_book_id", bookId)
                    put("p_target_uid", targetUid)
                    put("p_access", access.name)
                    permsOverride?.let { put("p_perms_override", JsonArray(it.map { p -> JsonPrimitive(p.name) })) }
                },
            )
        }
    }

    override suspend fun revokeMember(businessId: String, targetUid: String) {
        call {
            requireClient().postgrest.rpc(
                "revoke_member",
                buildJsonObject {
                    put("p_business_id", businessId)
                    put("p_target_uid", targetUid)
                },
            )
        }
    }

    override suspend fun revokeBookGrant(bookId: String, targetUid: String) {
        call {
            requireClient().postgrest.rpc(
                "revoke_book_grant",
                buildJsonObject {
                    put("p_book_id", bookId)
                    put("p_target_uid", targetUid)
                },
            )
        }
    }

    override suspend fun listBusinessMembers(businessId: String): List<BusinessMember> = call {
        val client = requireClient()
        val result = client.postgrest.rpc("list_business_members", buildJsonObject { put("p_business_id", businessId) })
        result.decodeList<JsonObject>().map { it.toBusinessMember() }
    }

    override suspend fun listBookGrants(bookId: String): List<BookGrantInfo> = call {
        val client = requireClient()
        val result = client.postgrest.rpc("list_book_grants", buildJsonObject { put("p_book_id", bookId) })
        result.decodeList<JsonObject>().map { it.toBookGrantInfo() }
    }

    private fun requireClient() = holder.client ?: throw SharingException("Server not configured — sharing needs an account and a connection")

    /** Every RPC path funnels its exceptions through the same friendly-message mapping. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: PostgrestRestException) {
        throw SharingException(friendlyMessage(e), e)
    } catch (e: HttpRequestException) {
        throw SharingException("Cannot reach server — check your connection", e)
    }

    private fun friendlyMessage(e: PostgrestRestException): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("USER_NOT_REGISTERED") -> "This person must install and register first"
            raw.contains("LAST_OWNER") -> "A business must always keep at least one owner"
            raw.contains("FORBIDDEN") -> "You don't have permission to do that"
            raw.contains("INVALID_ROLE") -> "Not a valid role"
            raw.contains("INVALID_ACCESS") -> "Not a valid access value"
            raw.contains("BOOK_NOT_FOUND") -> "Book not found"
            else -> "Action failed — please try again"
        }
    }
}

private fun JsonObject.toBusinessMember() = BusinessMember(
    userUid = getValue("user_uid").jsonPrimitive.content,
    email = getValue("email").jsonPrimitive.content,
    displayName = get("display_name")?.jsonPrimitive?.contentOrNull,
    role = BusinessRole.valueOf(getValue("role").jsonPrimitive.content),
    status = MembershipStatus.valueOf(getValue("status").jsonPrimitive.content),
    bookScoped = getValue("book_scoped").jsonPrimitive.content.toBoolean(),
    joinedAt = parseTimestamp(getValue("joined_at").jsonPrimitive.content),
)

private fun JsonObject.toBookGrantInfo() = BookGrantInfo(
    userUid = getValue("user_uid").jsonPrimitive.content,
    email = getValue("email").jsonPrimitive.content,
    displayName = get("display_name")?.jsonPrimitive?.contentOrNull,
    access = GrantAccess.valueOf(getValue("access").jsonPrimitive.content),
    permsOverride = get("perms_override")?.takeIf { it != JsonNull }?.jsonArray
        ?.mapNotNull { runCatching { Permission.valueOf(it.jsonPrimitive.content) }.getOrNull() }?.toSet(),
)
