package de.astrapi.sync

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.astrapi.sync.data.AppDatabase
import de.astrapi.sync.data.AppPreferences
import de.astrapi.sync.data.SecurePrefs
import de.astrapi.sync.network.ApiClient
import de.astrapi.sync.sync.SyncWorker
import java.util.concurrent.TimeUnit

/** Hält die App-weiten Singletons -- bewusst keine DI-Bibliothek
 * (Hilt/Dagger), passend zur schlanken Philosophie des Python-Clients:
 * ein Application-Objekt reicht für diesen Umfang. */
class SyncApp : Application() {

    val securePrefs by lazy { SecurePrefs(this) }
    val preferences by lazy { AppPreferences(this) }
    val database by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Sicherheitsnetz für den Fall, dass die WorkManager-eigene
        // Neuplanung nach einem Reboot mal nicht greift -- KEEP macht
        // wiederholtes Aufrufen (jeder App-Start) folgenlos, solange
        // schon ein Job eingeplant ist.
        if (securePrefs.isPaired) scheduleBackgroundSync()
    }

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

    private companion object {
        const val SYNC_WORK_NAME = "periodic_folder_sync"
    }
}
