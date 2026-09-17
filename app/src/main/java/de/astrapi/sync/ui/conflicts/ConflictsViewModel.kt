package de.astrapi.sync.ui.conflicts

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ConflictUiItem(
    val folderId: String,
    val path: String,
    val folderDescription: String,
    val treeUri: Uri,
    val localSize: Long,
    val remoteSize: Long,
    val detectedAt: Long,
    val isResolving: Boolean = false,
    val errorMessage: String? = null,
)

data class ConflictsUiState(
    val isLoading: Boolean = true,
    val items: List<ConflictUiItem> = emptyList(),
)

/** Liste der pending_conflicts, angereichert mit Ordner-Metadaten (Name,
 * SAF-Baum) aus folder_bindings -- reaktiv über combine(), damit sowohl
 * ein manueller Sync als auch der Hintergrund-Worker sofort sichtbar
 * werden, während dieser Bildschirm offen ist (gleiches Muster wie
 * FolderListViewModel.observeBound()). */
class ConflictsViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(ConflictsUiState())
    val uiState: StateFlow<ConflictsUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(dao.pendingConflictsFlow(), dao.allBindingsFlow()) { conflicts, bindings ->
                val bindingsById = bindings.associateBy { it.folderId }
                val resolvingById = _uiState.value.items.associateBy { it.folderId to it.path }
                conflicts.mapNotNull { c ->
                    // Ordner kann zwischenzeitlich getrennt worden sein
                    // (Binding gelöscht) -- der nächste Sync-Lauf räumt
                    // pending_conflicts dann selbst auf, bis dahin einfach
                    // ausblenden statt mit fehlenden Metadaten anzuzeigen.
                    val binding = bindingsById[c.folderId] ?: return@mapNotNull null
                    val existing = resolvingById[c.folderId to c.path]
                    ConflictUiItem(
                        folderId = c.folderId,
                        path = c.path,
                        folderDescription = binding.description,
                        treeUri = Uri.parse(binding.treeUri),
                        localSize = c.localSize,
                        remoteSize = c.remoteSize,
                        detectedAt = c.detectedAt,
                        isResolving = existing?.isResolving ?: false,
                        errorMessage = existing?.errorMessage,
                    )
                }
            }.collectLatest { items ->
                _uiState.value = _uiState.value.copy(isLoading = false, items = items)
            }
        }
    }

    fun keepLocal(item: ConflictUiItem) = resolve(item, keepLocal = true)

    fun keepRemote(item: ConflictUiItem) = resolve(item, keepLocal = false)

    private fun resolve(item: ConflictUiItem, keepLocal: Boolean) {
        updateItem(item) { it.copy(isResolving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val engine = SyncEngine(app, app.apiClient(), dao)
                val label = app.securePrefs.deviceLabel.ifBlank { "android" }
                engine.resolveConflict(item.folderId, item.treeUri, label, item.path, keepLocal)
                // Kein manuelles Entfernen aus der Liste nötig -- resolveConflict()
                // löscht die Zeile aus pending_conflicts, der Flow oben bekommt
                // das automatisch mit.
            } catch (e: Exception) {
                updateItem(item) {
                    it.copy(isResolving = false, errorMessage = e.message ?: "Auflösung fehlgeschlagen")
                }
            }
        }
    }

    private fun updateItem(item: ConflictUiItem, transform: (ConflictUiItem) -> ConflictUiItem) {
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.map {
                if (it.folderId == item.folderId && it.path == item.path) transform(it) else it
            },
        )
    }
}
