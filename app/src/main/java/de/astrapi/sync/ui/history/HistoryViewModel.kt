package de.astrapi.sync.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.network.SyncHistoryEntry
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
    "dir_created_local" to "Ordner lokal angelegt",
    "dir_created_remote" to "Ordner auf Server angelegt",
    "dir_deleted_local" to "Ordner lokal gelöscht",
    "dir_deleted_remote" to "Ordner auf Server gelöscht",
)

data class HistoryUiItem(
    val path: String,
    val action: String,
    val deviceLabel: String,
    val syncedAt: String,
) {
    val actionLabel: String get() = ACTION_LABELS[action] ?: action
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val folderDescription: String = "",
    val errorMessage: String? = null,
    val items: List<HistoryUiItem> = emptyList(),
)

/** Verlauf EINES einzelnen Ordners (T-338-SYNC, pro Ordner statt einer
 * globalen Liste über alle gebundenen Ordner -- siehe Nutzer-Feedback
 * 2026-09-17: die frühere geräteweite Ansicht wirkte leer, solange nicht
 * jeder Ordner tatsächlich synchronisiert wurde, was verwirrender war als
 * hilfreich). Server-seitig gespeist statt aus einer lokalen Room-Tabelle:
 * der Server sieht ohnehin die Syncs aller Geräte für einen Ordner (nicht
 * nur dieses), und nach den Room-Migrations-Problemen aus T-336-SYNC ist
 * eine zusätzliche lokale Tabelle nur für Anzeigezwecke nicht gerechtfertigt.
 * Serverausfall bedeutet daher: kein Verlauf statt eines nur-lokalen
 * Teilausschnitts. */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState

    fun refresh(folderId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val description = dao.allBindingsFlow().first()
                    .firstOrNull { it.folderId == folderId }?.description ?: ""
                val items = app.apiClient().getHistory(folderId)
                    .map { it.toUiItem() }
                    .sortedByDescending { it.syncedAt }

                _uiState.value = HistoryUiState(
                    isLoading = false,
                    folderDescription = description,
                    items = items,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Verlauf konnte nicht geladen werden",
                )
            }
        }
    }
}

private fun SyncHistoryEntry.toUiItem() = HistoryUiItem(
    path = path,
    action = action,
    deviceLabel = deviceLabel,
    syncedAt = syncedAt,
)
