package de.astrapi.sync.ui.folders

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.data.FolderBindingEntity
import de.astrapi.sync.network.FolderInfo
import de.astrapi.sync.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FolderUiItem(
    val folderId: String,
    val description: String,
    val boundUri: Uri,
    /** Epoch-Millis, persistiert -- siehe FolderBindingEntity.lastSyncedAt. */
    val lastSyncedAt: Long? = null,
    /** Nur session-lokales Ergebnis des letzten manuellen Sync-Laufs
     * ("3 hoch, 2 runter" o.ä.), im Gegensatz zu lastSyncedAt nicht
     * persistiert -- verschwindet bewusst wieder nach App-Neustart. */
    val statusText: String? = null,
    val isSyncing: Boolean = false,
)

data class FolderListUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val folders: List<FolderUiItem> = emptyList(),
    val showAddSheet: Boolean = false,
    val isLoadingAvailable: Boolean = false,
    val availableFolders: List<FolderInfo> = emptyList(),
    val addErrorMessage: String? = null,
)

class FolderListViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(FolderListUiState())
    val uiState: StateFlow<FolderListUiState> = _uiState

    init {
        loadBound()
    }

    /** Nur lokal bereits verbundene Ordner -- kein Server-Aufruf nötig,
     * die Beschreibung wurde beim Verbinden schon mitgespeichert. Zeigt
     * bewusst nichts an, bevor der Nutzer aktiv über "+" einen Ordner
     * hinzugefügt hat, statt wie zuvor sofort alle freigegebenen Ordner
     * der Reihe nach aufzulisten. */
    fun loadBound() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val bindings = dao.allBindings()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    folders = bindings.map { b ->
                        FolderUiItem(
                            folderId = b.folderId,
                            description = b.description,
                            boundUri = Uri.parse(b.treeUri),
                            lastSyncedAt = b.lastSyncedAt,
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

    /** Lädt die Server-Liste und blendet bereits verbundene Ordner aus --
     * einzige Stelle, die noch alle freigegebenen Ordner auf einmal
     * abfragt, jetzt aber gezielt hinter dem "+"-Button statt automatisch
     * beim Start. */
    fun onAddClicked() {
        _uiState.value = _uiState.value.copy(
            showAddSheet = true,
            isLoadingAvailable = true,
            addErrorMessage = null,
        )
        viewModelScope.launch {
            try {
                val remote = app.apiClient().listFolders()
                val boundIds = _uiState.value.folders.map { it.folderId }.toSet()
                _uiState.value = _uiState.value.copy(
                    isLoadingAvailable = false,
                    availableFolders = remote.filter { it.id !in boundIds },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingAvailable = false,
                    addErrorMessage = e.message ?: "Ordnerliste konnte nicht geladen werden",
                )
            }
        }
    }

    fun onAddDismissed() {
        _uiState.value = _uiState.value.copy(
            showAddSheet = false,
            availableFolders = emptyList(),
            addErrorMessage = null,
        )
    }

    /** Persistierbare Zugriffsberechtigung muss der Aufrufer (Compose-
     * Layer, hat den ActivityResult-Callback) bereits über
     * ContentResolver.takePersistableUriPermission() gesichert haben,
     * bevor diese Funktion aufgerufen wird -- sonst überlebt die
     * Berechtigung keinen App-/Geräte-Neustart. */
    fun bindFolder(folderId: String, description: String, treeUri: Uri) {
        viewModelScope.launch {
            dao.upsertBinding(FolderBindingEntity(folderId, treeUri.toString(), description))
            _uiState.value = _uiState.value.copy(showAddSheet = false, availableFolders = emptyList())
            loadBound()
        }
    }

    fun syncNow(folderId: String) {
        val item = _uiState.value.folders.find { it.folderId == folderId } ?: return
        updateItem(folderId) { it.copy(isSyncing = true, statusText = null) }
        viewModelScope.launch {
            try {
                val engine = SyncEngine(app, app.apiClient(), dao)
                val label = app.securePrefs.deviceLabel.ifBlank { "android" }
                val result = engine.syncFolderOnce(folderId, item.boundUri, label)
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
                val now = if (result.aborted) null else System.currentTimeMillis()
                if (now != null) dao.updateLastSyncedAt(folderId, now)
                updateItem(folderId) {
                    it.copy(
                        isSyncing = false,
                        statusText = text,
                        lastSyncedAt = now ?: it.lastSyncedAt,
                    )
                }
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
