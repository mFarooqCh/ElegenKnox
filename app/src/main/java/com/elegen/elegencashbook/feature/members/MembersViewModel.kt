package com.elegen.elegencashbook.feature.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.domain.model.BusinessMember
import com.elegen.elegencashbook.domain.model.MembershipStatus
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.usecase.InviteToBusiness
import com.elegen.elegencashbook.domain.usecase.ListBooks
import com.elegen.elegencashbook.domain.usecase.ListBusinessMembers
import com.elegen.elegencashbook.domain.usecase.ObserveSession
import com.elegen.elegencashbook.domain.usecase.RevokeMember
import com.elegen.elegencashbook.domain.usecase.UpdateMemberRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemberItem(
    val userUid: String,
    val emailOrName: String,
    val roleLabel: String,
    val role: BusinessRole,
    val isRevoked: Boolean,
)

data class InviteBookOption(val id: String, val name: String)

data class MembersUiState(
    val members: List<MemberItem> = emptyList(),
    /** This business's books, for the invite sheet's "which books" picker. */
    val books: List<InviteBookOption> = emptyList(),
    /** OWNER or ADMIN — UX-gating only, mirrors MEMBER_MANAGE (spec §8.3); server re-checks on every RPC. */
    val canManage: Boolean = false,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

sealed interface MembersUiEvent {
    /** [bookIds] null = full access to every book in the business; empty/non-null = scoped to just those. */
    data class Invite(val emailOrPhone: String, val role: BusinessRole, val bookIds: List<String>? = null) : MembersUiEvent
    data class ChangeRole(val targetUid: String, val role: BusinessRole) : MembersUiEvent
    data class Revoke(val targetUid: String) : MembersUiEvent
    data object ErrorShown : MembersUiEvent
}

@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listBusinessMembers: ListBusinessMembers,
    private val listBooks: ListBooks,
    private val inviteToBusiness: InviteToBusiness,
    private val updateMemberRole: UpdateMemberRole,
    private val revokeMember: RevokeMember,
    private val observeSession: ObserveSession,
) : ViewModel() {

    private val businessId: String = savedStateHandle["business_id"] ?: ""

    private val _state = MutableStateFlow(MembersUiState())
    val state: StateFlow<MembersUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: MembersUiEvent) {
        when (event) {
            is MembersUiEvent.Invite -> runRpc(retryOnBookNotFound = event.bookIds != null) {
                inviteToBusiness(businessId, event.emailOrPhone, event.role, event.bookIds)
            }
            is MembersUiEvent.ChangeRole -> runRpc { updateMemberRole(businessId, event.targetUid, event.role) }
            is MembersUiEvent.Revoke -> runRpc { revokeMember(businessId, event.targetUid) }
            MembersUiEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /**
     * [retryOnBookNotFound]: inviting with a book scope can hit `invite_to_business`'s
     * BOOK_NOT_FOUND if a picked book's own CREATE outbox row hasn't pushed yet — same mirror-lag
     * class as [load]'s retry, but for a book referenced by id rather than the caller's own
     * membership row. Real bug found on-device: inviting with 1 of 2 books selected threw a raw
     * FK-violation-turned-"Action failed" error (server-side gap fixed in
     * `20260714000002_p8_fix_invite_book_scope_fk.sql`; this is the client-side retry half).
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
        if (businessId.isBlank()) {
            _state.update { it.copy(loading = false, errorMessage = "No business selected") }
            return
        }
        viewModelScope.launch {
            // A just-created business hasn't pushed to the server yet, so the RPC below can 403
            // or come back without the owner's own (server-trigger-created) membership row for a
            // few seconds. Retry with backoff instead of surfacing a stale "no permission" error
            // that never clears until the user manually re-opens the screen.
            var attempt = 0
            while (true) {
                _state.update { it.copy(loading = true) }
                val outcome = runCatching {
                    // Skip the transient Loading state (session restore from Keystore right after
                    // a cold start) — comparing against a null uid would waste a whole retry.
                    val session = observeSession().first { it !is SessionState.Loading }
                    val myUid = (session as? SessionState.LoggedIn)?.user?.id
                    Triple(myUid, listBusinessMembers(businessId), listBooks(businessId).first())
                }
                attempt++
                val (myUid, members, books) = outcome.getOrNull() ?: Triple(null, null, null)
                val amMember = members?.any { it.userUid == myUid } == true
                if (amMember || attempt >= MAX_LOAD_ATTEMPTS) {
                    if (members != null) {
                        val myRole = members.firstOrNull { it.userUid == myUid && it.status == MembershipStatus.ACTIVE }?.role
                        _state.update {
                            it.copy(
                                members = members.map { m -> m.toItem() },
                                books = books.orEmpty().map { InviteBookOption(it.book.id, it.book.name) },
                                canManage = myRole == BusinessRole.OWNER || myRole == BusinessRole.ADMIN,
                                loading = false,
                            )
                        }
                    } else {
                        _state.update { it.copy(loading = false, errorMessage = outcome.exceptionOrNull()?.message ?: "Failed to load members") }
                    }
                    break
                }
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun BusinessMember.toItem() = MemberItem(
        userUid = userUid,
        emailOrName = displayName?.takeIf { it.isNotBlank() } ?: email,
        roleLabel = role.name.lowercase().replaceFirstChar { it.uppercase() },
        role = role,
        isRevoked = status == MembershipStatus.REVOKED,
    )

    private companion object {
        const val MAX_LOAD_ATTEMPTS = 6
        const val RETRY_DELAY_MS = 1_500L
    }
}
