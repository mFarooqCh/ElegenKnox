package com.elegen.elegencashbook.feature.business

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.usecase.CreateBusiness
import com.elegen.elegencashbook.domain.usecase.SwitchBusiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddBusinessUiState(
    val saving: Boolean = false,
    val done: Boolean = false,
)

sealed interface AddBusinessUiEvent {
    data class Create(val name: String) : AddBusinessUiEvent
}

@HiltViewModel
class AddBusinessViewModel @Inject constructor(
    private val createBusiness: CreateBusiness,
    private val switchBusiness: SwitchBusiness,
) : ViewModel() {

    private val _state = MutableStateFlow(AddBusinessUiState())
    val state: StateFlow<AddBusinessUiState> = _state

    fun onEvent(event: AddBusinessUiEvent) {
        when (event) {
            is AddBusinessUiEvent.Create -> {
                if (_state.value.saving || event.name.isBlank()) return
                _state.value = _state.value.copy(saving = true)
                viewModelScope.launch {
                    val business = createBusiness(event.name)
                    switchBusiness(business.id) // new business becomes the active one
                    _state.value = AddBusinessUiState(saving = false, done = true)
                }
            }
        }
    }
}
