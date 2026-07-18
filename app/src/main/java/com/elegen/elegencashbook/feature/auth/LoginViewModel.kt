package com.elegen.elegencashbook.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.model.AuthException
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.usecase.RegisterUser
import com.elegen.elegencashbook.domain.usecase.RequestPasswordReset
import com.elegen.elegencashbook.domain.usecase.ResetPasswordWithCode
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
    /** Separate from [loading] so the "Forgot password" dialog's own spinner doesn't bleed into the sign-in button. */
    val resetLoading: Boolean = false,
    /** True once the reset code email was sent — dialog switches from "enter email" to "enter code + new password". */
    val resetCodeSent: Boolean = false,
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
    data class ForgotPassword(val email: String) : LoginUiEvent
    data class ResetPasswordWithCode(
        val email: String,
        val code: String,
        val newPassword: String,
        val confirmPassword: String,
    ) : LoginUiEvent
    /** Dialog closed/cancelled — clear its transient state so reopening starts fresh. */
    data object ResetFlowDismissed : LoginUiEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val signIn: SignIn,
    private val registerUser: RegisterUser,
    private val requestPasswordReset: RequestPasswordReset,
    private val resetPasswordWithCode: ResetPasswordWithCode,
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
            is LoginUiEvent.ForgotPassword -> forgotPassword(event)
            is LoginUiEvent.ResetPasswordWithCode -> submitResetWithCode(event)
            LoginUiEvent.ResetFlowDismissed -> _state.value = _state.value.copy(
                resetLoading = false, resetCodeSent = false, error = null, info = null,
            )
        }
    }

    private fun forgotPassword(event: LoginUiEvent.ForgotPassword) {
        if (_state.value.resetLoading) return
        _state.value = _state.value.copy(resetLoading = true, error = null, info = null)
        viewModelScope.launch {
            try {
                requestPasswordReset(event.email)
                _state.value = _state.value.copy(
                    resetLoading = false,
                    resetCodeSent = true,
                    info = "Code sent — check your email",
                )
            } catch (e: AuthException) {
                _state.value = _state.value.copy(resetLoading = false, error = e.message)
            }
        }
    }

    private fun submitResetWithCode(event: LoginUiEvent.ResetPasswordWithCode) {
        if (_state.value.resetLoading) return
        if (event.newPassword != event.confirmPassword) {
            _state.value = _state.value.copy(error = "Passwords don't match")
            return
        }
        _state.value = _state.value.copy(resetLoading = true, error = null, info = null)
        viewModelScope.launch {
            try {
                resetPasswordWithCode(event.email, event.code, event.newPassword)
                settings.setGuestModeChosen(true)
                _state.value = _state.value.copy(resetLoading = false, done = true)
            } catch (e: AuthException) {
                _state.value = _state.value.copy(resetLoading = false, error = e.message)
            }
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
