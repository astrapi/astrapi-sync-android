package de.astrapi.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.astrapi.sync.SyncApp

/** Foreground-Service, der [FileSystemWatcher] unabhängig von der
 * MainActivity-Sichtbarkeit am Leben hält -- siehe T-340-SYNC-Nachtest
 * (2026-09-18, Pixel 10): mit dem ursprünglichen T-339-SYNC-Ansatz
 * (Watcher an Activity.onStart()/onStop() gekoppelt) verschwand der
 * Watcher genau im Hauptfall, den er eigentlich abdecken sollte -- eine
 * Ordneränderung durch eine ANDERE App erfordert fast immer, astrapi
 * sync selbst zu verlassen. Das löste onStop() sofort aus, was seinerseits
 * den gerade erst geplanten 500ms-Debounce-Trigger abbrach, bevor er
 * feuern konnte (Event-Toast erschien noch, "Sofort-Sync ausgelöst" nie).
 * Wie Syncthing-Android: dauerhafter Foreground-Service mit stiller
 * (IMPORTANCE_LOW), fortlaufender Benachrichtigung -- der Watcher hängt
 * jetzt nur noch am Pairing-Status (siehe SyncApp.startFileWatchService(),
 * aufgerufen bei App-Start und direkt nach dem Pairing), nicht mehr an
 * der Activity.
 *
 * Bekannte Lücke: kein BOOT_COMPLETED-Receiver -- nach einem Geräte-Neustart
 * läuft der Service erst wieder, sobald die App das nächste Mal geöffnet
 * wird (SyncApp.onCreate()). Der periodische WorkManager-Sync deckt die
 * Lücke bis dahin ab. */
class FileWatchService : Service() {

    private val watcher by lazy { (application as SyncApp).fileSystemWatcher }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        watcher.start()
    }

    // START_STICKY -- Android darf den Prozess unter Speicherdruck
    // beenden, soll den Service danach aber ohne den ursprünglichen
    // Intent neu erstellen (onCreate() reicht, es gibt keine
    // Intent-Parameter zu verarbeiten).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watcher.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ordnerüberwachung",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("astrapi sync")
            .setContentText("Beobachtet gebundene Ordner auf Änderungen")
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "file_watch"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FileWatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileWatchService::class.java))
        }
    }
}
