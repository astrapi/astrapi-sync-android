package de.astrapi.sync.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.network.SyncHistoryEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Deutsche Labels, 1:1 zu astrapi_sync/modules/folders/history.py's
 * _ACTION_LABELS -- Server und App zeigen bewusst denselben Wortlaut. */
private val ACTION_LABELS = mapOf(
    "uploaded" to "Hochgeladen",
    "downloaded" to "Heruntergeladen",
    "deleted_local" to "Lokal gelöscht",
    "deleted_remote" to "Auf Server gelöscht",
    "conflict" to "Konflikt",
)

data class HistoryUiItem(
    val folderId: String,
    val folderDescription: String,
    val path: String,
    val action: String,
    val deviceLabel: String,
    val syncedAt: String,
) {
    val actionLabel: String get() = ACTION_LABELS[action] ?: action
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val items: List<HistoryUiItem> = emptyList(),
)

/** Verlauf über ALLE auf diesem Gerät gebundenen Ordner hinweg (T-338-SYNC)
 * -- anders als Konflikte/Ordnerliste rein server-seitig gespeist statt aus
 * einer lokalen Room-Tabelle: der Server sieht ohnehin die Syncs aller
 * Geräte für einen Ordner (nicht nur dieses), und nach den Room-Migrations-
 * Problemen aus T-336-SYNC ist eine zusätzliche lokale Tabelle nur für
 * Anzeigezwecke nicht gerechtfertigt. Serverausfall bedeutet daher: kein
 * Verlauf statt eines nur-lokalen Teilausschnitts. */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val bindings = dao.allBindingsFlow().first()
                val client = app.apiClient()
                val perFolder = bindings.map { binding ->
                    async {
                        binding to runCatching { client.getHistory(binding.folderId) }.getOrDefault(emptyList())
                    }
                }.awaitAll()

                val items = perFolder.flatMap { (binding, entries) ->
                    entries.map { it.toUiItem(binding.folderId, binding.description) }
                }.sortedByDescending { it.syncedAt }

                _uiState.value = HistoryUiState(isLoading = false, items = items)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Verlauf konnte nicht geladen werden",
                )
            }
        }
    }
}

private fun SyncHistoryEntry.toUiItem(folderId: String, folderDescription: String) = HistoryUiItem(
    folderId = folderId,
    folderDescription = folderDescription,
    path = path,
    action = action,
    deviceLabel = deviceLabel,
    syncedAt = syncedAt,
)
