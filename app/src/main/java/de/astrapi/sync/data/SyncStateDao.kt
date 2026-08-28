package de.astrapi.sync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    @Query("SELECT * FROM folder_bindings WHERE folderId = :folderId")
    suspend fun binding(folderId: String): FolderBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: FolderBindingEntity)

    @Query("DELETE FROM folder_bindings WHERE folderId = :folderId")
    suspend fun removeBinding(folderId: String)

    @Query("UPDATE folder_bindings SET lastSyncedAt = :timestamp WHERE folderId = :folderId")
    suspend fun updateLastSyncedAt(folderId: String, timestamp: Long)
}
