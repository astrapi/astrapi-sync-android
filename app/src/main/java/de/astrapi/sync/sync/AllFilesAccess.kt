package de.astrapi.sync.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** Helfer rund um `MANAGE_EXTERNAL_STORAGE` -- seit T-340-SYNC
 * Voraussetzung fürs Binden eines Ordners (siehe FileOps, ersetzt SAF).
 * Anders als Laufzeit-Permissions (z.B. POST_NOTIFICATIONS) lässt sich
 * diese Sonderberechtigung nicht per Dialog anfragen, sondern nur über
 * einen System-Settings-Screen gewähren -- Nutzer muss dort aktiv den
 * Schalter für diese App umlegen. */
object AllFilesAccess {

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
