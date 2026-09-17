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
    /** Farbkategorie wird beim Verbinden aus FolderInfo übernommen und
     * nicht mehr live nachgeladen -- wie description ändert sie sich also
     * erst wieder beim nächsten Verbinden desselben Ordners, falls sie sich
     * serverseitig ändert (gleiche, bereits bestehende Einschränkung). */
    val color: String? = null,
)

/** Ein von der Engine erkannter, noch nicht aufgelöster Konflikt (beide
 * Seiten seit dem letzten bekannten Stand geändert, siehe
 * SyncDecision.FileAction.Conflict). Anders als bisher wird der Konflikt
 * NICHT mehr automatisch aufgelöst (Server gewinnt, lokale Version als
 * .syncconflict-Kopie) -- die Datei bleibt unangetastet, bis der Nutzer in
 * der Konflikt-Liste explizit "meine Version" oder "Server-Version" wählt
 * (SyncEngine.resolveConflict()). Muss daher, anders als known_files,
 * über einen App-Neustart und mehrere Sync-Läufe hinweg bestehen bleiben. */
@Entity(tableName = "pending_conflicts", primaryKeys = ["folderId", "path"])
data class PendingConflictEntity(
    val folderId: String,
    val path: String,
    val localSha256: String,
    val localSize: Long,
    val remoteSha256: String,
    val remoteSize: Long,
    val detectedAt: Long,
)
