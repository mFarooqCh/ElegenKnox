package com.elegen.elegencashbook.feature.business

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elegen.elegencashbook.domain.usecase.DeleteBusiness
import com.elegen.elegencashbook.domain.usecase.RenameBusiness
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BusinessSettingsUiState(
    val businessName: String = "",
    val deleted: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface BusinessSettingsUiEvent {
    data class Rename(val name: String) : BusinessSettingsUiEvent
    data object Delete : BusinessSettingsUiEvent
    data object ErrorShown : BusinessSettingsUiEvent
}

@HiltViewModel
class BusinessSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val renameBusiness: RenameBusiness,
    private val deleteBusiness: DeleteBusiness,
) : ViewModel() {

    private val businessId: String = savedStateHandle["business_id"] ?: ""
    private val _state = MutableStateFlow(
        BusinessSettingsUiState(businessName = savedStateHandle["business_name"] ?: "")
    )
    val state: StateFlow<BusinessSettingsUiState> = _state

    fun onEvent(event: BusinessSettingsUiEvent) {
        when (event) {
            is BusinessSettingsUiEvent.Rename -> viewModelScope.launch {
                runCatching { renameBusiness(businessId, event.name) }
                    .onSuccess { _state.value = _state.value.copy(businessName = event.name.trim()) }
                    .onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            }
            BusinessSettingsUiEvent.Delete -> viewModelScope.launch {
                runCatching { deleteBusiness(businessId) }
                    .onSuccess { _state.value = _state.value.copy(deleted = true) }
                    .onFailure { e -> _state.value = _state.value.copy(errorMessage = e.message) }
            }
            BusinessSettingsUiEvent.ErrorShown -> _state.value = _state.value.copy(errorMessage = null)
        }
    }
}
