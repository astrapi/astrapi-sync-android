package de.astrapi.sync.sync

import de.astrapi.sync.data.KnownFileEntity
import de.astrapi.sync.network.FileEntry

/** Reine, Android-unabhängige Kernlogik des Drei-Wege-Vergleichs --
 * bewusst von SyncEngine (die DocumentFile/Context/Netzwerk-I/O
 * braucht) getrennt, damit sich genau diese Entscheidungslogik ohne
 * Android-Mocking als normaler JVM-Unit-Test prüfen lässt (siehe
 * SyncDecisionTest). Portiert engine.py::sync_folder_once()'s
 * if/elif-Kette 1:1, mit der T-215-SYNC-Korrektur: kein zusätzliches
 * "lastKnown != null" bei der Konflikterkennung -- ein Pfad, der beim
 * allerersten Sync (kein bekannter Stand) bereits auf beiden Seiten mit
 * unterschiedlichem Inhalt existiert, gilt hier ebenfalls als Konflikt
 * statt dass die lokale Version stillschweigend gewinnt. */
sealed class FileAction {
    /** Weder lokal noch remote vorhanden, nur noch im Verlaufsspeicher -> vergessen. */
    data object Forget : FileAction()

    /** Nur lokal vorhanden, unbekannt (oder seit Server-Löschung verändert) -> hochladen. */
    data object UploadNew : FileAction()

    /** Nur lokal vorhanden, Server hat sie (nachweislich) gelöscht -> lokal nachziehen. */
    data object DeleteLocal : FileAction()

    /** Nur remote vorhanden, unbekannt (oder seit lokaler Löschung verändert) -> herunterladen. */
    data object DownloadNew : FileAction()

    /** Nur remote vorhanden, lokal (nachweislich) gelöscht -> dem Server mitteilen. */
    data object DeleteRemote : FileAction()

    /** Beide Seiten identisch -> nur bekannten Zustand aktualisieren. */
    data object KeepInSync : FileAction()

    /** Beide Seiten seit dem letzten bekannten Stand geändert (oder gar
     * kein bekannter Stand, aber Inhalt unterschiedlich) -> Sicherungskopie
     * + Server-Version übernehmen. */
    data object Conflict : FileAction()

    /** Nur remote geändert -> herunterladen. */
    data object DownloadChanged : FileAction()

    /** Nur lokal geändert -> hochladen. */
    data object UploadChanged : FileAction()
}

object SyncDecision {
    fun decide(
        remote: FileEntry?,
        localExists: Boolean,
        localHash: String?,
        lastKnown: KnownFileEntity?,
    ): FileAction {
        if (remote == null && !localExists) return FileAction.Forget

        if (remote == null && localExists) {
            return if (lastKnown != null && localHash == lastKnown.sha256) {
                FileAction.DeleteLocal
            } else {
                FileAction.UploadNew
            }
        }

        if (remote != null && !localExists) {
            return if (lastKnown != null && lastKnown.sha256 == remote.sha256) {
                FileAction.DeleteRemote
            } else {
                FileAction.DownloadNew
            }
        }

        val r = remote!!
        if (localHash == r.sha256) return FileAction.KeepInSync

        val localChanged = lastKnown == null || lastKnown.sha256 != localHash
        val remoteChanged = lastKnown == null || lastKnown.sha256 != r.sha256

        if (localChanged && remoteChanged) return FileAction.Conflict
        if (remoteChanged && !localChanged) return FileAction.DownloadChanged
        return FileAction.UploadChanged
    }
}
