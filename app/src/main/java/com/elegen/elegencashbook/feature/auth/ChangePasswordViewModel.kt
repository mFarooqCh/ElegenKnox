package com.elegen.elegencashbook.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.model.AuthException
import com.elegen.elegencashbook.domain.usecase.ChangePassword
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangePasswordUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

sealed interface ChangePasswordUiEvent {
    data class Submit(val newPassword: String, val confirmPassword: String) : ChangePasswordUiEvent
    data object Reset : ChangePasswordUiEvent
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val changePassword: ChangePassword,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state

    fun onEvent(event: ChangePasswordUiEvent) {
        when (event) {
            is ChangePasswordUiEvent.Submit -> submit(event)
            ChangePasswordUiEvent.Reset -> _state.value = ChangePasswordUiState()
        }
    }

    private fun submit(event: ChangePasswordUiEvent.Submit) {
        if (_state.value.loading) return
        if (event.newPassword != event.confirmPassword) {
            _state.value = _state.value.copy(error = "Passwords don't match")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                changePassword(event.newPassword)
                _state.value = _state.value.copy(loading = false, done = true)
            } catch (e: AuthException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}
