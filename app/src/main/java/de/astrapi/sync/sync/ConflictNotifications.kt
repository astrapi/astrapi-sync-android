package de.astrapi.sync.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.astrapi.sync.MainActivity

/** Konflikt-Benachrichtigung für den Hintergrund-Sync (SyncWorker) --
 * ohne offene App wäre ein neu erkannter Konflikt sonst erst beim
 * nächsten manuellen Öffnen sichtbar. Bewusst NUR bei tatsächlich NEUEN
 * Konflikten ausgelöst (siehe SyncEngine.SyncResult.newConflicts), nicht
 * bei jedem periodischen Lauf erneut für denselben, weiterhin
 * ungelösten Konflikt -- sonst würde ein einziger ungelöster Konflikt
 * bei jedem Intervall die Statusleiste erneut aufpoppen lassen. */
object ConflictNotifications {
    private const val CHANNEL_ID = "sync_conflicts"

    // Fester Wert statt pro Konflikt eine eigene ID: mehrere neue
    // Konflikte in kurzer Zeit sollen sich zu EINER Benachrichtigung mit
    // aktualisiertem Text zusammenfassen, nicht die Statusleiste fluten.
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync-Konflikte",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [totalPending] ist die GESAMTZAHL aller aktuell offenen Konflikte
     * (nicht nur die neuen aus diesem Lauf) -- der Text soll immer den
     * vollständigen Nachholbedarf zeigen, auch wenn frühere Konflikte
     * noch unbeantwortet sind. */
    fun notifyNewConflicts(context: Context, totalPending: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CONFLICTS, true)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (totalPending == 1) {
            "1 Datei konnte nicht automatisch synchronisiert werden"
        } else {
            "$totalPending Dateien konnten nicht automatisch synchronisiert werden"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sync-Konflikt")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
