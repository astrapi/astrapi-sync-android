package de.astrapi.sync.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.astrapi.sync.SyncApp
import de.astrapi.sync.ui.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<SyncApp>()

    val themeMode: StateFlow<ThemeMode> = app.preferences.themeMode

    fun setThemeMode(mode: ThemeMode) = app.preferences.setThemeMode(mode)
}
