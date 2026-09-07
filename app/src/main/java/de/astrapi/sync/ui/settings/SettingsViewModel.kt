package de.astrapi.sync.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.astrapi.sync.SyncApp
import de.astrapi.sync.ui.theme.AccentColor
import de.astrapi.sync.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()

    val themeMode: StateFlow<ThemeMode> = app.preferences.themeMode
    val accentColor: StateFlow<AccentColor> = app.preferences.accentColor
    val useDynamicColor: StateFlow<Boolean> = app.preferences.useDynamicColor
    val syncIntervalMinutes: StateFlow<Long> = app.preferences.syncIntervalMinutes
    val realtimeSyncEnabled: StateFlow<Boolean> = app.preferences.realtimeSyncEnabled

    private val _realtimeSyncError = MutableStateFlow<String?>(null)
    val realtimeSyncError: StateFlow<String?> = _realtimeSyncError

    fun setThemeMode(mode: ThemeMode) = app.preferences.setThemeMode(mode)
    fun setAccentColor(color: AccentColor) = app.preferences.setAccentColor(color)
    fun setUseDynamicColor(value: Boolean) = app.preferences.setUseDynamicColor(value)

    /** Schreibt die Einstellung UND plant den laufenden WorkManager-Job
     * sofort neu ein -- sonst würde die Änderung erst beim nächsten
     * App-Start wirksam (siehe SyncApp.scheduleBackgroundSync()). */
    fun setSyncIntervalMinutes(minutes: Long) {
        app.preferences.setSyncIntervalMinutes(minutes)
        app.scheduleBackgroundSync()
    }

    /** Aufgerufen vom Settings-Screen, NACHDEM UnifiedPush.tryPickDistributor()
     * (braucht eine Activity, deshalb dort statt hier) zurückgekommen ist.
     * `picked=false` heißt üblicherweise: kein UnifiedPush-fähiger
     * Distributor (z.B. ntfy-App) installiert. Die eigentliche Server-
     * Registrierung passiert asynchron in PushReceiverService.onNewEndpoint()
     * -- register() liefert den Endpoint nicht synchron zurück. Schlägt die
     * Registrierung später fehl, setzt PushReceiverService.onRegistrationFailed()
     * die Preference selbst wieder zurück. */
    fun onDistributorPicked(picked: Boolean) {
        if (!picked) {
            _realtimeSyncError.value = "Kein UnifiedPush-Distributor gefunden (z.B. ntfy-App installieren)"
            return
        }
        _realtimeSyncError.value = null
        UnifiedPush.register(app)
        app.preferences.setRealtimeSyncEnabled(true)
    }

    fun disableRealtimeSync() {
        _realtimeSyncError.value = null
        UnifiedPush.unregister(app)
        app.preferences.setRealtimeSyncEnabled(false)
        app.securePrefs.unifiedPushEndpoint = ""
        if (!app.securePrefs.isPaired) return
        viewModelScope.launch {
            runCatching { app.apiClient().registerPushEndpoint("") }
        }
    }
}
