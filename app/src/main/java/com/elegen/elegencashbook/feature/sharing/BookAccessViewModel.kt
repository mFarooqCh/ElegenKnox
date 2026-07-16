package com.elegen.elegencashbook.feature.sharing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.core.permission.GrantAccess
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.domain.model.BookGrantInfo
import com.elegen.elegencashbook.domain.model.MembershipStatus
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.usecase.GetEffectivePermissions
import com.elegen.elegencashbook.domain.usecase.ListBookGrants
import com.elegen.elegencashbook.domain.usecase.ListBusinessMembers
import com.elegen.elegencashbook.domain.usecase.ObserveSession
import com.elegen.elegencashbook.domain.usecase.RevokeBookGrant
import com.elegen.elegencashbook.domain.usecase.ShareBook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GrantItem(
    val userUid: String,
    val emailOrName: String,
    val accessLabel: String,
    val access: GrantAccess,
)

data class BookAccessUiState(
    val grants: List<GrantItem> = emptyList(),
    /** MEMBER_MANAGE, UX-gating only (spec §8.3) — server re-checks on every RPC. */
    val canManage: Boolean = false,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

sealed interface BookAccessUiEvent {
    data class Share(val emailOrPhone: String, val perms: Set<Permission>) : BookAccessUiEvent
    data class Revoke(val targetUid: String) : BookAccessUiEvent
    data object ErrorShown : BookAccessUiEvent
}

@HiltViewModel
class BookAccessViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listBookGrants: ListBookGrants,
    private val listBusinessMembers: ListBusinessMembers,
    private val shareBook: ShareBook,
    private val revokeBookGrant: RevokeBookGrant,
    private val observeSession: ObserveSession,
    getEffectivePermissions: GetEffectivePermissions,
) : ViewModel() {

    private val bookId: String = savedStateHandle["book_id"] ?: ""
    /** null for a personal book — those gate purely on the local owner_uid check below, which is instant. */
    private val businessId: String? = savedStateHandle["business_id"]

    private val _state = MutableStateFlow(BookAccessUiState())
    val state: StateFlow<BookAccessUiState> = _state.asStateFlow()

    init {
        // Personal-book owner check: instant, local, never lagged (book.ownerUid is known the
        // moment the book itself exists locally — no pull-cycle dependency).
        viewModelScope.launch {
            getEffectivePermissions(bookId).collect { perms ->
                if (Permission.MEMBER_MANAGE in perms) _state.update { it.copy(canManage = true) }
            }
        }
        load()
    }

    fun onEvent(event: BookAccessUiEvent) {
        when (event) {
            is BookAccessUiEvent.Share -> runRpc(retryOnBookNotFound = true) { shareBook(bookId, event.emailOrPhone, event.perms) }
            is BookAccessUiEvent.Revoke -> runRpc { revokeBookGrant(bookId, event.targetUid) }
            BookAccessUiEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /**
     * [retryOnBookNotFound]: sharing a just-created book can hit `share_book`'s BOOK_NOT_FOUND
     * before this device's own CREATE outbox row has pushed — same mirror-lag class as [load]'s
     * retry, but here it's the book itself missing server-side, not a membership row. Real bug
     * found on-device: "no book found" sharing from a fresh book's Book Access screen.
     */
    private fun runRpc(retryOnBookNotFound: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            var attempt = 0
            while (true) {
                attempt++
                val result = runCatching { block() }
                val error = result.exceptionOrNull()
                if (error != null && retryOnBookNotFound && error.message == "Book not found" && attempt < MAX_LOAD_ATTEMPTS) {
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                }
                result.onSuccess { load() }.onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
                break
            }
        }
    }

    private fun load() {
        if (bookId.isBlank()) {
            _state.update { it.copy(loading = false, errorMessage = "No book selected") }
            return
        }
        viewModelScope.launch {
            // A just-created business/book hasn't pushed to the server yet, so these RPCs can
            // 403 or come back without my own (server-trigger-created) membership row for a few
            // seconds. Retry with backoff instead of surfacing a stale "no permission" error that
            // never clears until the user manually re-opens the screen.
            var attempt = 0
            while (true) {
                _state.update { it.copy(loading = true) }
                val outcome = runCatching {
                    val grants = listBookGrants(bookId)
                    // Skip the transient Loading state (session restore from Keystore right after
                    // a cold start) — comparing against a null uid would waste a whole retry.
                    val myUid = businessId?.let { (observeSession().first { s -> s !is SessionState.Loading } as? SessionState.LoggedIn)?.user?.id }
                    // Business-book manage check, derived from the same two network calls this
                    // screen already needs — not the local business_members mirror, which only
                    // gets populated after a pull cycle (same bug class as MembersViewModel's fix).
                    val members = businessId?.let { listBusinessMembers(it) }
                    val myRole = members?.firstOrNull { it.userUid == myUid && it.status == MembershipStatus.ACTIVE }?.role
                    val myGrantDenied = grants.firstOrNull { it.userUid == myUid }?.access == GrantAccess.DENY
                    val networkCanManage = businessId == null || ((myRole == BusinessRole.OWNER || myRole == BusinessRole.ADMIN) && !myGrantDenied)
                    val amMember = businessId == null || members?.any { it.userUid == myUid } == true
                    Triple(grants, networkCanManage, amMember)
                }
                attempt++
                val (grants, networkCanManage, amMember) = outcome.getOrNull() ?: Triple(null, false, false)
                if (amMember || attempt >= MAX_LOAD_ATTEMPTS) {
                    if (grants != null) {
                        _state.update {
                            it.copy(
                                grants = grants.map { g -> g.toItem() },
                                canManage = it.canManage || networkCanManage,
                                loading = false,
                            )
                        }
                    } else {
                        _state.update { it.copy(loading = false, errorMessage = outcome.exceptionOrNull()?.message ?: "Failed to load access list") }
                    }
                    break
                }
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun BookGrantInfo.toItem() = GrantItem(
        userUid = userUid,
        emailOrName = displayName?.takeIf { it.isNotBlank() } ?: email,
        accessLabel = when {
            access == GrantAccess.DENY -> "Denied"
            permsOverride == null -> "Full access"
            Permission.TX_ADD in permsOverride || Permission.TX_EDIT in permsOverride -> "Editor"
            else -> "Viewer"
        },
        access = access,
    )

    private companion object {
        const val MAX_LOAD_ATTEMPTS = 6
        const val RETRY_DELAY_MS = 1_500L
    }
}
