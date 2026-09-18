package de.astrapi.sync.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Echter Dateisystem-Zugriff über [java.io.File] statt Storage-Access-
 * Framework -- siehe T-340-SYNC: SAF-`ContentObserver` hat sich als
 * unzuverlässig erwiesen (T-339-SYNC), die App fordert stattdessen
 * `MANAGE_EXTERNAL_STORAGE` an und kann damit normale Dateisystem-Pfade
 * nutzen, auf denen `android.os.FileObserver` (echtes `inotify`)
 * zuverlässig feuert. Ersetzt das frühere SafFileOps (DocumentFile-
 * basiert).
 *
 * WICHTIG: anders als das frühere SafFileOps.deleteEmptyDir() prüft auch
 * hier jede Löschfunktion VORHER explizit auf Leerheit -- File.delete()
 * scheitert zwar (anders als DocumentFile.delete()) bei einem
 * nicht-leeren Verzeichnis von selbst, die explizite Prüfung bleibt aber
 * bestehen, damit der Rückgabewert unverändert "wirklich gelöscht"
 * bedeutet. */
object FileOps {

    private const val CONFLICT_INFIX = ".syncconflict-"
    private const val TMP_PREFIX = ".astrapi-sync-tmp-"

    fun root(path: String): File {
        val dir = File(path)
        if (!dir.isDirectory) throw IllegalStateException("Ordner nicht gefunden oder kein Verzeichnis: $path")
        return dir
    }

    /** Läuft den kompletten lokalen Baum ab, liefert alle Dateien als
     * (relativer Pfad -> File) -- Pendant zu engine.py::_local_files().
     * Konflikt-Kopien werden übersprungen, damit sie nicht selbst wieder
     * synchronisiert werden. */
    fun listLocalFiles(root: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        fun walk(dir: File, prefix: String) {
            for (child in dir.listFiles() ?: emptyArray()) {
                val name = child.name
                if (child.isDirectory) {
                    walk(child, if (prefix.isEmpty()) name else "$prefix/$name")
                } else if (CONFLICT_INFIX !in name && !name.startsWith(TMP_PREFIX)) {
                    val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
                    result[relPath] = child
                }
            }
        }
        walk(root, "")
        return result
    }

    /** Relative Pfade aller (rekursiv) leeren lokalen Verzeichnisse --
     * Pendant zu engine.py::_local_empty_dirs(). Muss NACH dem
     * Datei-Sync-Durchlauf aufgerufen werden, siehe SyncEngine. */
    fun listLocalEmptyDirs(root: File): List<String> {
        val result = mutableListOf<String>()
        fun hasAnyFile(dir: File): Boolean {
            for (child in dir.listFiles() ?: emptyArray()) {
                if (child.isDirectory) {
                    if (hasAnyFile(child)) return true
                } else {
                    return true
                }
            }
            return false
        }
        fun walk(dir: File, prefix: String) {
            for (child in dir.listFiles() ?: emptyArray()) {
                if (!child.isDirectory) continue
                val relPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (!hasAnyFile(child)) result.add(relPath)
                walk(child, relPath)
            }
        }
        walk(root, "")
        return result
    }

    /** Findet oder legt alle fehlenden Zwischenverzeichnisse für einen
     * relativen Pfad an -- Pendant zu target.parent.mkdir(parents=True)
     * beim Upload-Handler des Servers. */
    fun findOrCreateParentDir(root: File, relPath: String): File {
        val parts = relPath.split("/").dropLast(1)
        var cur = root
        for (part in parts) {
            cur = File(cur, part)
            if (!cur.isDirectory && !cur.mkdirs()) {
                throw IllegalStateException("Konnte Verzeichnis '$part' nicht anlegen")
            }
        }
        return cur
    }

    fun findFile(root: File, relPath: String): File? {
        val f = File(root, relPath)
        return if (f.isFile) f else null
    }

    fun findDir(root: File, relPath: String): File? {
        val f = File(root, relPath)
        return if (f.isDirectory) f else null
    }

