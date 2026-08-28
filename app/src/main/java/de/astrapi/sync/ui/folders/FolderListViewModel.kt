package de.astrapi.sync.ui.folders

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.data.FolderBindingEntity
import de.astrapi.sync.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FolderUiItem(
    val folderId: String,
    val description: String,
    val boundUri: Uri? = null,
    val statusText: String? = null,
    val isSyncing: Boolean = false,
)

data class FolderListUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val folders: List<FolderUiItem> = emptyList(),
)

class FolderListViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(FolderListUiState())
    val uiState: StateFlow<FolderListUiState> = _uiState

    init {
        load()
    }

    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val remoteFolders = app.apiClient().listFolders()
                val bindings = dao.allBindings().associateBy { it.folderId }
                _uiState.value = FolderListUiState(
                    isLoading = false,
                    folders = remoteFolders.map { f ->
                        val binding = bindings[f.id]
                        FolderUiItem(
                            folderId = f.id,
                            description = f.description,
                            boundUri = binding?.treeUri?.let { Uri.parse(it) },
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Ordnerliste konnte nicht geladen werden",
                )
            }
        }
    }

    /** Persistierbare Zugriffsberechtigung muss der Aufrufer (Compose-
     * Layer, hat den ActivityResult-Callback) bereits über
     * ContentResolver.takePersistableUriPermission() gesichert haben,
     * bevor diese Funktion aufgerufen wird -- sonst überlebt die
     * Berechtigung keinen App-/Geräte-Neustart. */
    fun bindFolder(folderId: String, description: String, treeUri: Uri) {
        viewModelScope.launch {
            dao.upsertBinding(FolderBindingEntity(folderId, treeUri.toString(), description))
            load()
        }
    }

    fun syncNow(folderId: String) {
        val item = _uiState.value.folders.find { it.folderId == folderId } ?: return
        val uri = item.boundUri ?: return
        updateItem(folderId) { it.copy(isSyncing = true, statusText = null) }
        viewModelScope.launch {
            try {
                val engine = SyncEngine(app, app.apiClient(), dao)
                val label = app.securePrefs.deviceLabel.ifBlank { "android" }
                val result = engine.syncFolderOnce(folderId, uri, label)
                val text = when {
                    result.aborted -> "Abgebrochen: ${result.reason}"
                    else -> {
                        val total = result.uploaded.size + result.downloaded.size +
                            result.deletedLocal.size + result.deletedRemote.size
                        if (total == 0) "Bereits aktuell"
                        else "${result.uploaded.size} hoch, ${result.downloaded.size} runter, " +
                            "${result.deletedLocal.size + result.deletedRemote.size} gelöscht" +
                            if (result.conflicts.isNotEmpty()) ", ${result.conflicts.size} Konflikt(e)" else ""
                    }
                }
                updateItem(folderId) { it.copy(isSyncing = false, statusText = text) }
            } catch (e: Exception) {
                updateItem(folderId) { it.copy(isSyncing = false, statusText = "Fehler: ${e.message}") }
            }
        }
    }

    private fun updateItem(folderId: String, transform: (FolderUiItem) -> FolderUiItem) {
        _uiState.value = _uiState.value.copy(
            folders = _uiState.value.folders.map { if (it.folderId == folderId) transform(it) else it },
        )
    }
}
