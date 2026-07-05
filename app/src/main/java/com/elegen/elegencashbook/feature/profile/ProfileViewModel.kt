package com.elegen.elegencashbook.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.usecase.ObserveSession
import com.elegen.elegencashbook.feature.main.AccountUi
import com.elegen.elegencashbook.feature.main.toAccountUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    observeSession: ObserveSession,
) : ViewModel() {
    val account: StateFlow<AccountUi> = observeSession()
        .map { it.toAccountUi() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUi(loggedIn = false, label = "Guest"))
}
