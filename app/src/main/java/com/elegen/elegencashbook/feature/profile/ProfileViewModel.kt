package com.elegen.elegencashbook.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.repository.SettingsRepository
import com.elegen.elegencashbook.domain.usecase.ObserveSession
import com.elegen.elegencashbook.domain.usecase.SignOut
import com.elegen.elegencashbook.feature.main.AccountUi
import com.elegen.elegencashbook.feature.main.toAccountUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    observeSession: ObserveSession,
    private val signOut: SignOut,
    private val settings: SettingsRepository,
) : ViewModel() {
    val account: StateFlow<AccountUi> = observeSession()
        .map { it.toAccountUi() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUi(loggedIn = false, label = "Guest"))

    private val _signedOut = MutableSharedFlow<Unit>()
    val signedOut: SharedFlow<Unit> = _signedOut

    fun onLogout() {
        viewModelScope.launch {
            signOut()
            settings.clearActiveBusinessId()
            settings.setGuestModeChosen(true)
            _signedOut.emit(Unit)
        }
    }
}
