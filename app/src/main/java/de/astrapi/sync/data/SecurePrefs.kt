package de.astrapi.sync.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Geräte-Token, Server-URL etc. verschlüsselt auf Platte -- anders als
 * beim Python-Client (config.py: Klartext-JSON, siehe T-218-SYNC) nutzt
 * Android dafür eine fertige, wenig aufwändige Bordlösung
 * (Jetpack Security), kein Grund die MVP-Vereinfachung von dort zu
 * übernehmen. */
class SecurePrefs(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "astrapi_sync_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceLabel: String
        get() = prefs.getString(KEY_DEVICE_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_LABEL, value).apply()

    val isPaired: Boolean get() = deviceToken.isNotBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_LABEL = "device_label"
    }
}
