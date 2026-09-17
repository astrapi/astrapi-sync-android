package de.astrapi.sync.sync

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.astrapi.sync.SyncApp

/** Periodischer Hintergrund-Sync aller lokal gebundenen Ordner.
 *
 * Bewusst kein Dauer-Service/Dauer-WebSocket (wie beim CLI-Client) --
 * WorkManager ist der von Google empfohlene Standardweg für
 * wiederkehrende Hintergrundarbeit, respektiert Doze/
 * Batterieoptimierung von selbst und braucht keine Dauer-Benachrichtigung
 * (anders als ein Foreground-Service). Serverseitige Änderungen werden
 * dadurch erst mit der periodischen Verzögerung sichtbar, nicht
 * in Echtzeit -- akzeptierter Kompromiss, siehe astrapi-hub-Vault
 * (T-256-SYNC).
 *
 * Ein einzelner fehlschlagender Ordner darf die anderen nicht
 * verhindern -- gleiche Überlegung wie beim Python-Client (T-216-SYNC),
 * hier pro Ordner statt pro Datei. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SyncApp
        if (!app.securePrefs.isPaired) return Result.success()

        val dao = app.database.syncStateDao()
        val bindings = dao.allBindings()
        if (bindings.isEmpty()) return Result.success()

        val engine = SyncEngine(app, app.apiClient(), dao)
        val label = app.securePrefs.deviceLabel.ifBlank { "android" }

        var anyFailure = false
        var anyNewConflicts = false
        for (binding in bindings) {
            try {
                val result = engine.syncFolderOnce(binding.folderId, Uri.parse(binding.treeUri), label)
                // lastSyncedAt heisst "letzter erfolgreicher Lauf" (siehe
                // Entities.kt-Doc) -- auch bei "Bereits aktuell" (kein
                // Transfer nötig) aktualisieren, sonst zeigt die App nach
                // Neustart fälschlich "Noch nie synchronisiert" für einen
                // Ordner, der gerade erst erfolgreich geprüft wurde.
                if (!result.aborted) {
                    dao.updateLastSyncedAt(binding.folderId, System.currentTimeMillis())
                }
                if (result.newConflicts.isNotEmpty()) anyNewConflicts = true
            } catch (_: Exception) {
                anyFailure = true
            }
        }
        // Erst NACH dem Durchlauf aller Ordner und mit der Gesamtzahl aus
        // der DB benachrichtigen -- sonst würde bei mehreren betroffenen
        // Ordnern in einem Lauf mehrfach (mit jeweils veraltetem Stand)
        // benachrichtigt (siehe ConflictNotifications-Doc-Kommentar).
        if (anyNewConflicts) {
            ConflictNotifications.notifyNewConflicts(app, dao.pendingConflictCount())
        }
        return if (anyFailure) Result.retry() else Result.success()
    }
}
