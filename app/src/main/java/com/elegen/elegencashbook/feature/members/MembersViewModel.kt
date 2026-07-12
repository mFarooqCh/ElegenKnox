package com.elegen.elegencashbook.feature.members

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.domain.model.BusinessMember
import com.elegen.elegencashbook.domain.model.MembershipStatus
import com.elegen.elegencashbook.domain.model.SessionState
import com.elegen.elegencashbook.domain.usecase.InviteToBusiness
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

data class MembersUiState(
    val members: List<MemberItem> = emptyList(),
    /** OWNER or ADMIN — UX-gating only, mirrors MEMBER_MANAGE (spec §8.3); server re-checks on every RPC. */
    val canManage: Boolean = false,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

sealed interface MembersUiEvent {
    data class Invite(val emailOrPhone: String, val role: BusinessRole) : MembersUiEvent
    data class ChangeRole(val targetUid: String, val role: BusinessRole) : MembersUiEvent
    data class Revoke(val targetUid: String) : MembersUiEvent
    data object ErrorShown : MembersUiEvent
}

@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listBusinessMembers: ListBusinessMembers,
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
            is MembersUiEvent.Invite -> runRpc { inviteToBusiness(businessId, event.emailOrPhone, event.role) }
            is MembersUiEvent.ChangeRole -> runRpc { updateMemberRole(businessId, event.targetUid, event.role) }
            is MembersUiEvent.Revoke -> runRpc { revokeMember(businessId, event.targetUid) }
            MembersUiEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private fun runRpc(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { load() }
                .onFailure { e -> _state.update { it.copy(errorMessage = e.message) } }
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
                    myUid to listBusinessMembers(businessId)
                }
                attempt++
                val (myUid, members) = outcome.getOrNull() ?: (null to null)
                val amMember = members?.any { it.userUid == myUid } == true
                if (amMember || attempt >= MAX_LOAD_ATTEMPTS) {
                    if (members != null) {
                        val myRole = members.firstOrNull { it.userUid == myUid && it.status == MembershipStatus.ACTIVE }?.role
                        _state.update {
                            it.copy(
                                members = members.map { m -> m.toItem() },
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