    /** Findet oder legt das Verzeichnis [relPath] selbst an (inkl. aller
     * fehlenden Zwischenverzeichnisse). */
    fun findOrCreateDir(root: File, relPath: String): File {
        val dir = File(root, relPath)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Konnte Verzeichnis '$relPath' nicht anlegen")
        }
        return dir
    }

    /** Erstellt einen Temp-Datei-Pfad für [relPath] -- schreibt zunächst
     * unter einem Temp-Namen im selben Zielverzeichnis und benennt erst
     * nach vollständigem Schreiben um (siehe SyncEngine.downloadInto()).
     * Analog zum atomaren Temp-Datei-plus-rename-Muster des
     * Python-Clients (api_client.py::download()). Legt die Datei anders
     * als das frühere SafFileOps (SAF verlangt vorheriges createFile())
     * noch nicht an -- das übernimmt der Aufrufer beim Öffnen zum
     * Schreiben. */
    fun createTempTarget(root: File, relPath: String): File {
        val parent = findOrCreateParentDir(root, relPath)
        val name = relPath.substringAfterLast("/")
        return File(parent, "$TMP_PREFIX${System.nanoTime()}-$name")
    }

    /** Ersetzt eine ggf. bestehende Zieldatei durch [tmp] (atomarer
     * Umbenennungs-Schritt, gleiches Verzeichnis -> renameTo() ist unter
     * POSIX atomar). */
    fun commitTempTarget(root: File, relPath: String, tmp: File) {
        val parent = findOrCreateParentDir(root, relPath)
        val target = File(parent, relPath.substringAfterLast("/"))
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Konnte bestehende Datei '$relPath' nicht ersetzen")
        }
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("Konnte '$relPath' nicht fertigstellen (renameTo fehlgeschlagen)")
        }
    }

    fun deleteFile(file: File) {
        file.delete()
    }

    /** Löscht ein Verzeichnis NUR, wenn es tatsächlich leer ist. Gibt
     * zurück, ob wirklich gelöscht wurde. */
    fun deleteEmptyDir(dir: File): Boolean {
        if (dir.listFiles()?.isNotEmpty() == true) return false
        return dir.delete()
    }

    fun hashDocument(file: File, blockSize: Int = BlockHash.DEFAULT_BLOCK_SIZE): BlockHash.HashResult =
        FileInputStream(file).use { stream -> BlockHash.hash(stream, blockSize) }

    /** Liest genau die angegebenen Blockindizes (aufsteigend sortiert
     * erwartet, wie server-seitig auch) und hängt sie aneinander --
     * Pendant zu api_client.py's `read_block()`-Aufrufen beim Upload. */
    fun readBlocks(file: File, indices: List<Int>, blockSize: Int): ByteArray {
        if (indices.isEmpty()) return ByteArray(0)
        FileInputStream(file).use { stream ->
            val channel = stream.channel
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(blockSize)
            for (idx in indices) {
                channel.position(idx.toLong() * blockSize)
                var total = 0
                while (total < blockSize) {
                    val n = stream.read(buffer, total, blockSize - total)
                    if (n < 0) break
                    total += n
                }
                out.write(buffer, 0, total)
            }
            return out.toByteArray()
        }
    }

    fun size(file: File): Long = file.length()

    fun lastModifiedSeconds(file: File): Double = file.lastModified() / 1000.0

    /** Legt eine `.syncconflict-<Zeit>-<Gerät>`-Kopie neben der Originaldatei an
     * -- Pendant zu engine.py::_conflict_copy(). */
    fun conflictCopy(root: File, relPath: String, original: File, deviceLabel: String): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = relPath.substringAfterLast("/")
        val dotIdx = name.lastIndexOf('.')
        val conflictName = if (dotIdx >= 0) {
            name.substring(0, dotIdx) + "$CONFLICT_INFIX$ts-$deviceLabel" + name.substring(dotIdx)
        } else {
            "$name$CONFLICT_INFIX$ts-$deviceLabel"
        }
        val parent = findOrCreateParentDir(root, relPath)
        val copy = File(parent, conflictName)
        FileInputStream(original).use { input ->
            FileOutputStream(copy).use { output -> input.copyTo(output) }
        }
        return copy
    }
}
