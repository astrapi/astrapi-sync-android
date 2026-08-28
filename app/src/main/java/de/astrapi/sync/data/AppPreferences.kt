package de.astrapi.sync.data

import android.content.Context
import de.astrapi.sync.ui.theme.AccentColor
import de.astrapi.sync.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Nicht-sensible UI-Einstellungen -- bewusst getrennt von SecurePrefs
 * (EncryptedSharedPreferences), da hier nichts Schützenswertes drinsteht
 * und der Verschlüsselungs-Overhead dafür unnötig wäre. */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("astrapi_sync_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.find { it.name == prefs.getString(KEY_THEME_MODE, null) }
            ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    /** Default false -- ein Umstieg auf wallpaperbasierte Farben soll
     * eine bewusste Nutzerentscheidung sein, nicht die abgestimmte
     * Standard-Akzentfarbe beim Update stillschweigend ersetzen. */
    private val _useDynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor

    fun setUseDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        _useDynamicColor.value = value
    }

    private val _accentColor = MutableStateFlow(
        AccentColor.entries.find { it.name == prefs.getString(KEY_ACCENT_COLOR, null) }
            ?: AccentColor.INDIGO,
    )
    val accentColor: StateFlow<AccentColor> = _accentColor

    fun setAccentColor(color: AccentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, color.name).apply()
        _accentColor.value = color
    }

    private val _syncIntervalMinutes = MutableStateFlow(
        prefs.getLong(KEY_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES),
    )
    val syncIntervalMinutes: StateFlow<Long> = _syncIntervalMinutes

    fun setSyncIntervalMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MINUTES, minutes).apply()
        _syncIntervalMinutes.value = minutes
    }

    companion object {
        /** WorkManager erzwingt ohnehin ein Minimum von 15 Min. für
         * periodische Arbeit -- die Auswahl im SettingsScreen bietet
         * deshalb bewusst nichts Kleineres an. */
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30L

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "use_dynamic_color"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
    }
}
