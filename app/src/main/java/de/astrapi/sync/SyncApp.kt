package de.astrapi.sync

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.astrapi.sync.data.AppDatabase
import de.astrapi.sync.data.AppPreferences
import de.astrapi.sync.data.SecurePrefs
import de.astrapi.sync.network.ApiClient
import de.astrapi.sync.sync.ConflictNotifications
import de.astrapi.sync.sync.FileSystemWatcher
import de.astrapi.sync.sync.FileWatchService
import de.astrapi.sync.sync.SyncWorker
import java.util.concurrent.TimeUnit

/** Hält die App-weiten Singletons -- bewusst keine DI-Bibliothek
 * (Hilt/Dagger), passend zur schlanken Philosophie des Python-Clients:
 * ein Application-Objekt reicht für diesen Umfang. */
class SyncApp : Application() {

    val securePrefs by lazy { SecurePrefs(this) }
    val preferences by lazy { AppPreferences(this) }
    val database by lazy { AppDatabase.get(this) }

    /** Echtzeit-Erkennung lokaler Dateiänderungen, siehe T-339-SYNC/
     * T-340-SYNC. Start/Stop übernimmt seit dem T-340-SYNC-Nachtest
     * FileWatchService (Foreground-Service), nicht mehr MainActivity --
     * siehe dessen Doc-Kommentar für die Begründung. */
    val fileSystemWatcher by lazy { FileSystemWatcher(this) }

    override fun onCreate() {
        super.onCreate()
        // Erneutes Erstellen bei jedem App-Start ist folgenlos --
        // createNotificationChannel() mit gleicher ID aktualisiert
        // höchstens Name/Wichtigkeit, legt nie doppelt an.
        ConflictNotifications.createChannel(this)
        // Sicherheitsnetz für den Fall, dass die WorkManager-eigene
        // Neuplanung nach einem Reboot mal nicht greift -- KEEP macht
        // wiederholtes Aufrufen (jeder App-Start) folgenlos, solange
        // schon ein Job eingeplant ist. Gleiches Argument gilt für den
        // Foreground-Service: startForegroundService() auf einen bereits
        // laufenden Service ruft nur erneut onStartCommand() auf, ohne
        // den Watcher neu zu registrieren (FileSystemWatcher.start() ist
        // selbst idempotent).
        if (securePrefs.isPaired) {
            scheduleBackgroundSync()
            startFileWatchService()
        }
    }

    /** Siehe FileWatchService-Doc-Kommentar. Muss sowohl hier (App-/
     * Prozessstart) als auch direkt nach erfolgreichem Pairing aufgerufen
     * werden (PairingViewModel.pair()) -- onCreate() ist beim allerersten
     * Pairing ja schon gelaufen, bevor securePrefs.isPaired wahr wird. */
    fun startFileWatchService() = FileWatchService.start(this)

    /** Neu erstellt statt gecacht -- Server-URL/Token können sich
     * ändern (z.B. nach erneutem Pairing), ein alter Client-Zustand
     * würde sonst mit einem stale Token weiterarbeiten. */
    fun apiClient(): ApiClient = ApiClient(securePrefs.serverUrl, securePrefs.deviceToken)

    /** Periodischer Hintergrund-Sync aller gebundenen Ordner, siehe
     * SyncWorker-Doc-Kommentar für die Begründung gegen einen
     * Dauer-Service. UPDATE (nicht KEEP) ist nötig, damit eine geänderte
     * Intervall-Einstellung den bereits laufenden Zeitplan tatsächlich
     * neu einplant, statt bis zum nächsten App-Start liegen zu bleiben --
     * WorkManager übernimmt dabei selbst, den nächsten Lauf sinnvoll
     * neu zu berechnen statt den Zeitplan komplett zu verwerfen. */
    fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            preferences.syncIntervalMinutes.value,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Einmaliger Sofort-Sync, ausgelöst von einem UnifiedPush-Weckruf
     * (siehe PushReceiverService). Eigener Work-Name (nicht SYNC_WORK_NAME),
     * damit sich periodischer und push-getriggerter Sync nicht gegenseitig
     * blockieren/canceln. KEEP statt REPLACE: mehrere kurz aufeinander-
     * folgende Pushes sollen keinen zweiten parallelen Lauf anstoßen --
     * SyncWorker liest ohnehin bei jedem Lauf alle Bindings frisch aus der
     * DB, ein bereits eingereihter Lauf deckt das schon ab. */
    fun triggerImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val SYNC_WORK_NAME = "periodic_folder_sync"
        const val IMMEDIATE_SYNC_WORK_NAME = "immediate_folder_sync"
    }
}
