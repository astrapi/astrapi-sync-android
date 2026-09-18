package de.astrapi.sync.ui.folders

import android.app.Application
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.data.FolderBindingEntity
import de.astrapi.sync.network.FolderInfo
import de.astrapi.sync.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderUiItem(
    val folderId: String,
    val description: String,
    val boundPath: String,
    /** Epoch-Millis, persistiert -- siehe FolderBindingEntity.lastSyncedAt. */
    val lastSyncedAt: Long? = null,
    /** Nur session-lokales Ergebnis des letzten manuellen Sync-Laufs
     * ("3 hoch, 2 runter" o.ä.), im Gegensatz zu lastSyncedAt nicht
     * persistiert -- verschwindet bewusst wieder nach App-Neustart. */
    val statusText: String? = null,
    val isSyncing: Boolean = false,
    val color: String? = null,
)

/** Von der Engine abgebrochener Lauf (MAX_AUTO_DELETE überschritten, siehe
 * SyncEngine.MAX_AUTO_DELETE / T-203-SYNC) -- hält die betroffenen Pfade,
 * damit der Dialog sie anzeigen kann, statt nur die Gesamtzahl im
 * statusText. */
data class PendingDeleteConfirmation(
    val folderId: String,
    val wouldDeleteLocal: List<String>,
    val wouldDeleteRemote: List<String>,
)

data class FolderListUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val folders: List<FolderUiItem> = emptyList(),
    val showAddSheet: Boolean = false,
    val isLoadingAvailable: Boolean = false,
    val availableFolders: List<FolderInfo> = emptyList(),
    val addErrorMessage: String? = null,
    val pendingDeleteConfirmation: PendingDeleteConfirmation? = null,
)

class FolderListViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()
    private val dao get() = app.database.syncStateDao()

    private val _uiState = MutableStateFlow(FolderListUiState())
    val uiState: StateFlow<FolderListUiState> = _uiState

    /** Für das Warn-Badge neben dem Einstellungen-Icon -- eigener,
     * schlanker Flow statt Teil von uiState, da er unabhängig von der
     * Ordnerliste selbst aktuell bleiben muss (Konflikte können jederzeit
     * durch den Hintergrund-Worker dazukommen). */
    val conflictCount: StateFlow<Int> = dao.pendingConflictCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        observeBound()
    }

    /** Nur lokal bereits verbundene Ordner -- kein Server-Aufruf nötig,
     * die Beschreibung wurde beim Verbinden schon mitgespeichert. Zeigt
     * bewusst nichts an, bevor der Nutzer aktiv über "+" einen Ordner
     * hinzugefügt hat, statt wie zuvor sofort alle freigegebenen Ordner
     * der Reihe nach aufzulisten.
     *
     * Reaktiv (Flow statt einmaligem suspend-Aufruf) -- der periodische
     * SyncWorker im Hintergrund schreibt in dieselbe Tabelle, während der
     * Bildschirm schon offen sein kann. Ohne Beobachtung bliebe die
     * Karte nach einem Hintergrund-Sync auf einem veralteten Stand
     * stehen, bis der Bildschirm neu geöffnet wird. session-lokale
     * Felder (statusText/isSyncing) bleiben beim Zusammenführen mit
     * jeder neuen Emission erhalten, da sie nicht aus der DB kommen. */
    private fun observeBound() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                dao.allBindingsFlow().collectLatest { bindings ->
                    val existingById = _uiState.value.folders.associateBy { it.folderId }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        folders = bindings.map { b ->
                            val existing = existingById[b.folderId]
                            FolderUiItem(
                                folderId = b.folderId,
                                description = b.description,
                                boundPath = b.folderPath,
                                lastSyncedAt = b.lastSyncedAt,
                                statusText = existing?.statusText,
                                isSyncing = existing?.isSyncing ?: false,
                                color = b.color,
                            )
                        },
                    )
                }
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

    /** Seit T-340-SYNC keine SAF-Persistierung mehr nötig --
     * MANAGE_EXTERNAL_STORAGE gilt app-weit und überlebt App-/Geräte-
     * Neustarts ohne separate Freigabe pro Ordner. Der Aufrufer (Compose-
     * Layer) muss die Berechtigung vor dem Aufruf lediglich erteilt
     * haben (siehe FolderListScreen). */
    fun bindFolder(folder: FolderInfo, path: File) {
        viewModelScope.launch {
            dao.upsertBinding(
                FolderBindingEntity(
                    folderId = folder.id,
                    folderPath = path.absolutePath,
                    description = folder.description,
                    color = folder.color,
                ),
            )
            // Kein manuelles Neuladen nötig -- observeBound() bekommt den
            // neuen Ordner automatisch über den Flow mit.
            _uiState.value = _uiState.value.copy(showAddSheet = false, availableFolders = emptyList())
        }
    }

    fun syncNow(folderId: String) = runSync(folderId, confirmDeletes = false)

    /** Nutzer hat im Dialog "Trotzdem löschen" bestätigt -- Lauf mit
     * denselben Pfaden wiederholen, diesmal mit confirmDeletes=true, damit
     * die Engine die zuvor geplanten Löschungen tatsächlich ausführt. */
    fun confirmPendingDeletions() {
        val pending = _uiState.value.pendingDeleteConfirmation ?: return
        _uiState.value = _uiState.value.copy(pendingDeleteConfirmation = null)
        runSync(pending.folderId, confirmDeletes = true)
    }

    fun dismissPendingDeletions() {
        _uiState.value = _uiState.value.copy(pendingDeleteConfirmation = null)
    }

    private fun runSync(folderId: String, confirmDeletes: Boolean) {
        val item = _uiState.value.folders.find { it.folderId == folderId } ?: return
        updateItem(folderId) { it.copy(isSyncing = true, statusText = null) }
        viewModelScope.launch {
            try {
                val engine = SyncEngine(app.apiClient(), dao)
                val label = app.securePrefs.deviceLabel.ifBlank { "android" }
                val result = engine.syncFolderOnce(folderId, File(item.boundPath), label, confirmDeletes = confirmDeletes)
                val total = result.uploaded.size + result.downloaded.size +
                    result.deletedLocal.size + result.deletedRemote.size
                val text = when {
                    result.aborted -> "Abgebrochen: ${result.reason}"
                    // Ein Lauf, der NUR einen Konflikt findet und sonst
                    // nichts überträgt, ist trotzdem kein "bereits
                    // aktuell" -- der Nutzer muss ihn noch auflösen.
                    total == 0 && result.conflicts.isEmpty() -> "Bereits aktuell"
                    total == 0 -> "${result.conflicts.size} Konflikt(e) offen"
                    else -> "${result.uploaded.size} hoch, ${result.downloaded.size} runter, " +
                        "${result.deletedLocal.size + result.deletedRemote.size} gelöscht" +
                        // Anders als früher werden Konflikte nicht mehr
                        // automatisch aufgelöst -- daher der Hinweis, dass
                        // eine Entscheidung des Nutzers aussteht (siehe
                        // Konflikt-Liste hinter dem Warn-Badge).
                        if (result.conflicts.isNotEmpty()) ", ${result.conflicts.size} Konflikt(e) offen" else ""
                }
                // lastSyncedAt heisst "letzter erfolgreicher Lauf", nicht
                // "letzter Lauf mit echter Änderung" (siehe Entities.kt-Doc
                // zu FolderBindingEntity.lastSyncedAt) -- die Activity-Log-
                // Definition des Servers (kein Eintrag bei total == 0,
                // sync.py::log_sync_summary(), T-212-SYNC) betrifft nur den
                // Verlauf, nicht diese Anzeige. Wurde hier bisher fälschlich
                // übernommen: ein folgenloser, aber erfolgreicher Check
                // ("Bereits aktuell") liess lastSyncedAt auf null stehen, was
                // nach App-Neustart (statusText ist session-lokal und dann
                // wieder null) fälschlich als "Noch nie synchronisiert"
                // angezeigt wurde.
                val now = if (!result.aborted) System.currentTimeMillis() else null
                if (now != null) dao.updateLastSyncedAt(folderId, now)
                updateItem(folderId) {
                    it.copy(
                        isSyncing = false,
                        statusText = text,
                        lastSyncedAt = now ?: it.lastSyncedAt,
                    )
                }
                _uiState.value = _uiState.value.copy(
                    pendingDeleteConfirmation = if (result.aborted) {
                        PendingDeleteConfirmation(folderId, result.wouldDeleteLocal, result.wouldDeleteRemote)
                    } else {
                        null
                    },
                )
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
