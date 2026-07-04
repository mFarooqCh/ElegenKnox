package com.elegen.elegencashbook.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.model.AuthException
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.usecase.RegisterUser
import com.elegen.elegencashbook.domain.usecase.SignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import com.elegen.elegencashbook.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { SIGN_IN, REGISTER }

data class LoginUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val loading: Boolean = false,
    val error: String? = null,
    /** Non-error banner, e.g. "Account created — confirm your email". */
    val info: String? = null,
    /** Auth done (or guest chosen) — screen should close. */
    val done: Boolean = false,
    /** False when the build has no server endpoint — only guest mode available. */
    val serverConfigured: Boolean = true,
)

sealed interface LoginUiEvent {
    data object SwitchMode : LoginUiEvent
    data class Submit(
        val email: String,
        val password: String,
        val displayName: String,
        val phone: String,
    ) : LoginUiEvent
    data object ContinueAsGuest : LoginUiEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val signIn: SignIn,
    private val registerUser: RegisterUser,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(serverConfigured = authRepository.isConfigured))
    val state: StateFlow<LoginUiState> = _state

    fun onEvent(event: LoginUiEvent) {
        when (event) {
            LoginUiEvent.SwitchMode -> _state.value = _state.value.copy(
                mode = if (_state.value.mode == AuthMode.SIGN_IN) AuthMode.REGISTER else AuthMode.SIGN_IN,
                error = null,
                info = null,
            )
            LoginUiEvent.ContinueAsGuest -> viewModelScope.launch {
                settings.setGuestModeChosen(true)
                _state.value = _state.value.copy(done = true)
            }
            is LoginUiEvent.Submit -> submit(event)
        }
    }

    private fun submit(event: LoginUiEvent.Submit) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null, info = null)
        viewModelScope.launch {
            try {
                when (_state.value.mode) {
                    AuthMode.SIGN_IN -> {
                        signIn(event.email, event.password)
                        settings.setGuestModeChosen(true)
                        _state.value = _state.value.copy(loading = false, done = true)
                    }
                    AuthMode.REGISTER -> {
                        val autoLoggedIn = registerUser(event.email, event.password, event.displayName, event.phone)
                        settings.setGuestModeChosen(true)
                        if (autoLoggedIn) {
                            _state.value = _state.value.copy(loading = false, done = true)
                        } else {
                            // Email confirmation required — switch to sign-in and tell the user.
                            _state.value = _state.value.copy(
                                loading = false,
                                mode = AuthMode.SIGN_IN,
                                info = "Account created. Check your email to confirm, then sign in.",
                            )
                        }
                    }
                }
            } catch (e: AuthException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}
