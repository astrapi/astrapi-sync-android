package de.astrapi.sync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM known_files WHERE folderId = :folderId")
    suspend fun knownFiles(folderId: String): List<KnownFileEntity>

    @Query("SELECT * FROM known_dirs WHERE folderId = :folderId")
    suspend fun knownDirs(folderId: String): List<KnownDirEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<KnownFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDirs(dirs: List<KnownDirEntity>)

    @Query("DELETE FROM known_files WHERE folderId = :folderId")
    suspend fun clearKnownFiles(folderId: String)

    @Query("DELETE FROM known_dirs WHERE folderId = :folderId")
    suspend fun clearKnownDirs(folderId: String)

    /** Ersetzt den kompletten bekannten Zustand eines Ordners in einer
     * einzigen SQLite-Transaktion -- anders als state.py's
     * write_text()-basiertes Schreiben (siehe T-217-SYNC) ist das durch
     * SQLite von Haus aus atomar: ein Absturz mittendrin hinterlässt
     * entweder den alten ODER den neuen vollständigen Zustand, nie
     * einen kaputten Mischzustand. */
    @Transaction
    suspend fun replaceKnownState(
        folderId: String,
        files: List<KnownFileEntity>,
        dirs: List<KnownDirEntity>,
    ) {
        clearKnownFiles(folderId)
        clearKnownDirs(folderId)
        if (files.isNotEmpty()) upsertFiles(files)
        if (dirs.isNotEmpty()) upsertDirs(dirs)
    }

    @Query("SELECT * FROM folder_bindings")
    suspend fun allBindings(): List<FolderBindingEntity>

    /** Reaktiv statt einmalig -- Room feuert bei jeder Änderung an
     * folder_bindings neu, unabhängig davon, ob sie über einen manuellen
     * Sync, das Hinzufügen eines Ordners oder den periodischen
     * SyncWorker im Hintergrund passiert. Damit bleibt die Ordnerliste
     * aktuell, auch wenn der Hintergrund-Sync schreibt, während der
     * Bildschirm bereits offen ist. */
    @Query("SELECT * FROM folder_bindings")
    fun allBindingsFlow(): Flow<List<FolderBindingEntity>>

    @Query("SELECT * FROM folder_bindings WHERE folderId = :folderId")
    suspend fun binding(folderId: String): FolderBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: FolderBindingEntity)

    @Query("DELETE FROM folder_bindings WHERE folderId = :folderId")
    suspend fun removeBinding(folderId: String)

    @Query("DELETE FROM pending_conflicts WHERE folderId = :folderId")
    suspend fun clearPendingConflicts(folderId: String)

    /** Entfernt eine Bindung vollständig inkl. ihres gesamten bekannten
     * Sync-Zustands (bekannte Dateien/Verzeichnisse, offene Konflikte) --
     * ohne dies würden verwaiste known_files/known_dirs/pending_conflicts-
     * Zeilen mit derselben folderId zurückbleiben, falls derselbe
     * Server-Ordner später erneut verbunden wird (führte sonst zu falschen
     * "server-seitig gelöscht"-Erkennungen, siehe FolderBindingEntity-Doc
     * zu folderPath-Änderungen). Rührt bewusst NICHT an den lokalen Dateien
     * selbst -- nur die Sync-Kopplung wird aufgehoben. */
    @Transaction
    suspend fun removeBindingCompletely(folderId: String) {
        removeBinding(folderId)
        clearKnownFiles(folderId)
        clearKnownDirs(folderId)
        clearPendingConflicts(folderId)
    }

    @Query("UPDATE folder_bindings SET lastSyncedAt = :timestamp WHERE folderId = :folderId")
    suspend fun updateLastSyncedAt(folderId: String, timestamp: Long)

    @Query("SELECT * FROM pending_conflicts WHERE folderId = :folderId AND path = :path")
    suspend fun pendingConflict(folderId: String, path: String): PendingConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingConflict(conflict: PendingConflictEntity)

    @Query("DELETE FROM pending_conflicts WHERE folderId = :folderId AND path = :path")
    suspend fun deletePendingConflict(folderId: String, path: String)

    /** Für die Konflikt-Liste (ConflictsScreen) -- reaktiv aus demselben
     * Grund wie allBindingsFlow(): der Hintergrund-Sync kann neue
     * Konflikte anlegen, während der Bildschirm bereits offen ist. */
    @Query("SELECT * FROM pending_conflicts ORDER BY detectedAt DESC")
    fun pendingConflictsFlow(): Flow<List<PendingConflictEntity>>

    /** Für das Badge in der Ordnerliste -- eigener Flow statt Ableitung
     * aus pendingConflictsFlow(), damit die Liste selbst nicht überall
     * mitgeladen werden muss, wo nur die Zahl gebraucht wird. */
    @Query("SELECT COUNT(*) FROM pending_conflicts")
    fun pendingConflictCountFlow(): Flow<Int>

    /** Einmaliger Snapshot für die Notification-Texterstellung im
     * SyncWorker -- der läuft nicht auf dem UI-Thread und braucht keinen
     * dauerhaften Flow-Collector für einen einzelnen Zahlenwert. */
    @Query("SELECT COUNT(*) FROM pending_conflicts")
    suspend fun pendingConflictCount(): Int
}
