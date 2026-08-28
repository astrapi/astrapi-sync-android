package de.astrapi.sync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Kotlin/Room-Pendant zu astrapi_sync_cli/state.py's "files"-Dict
 * (rel_path -> {sha256, size}) -- pro Ordner (folderId), relationale
 * statt JSON-Blob-Speicherung. */
@Entity(tableName = "known_files", primaryKeys = ["folderId", "path"])
data class KnownFileEntity(
    val folderId: String,
    val path: String,
    val sha256: String,
    val size: Long,
)

/** Pendant zu state.py's "dirs"-Liste (bekannte, zuletzt leere
 * Verzeichnisse). */
@Entity(tableName = "known_dirs", primaryKeys = ["folderId", "path"])
data class KnownDirEntity(
    val folderId: String,
    val path: String,
)

/** Welcher Server-Ordner mit welchem lokalen SAF-Baum verbunden ist --
 * Pendant zu config.py's cfg["folders"] (folder_id -> local_path), nur
 * dass Android statt eines Dateisystem-Pfads eine SAF-Tree-URI
 * speichert (String-Serialisierung von Uri, siehe Uri.toString()/
 * Uri.parse()). Ändert sich die treeUri für einen Ordner, ist der
 * bisherige bekannte Zustand (known_files/known_dirs) für diesen Ordner
 * hinfällig -- exakt dieselbe Sicherheitsüberlegung wie in state.py's
 * load_state()-Docstring beschrieben (sonst könnten zufällig gleich
 * benannte Dateien im neuen Ordner fälschlich als "server-seitig
 * gelöscht" erkannt werden). */
@Entity(tableName = "folder_bindings")
data class FolderBindingEntity(
    @PrimaryKey val folderId: String,
    val treeUri: String,
    val description: String,
    /** Epoch-Millis des letzten erfolgreichen syncFolderOnce()-Laufs,
     * null solange noch nie synchronisiert -- persistiert (anders als
     * der nur session-lokale statusText in FolderUiItem), damit die
     * Karte auch nach App-Neustart zeigt, wie aktuell ein Ordner ist. */
    val lastSyncedAt: Long? = null,
)
