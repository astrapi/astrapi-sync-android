package de.astrapi.sync.ui.pairing

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PairingUiState(
    val serverUrl: String = "",
    val pairingCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val paired: Boolean = false,
)

class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState

    fun onServerUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value, errorMessage = null)
    }

    fun onPairingCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(pairingCode = value, errorMessage = null)
    }

    fun pair() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.pairingCode.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Server-URL und Pairing-Code angeben")
            return
        }
        _uiState.value = state.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val label = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val result = ApiClient.pair(state.serverUrl, state.pairingCode, label, "android")
                app.securePrefs.apply {
                    serverUrl = state.serverUrl.trimEnd('/')
                    deviceToken = result.deviceToken
                    deviceId = result.deviceId
                    deviceLabel = label
                }
                _uiState.value = _uiState.value.copy(isLoading = false, paired = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Pairing fehlgeschlagen",
                )
            }
        }
    }
}
