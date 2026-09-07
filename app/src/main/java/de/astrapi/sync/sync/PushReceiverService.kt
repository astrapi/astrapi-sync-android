package de.astrapi.sync.sync

import android.util.Log
import de.astrapi.sync.SyncApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

private const val TAG = "PushReceiverService"

/** Empfängt UnifiedPush-Weckrufe vom Distributor (z.B. ntfy) -- siehe
 * astrapi-hub-Vault, projects/sync (Echtzeit-Sync-Diskussion). Der
 * eigentliche Nachrichteninhalt (onMessage) ist bewusst irrelevant: der
 * Push ist nur ein Weckruf, die App holt sich die tatsächliche Änderung
 * über einen normalen Sync-Lauf (SyncWorker liest ohnehin bei jedem Lauf
 * alle Bindings frisch aus der DB).
 *
 * Kein eigener CoroutineScope über den Service hinaus nötig -- die
 * ApiClient-Aufrufe sind kurze Best-effort-Calls (analog logSync()),
 * SupervisorJob verhindert nur, dass ein fehlgeschlagener Call einen
 * anderen gleichzeitig laufenden Callback stört. */
class PushReceiverService : PushService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val app = applicationContext as SyncApp
        app.securePrefs.unifiedPushEndpoint = endpoint.url
        if (!app.securePrefs.isPaired) return
        scope.launch {
            runCatching { app.apiClient().registerPushEndpoint(endpoint.url) }
                .onFailure { Log.w(TAG, "Endpoint-Registrierung beim Server fehlgeschlagen", it) }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        (applicationContext as SyncApp).triggerImmediateSync()
    }

    /** Distributor hat die Registrierung von sich aus zurückgezogen (z.B.
     * Nutzer hat sie in der ntfy-App entfernt) -- anders als beim
     * Ausschalten über den Settings-Schalter (SettingsViewModel.
     * disableRealtimeSync()) muss hier zusätzlich der Server informiert
     * werden, sonst versucht er weiter, einen toten Endpoint anzustoßen. */
    override fun onUnregistered(instance: String) {
        val app = applicationContext as SyncApp
        app.securePrefs.unifiedPushEndpoint = ""
        app.preferences.setRealtimeSyncEnabled(false)
        if (!app.securePrefs.isPaired) return
        scope.launch {
            runCatching { app.apiClient().registerPushEndpoint("") }
                .onFailure { Log.w(TAG, "Deregistrierung beim Server fehlgeschlagen", it) }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "UnifiedPush-Registrierung fehlgeschlagen: $reason")
        (applicationContext as SyncApp).preferences.setRealtimeSyncEnabled(false)
    }
}
