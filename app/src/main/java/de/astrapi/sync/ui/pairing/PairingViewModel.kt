package de.astrapi.sync.ui.pairing

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.network.ApiClient
import de.astrapi.sync.network.PairingQrPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PairingUiState(
    val serverUrl: String = "",
    val pairingCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val paired: Boolean = false,
    val showScanner: Boolean = false,
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

    fun onScanQrClicked() {
        _uiState.value = _uiState.value.copy(showScanner = true, errorMessage = null)
    }

    fun onScanCancelled() {
        _uiState.value = _uiState.value.copy(showScanner = false)
    }

    /** Übernimmt Server-URL + Token aus dem gescannten QR-Code und stößt das
     * Koppeln direkt an -- der ganze Sinn des Scannens ist, dass danach
     * nichts mehr von Hand eingetippt/bestätigt werden muss. */
    fun onQrScanned(payload: PairingQrPayload) {
        _uiState.value = _uiState.value.copy(
            serverUrl = payload.serverUrl,
            pairingCode = payload.token,
            showScanner = false,
        )
        pair()
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
                app.scheduleBackgroundSync()
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
