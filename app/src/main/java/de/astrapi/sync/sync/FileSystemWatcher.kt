package de.astrapi.sync.sync

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import de.astrapi.sync.SyncApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Registriert pro gebundenem Ordner einen [FileObserver], der lokale
 * Dateiänderungen (Hinzufügen/Ändern/Löschen/Umbenennen) sofort meldet --
 * Ergänzung zum UnifiedPush-Weckruf (PushReceiverService), der nur
 * Änderungen von ANDEREN Geräten meldet. Ersetzt den ursprünglichen
 * SAF-`ContentObserver`-Ansatz (T-339-SYNC): Live-Test auf einem Pixel 10
 * zeigte, dass SAF-Events dort schlicht nicht feuern. Seit T-340-SYNC hat
 * die App über `MANAGE_EXTERNAL_STORAGE` echten Dateisystem-Zugriff, damit
 * funktioniert der rekursive `FileObserver(File, Int)`-Konstruktor (echtes
 * `inotify`) zuverlässig. Der periodische WorkManager-Sync bleibt trotzdem
 * als Sicherheitsnetz bestehen -- dieser Watcher beschleunigt nur den
 * Regelfall.
 *
 * Anders als der frühere ContentObserver kennt FileObserver kein
 * `selfChange` -- ein Download/eine Konflikt-Kopie, die die Engine selbst
 * in den Ordner schreibt, löst also ebenfalls ein Event aus und damit
 * einen weiteren (dann folgenlosen) Sync-Lauf. Bewusst in Kauf genommen:
 * der bestehende 500ms-Debounce und WorkManagers ExistingWorkPolicy.KEEP
 * verhindern echte Parallelläufe, ein zusätzlicher "Bereits aktuell"-Lauf
 * ist kein Korrektheitsproblem.
 *
 * Liest die Bindings über allBindingsFlow() statt eines manuellen
 * refresh()-Aufrufs an jeder Änderungsstelle (Pairing, Ordner hinzufügen/
 * entfernen) -- dieselbe reaktive Quelle, die auch
 * FolderListViewModel.observeBound() nutzt, damit neue/entfernte
 * Ordner-Bindings automatisch nachgezogen werden, ohne dass der Aufrufer
 * daran denken muss. */
class FileSystemWatcher(private val app: SyncApp) {

    private var scope: CoroutineScope? = null
    private val observers = mutableMapOf<String, FileObserver>()
    private val handler = Handler(Looper.getMainLooper())

    /** Debounce pro Ordner -- ein einzelner Schreibvorgang löst über
     * inotify häufig mehrere Events hintereinander aus (z.B. CREATE
     * gefolgt von CLOSE_WRITE). ExistingWorkPolicy.KEEP in
     * triggerImmediateSync() schützt nur vor parallelen Läufen, nicht vor
     * mehreren schnell aufeinanderfolgenden Anstößen kurz vor Ablauf des
     * jeweils letzten -- daher zusätzlich hier 500ms warten. */
    private val pendingTriggers = mutableMapOf<String, Runnable>()
    private val debounceMillis = 500L

    private val watchMask = FileObserver.CREATE or FileObserver.DELETE or
        FileObserver.CLOSE_WRITE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO

    /** Beginnt, alle aktuell gebundenen Ordner zu beobachten -- aufgerufen
     * von FileWatchService.onCreate(). Wiederholtes Aufrufen ohne
     * zwischenzeitliches stop() ist folgenlos. */
    fun start() {
        if (scope != null) {
            Log.d("FileSystemWatcher", "Already started, ignoring duplicate start()")
            return
        }
        Log.d("FileSystemWatcher", "Starting FileSystemWatcher")
        val newScope = CoroutineScope(SupervisorJob())
        scope = newScope
        newScope.launch {
            app.database.syncStateDao().allBindingsFlow().collectLatest { bindings ->
                Log.d("FileSystemWatcher", "Observed ${bindings.size} bindings")
                debugToast("Watcher: ${bindings.size} Ordner beobachtet")
                syncObservers(bindings.map { it.folderId to File(it.folderPath) })
            }
        }
    }

    /** Meldet alle Observer ab -- aufgerufen von
     * FileWatchService.onDestroy(). */
    fun stop() {
        scope?.cancel()
        scope = null
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        pendingTriggers.values.forEach { handler.removeCallbacks(it) }
        pendingTriggers.clear()
    }

    private fun syncObservers(current: List<Pair<String, File>>) {
        val currentIds = current.map { it.first }.toSet()
        val removed = observers.keys - currentIds
        removed.forEach { folderId ->
            Log.d("FileSystemWatcher", "Unregistering observer for $folderId")
            observers.remove(folderId)?.stopWatching()
        }
        current.forEach { (folderId, dir) ->
            if (folderId in observers) {
                Log.d("FileSystemWatcher", "Observer for $folderId already registered")
                return@forEach
            }
            if (!dir.isDirectory) {
                // Ordner (noch) nicht vorhanden/zugreifbar -- z.B. gerade
                // erst gebunden, bevor der erste Sync ihn angelegt hat.
                // Kein Absturz, der nächste Bindings-Wechsel (oder der
                // nächste Foreground-Start) versucht es erneut.
                Log.d("FileSystemWatcher", "Skipping $folderId, not a directory: $dir")
                return@forEach
            }
            Log.d("FileSystemWatcher", "Registering observer for $folderId on $dir")
            val observer = object : FileObserver(dir, watchMask) {
                override fun onEvent(event: Int, path: String?) {
                    Log.d("FileSystemWatcher", "onEvent for $folderId (event=$event, path=$path)")
                    debugToast("Dateisystem-Event erkannt (Ordner $folderId)")
                    scheduleTrigger(folderId)
                }
            }
            observer.startWatching()
            observers[folderId] = observer
        }
    }

    private fun scheduleTrigger(folderId: String) {
        Log.d("FileSystemWatcher", "Scheduling trigger for $folderId (debounce ${debounceMillis}ms)")
        pendingTriggers.remove(folderId)?.let {
            Log.d("FileSystemWatcher", "Cancelled pending trigger for $folderId")
            handler.removeCallbacks(it)
        }
        val runnable = Runnable {
            Log.d("FileSystemWatcher", "Debounce expired, calling triggerImmediateSync()")
            debugToast("Sofort-Sync ausgelöst (Ordner $folderId)")
            pendingTriggers.remove(folderId)
            app.triggerImmediateSync()
        }
        pendingTriggers[folderId] = runnable
        handler.postDelayed(runnable, debounceMillis)
    }

    /** Nur für die Handy-Verifikation von T-340-SYNC (kein adb auf dem
     * Testgerät verfügbar) -- macht die sonst nur in Logcat sichtbaren
     * Schritte direkt in der App sichtbar. Wieder entfernen, sobald die
     * Verifikation abgeschlossen ist.
     *
     * Über handler.post() statt direktem Aufruf -- FileObserver ruft
     * onEvent() von einem eigenen Observer-Thread auf, nicht dem
     * Main-Thread. Toast.show() von einem Nicht-Main-Thread wirft
     * CalledFromWrongThreadException. handler ist an
     * Looper.getMainLooper() gebunden, post() garantiert also Main-Thread
     * unabhängig vom Aufrufer-Thread. */
    private fun debugToast(text: String) {
        handler.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}
