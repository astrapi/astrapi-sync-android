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
        for (binding in bindings) {
            try {
                val result = engine.syncFolderOnce(binding.folderId, Uri.parse(binding.treeUri), label)
                val total = result.uploaded.size + result.downloaded.size +
                    result.deletedLocal.size + result.deletedRemote.size
                // Nur bei echter Änderung aktualisieren -- muss zur
                // Server-Definition von "Letzter Lauf" passen (Activity
                // Log bekommt bei total == 0 bewusst keinen Eintrag,
                // sync.py::log_sync_summary(), T-212-SYNC).
                if (!result.aborted && total > 0) {
                    dao.updateLastSyncedAt(binding.folderId, System.currentTimeMillis())
                }
            } catch (_: Exception) {
                anyFailure = true
            }
        }
        return if (anyFailure) Result.retry() else Result.success()
    }
}
