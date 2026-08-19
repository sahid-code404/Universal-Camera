package com.omnicam.feature.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omnicam.camera.capability.CameraCapabilityScanner
import com.omnicam.core.model.DeviceCameraProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CameraDiagnosticsUiState {
    data object Idle : CameraDiagnosticsUiState
    data object Scanning : CameraDiagnosticsUiState
    data class Ready(val profile: DeviceCameraProfile) : CameraDiagnosticsUiState
    data class Error(val message: String) : CameraDiagnosticsUiState
}

class CameraDiagnosticsViewModel(
    private val scanner: CameraCapabilityScanner,
) : ViewModel() {
    private val _state = MutableStateFlow<CameraDiagnosticsUiState>(CameraDiagnosticsUiState.Idle)
    val state: StateFlow<CameraDiagnosticsUiState> = _state.asStateFlow()

    fun scan(force: Boolean = false) {
        if (!force && _state.value is CameraDiagnosticsUiState.Ready) return
        if (_state.value is CameraDiagnosticsUiState.Scanning) return

        viewModelScope.launch {
            _state.value = CameraDiagnosticsUiState.Scanning
            _state.value = runCatching { scanner.scan() }
                .fold(
                    onSuccess = CameraDiagnosticsUiState::Ready,
                    onFailure = { error ->
                        CameraDiagnosticsUiState.Error(error.message ?: error::class.java.simpleName)
                    },
                )
        }
    }

    class Factory(
        private val scanner: CameraCapabilityScanner,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CameraDiagnosticsViewModel::class.java))
            return CameraDiagnosticsViewModel(scanner) as T
        }
    }
}
