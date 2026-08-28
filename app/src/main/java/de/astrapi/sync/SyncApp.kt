package de.astrapi.sync

import android.app.Application
import de.astrapi.sync.data.AppDatabase
import de.astrapi.sync.data.SecurePrefs
import de.astrapi.sync.network.ApiClient

/** Hält die App-weiten Singletons -- bewusst keine DI-Bibliothek
 * (Hilt/Dagger), passend zur schlanken Philosophie des Python-Clients:
 * ein Application-Objekt reicht für diesen Umfang. */
class SyncApp : Application() {

    val securePrefs by lazy { SecurePrefs(this) }
    val database by lazy { AppDatabase.get(this) }

    /** Neu erstellt statt gecacht -- Server-URL/Token können sich
     * ändern (z.B. nach erneutem Pairing), ein alter Client-Zustand
     * würde sonst mit einem stale Token weiterarbeiten. */
    fun apiClient(): ApiClient = ApiClient(securePrefs.serverUrl, securePrefs.deviceToken)
}
