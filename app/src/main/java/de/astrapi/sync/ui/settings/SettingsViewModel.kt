package de.astrapi.sync.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.astrapi.sync.SyncApp
import de.astrapi.sync.ui.theme.AccentColor
import de.astrapi.sync.ui.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()

    val themeMode: StateFlow<ThemeMode> = app.preferences.themeMode
    val accentColor: StateFlow<AccentColor> = app.preferences.accentColor
    val useDynamicColor: StateFlow<Boolean> = app.preferences.useDynamicColor
    val syncIntervalMinutes: StateFlow<Long> = app.preferences.syncIntervalMinutes

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
}
