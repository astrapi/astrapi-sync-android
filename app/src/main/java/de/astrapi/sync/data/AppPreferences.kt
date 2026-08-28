package de.astrapi.sync.data

import android.content.Context
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

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
    }
}
