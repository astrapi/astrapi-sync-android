package de.astrapi.sync.sync

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Storage-Access-Framework-Helfer -- Android erlaubt seit Scoped
 * Storage keinen freien Dateisystem-Zugriff mehr, der Nutzer gibt einen
 * Ordner explizit über den System-Ordnerwähler frei
 * (ACTION_OPEN_DOCUMENT_TREE), die App bekommt dann nur eine
 * URI-basierte DocumentFile-Sicht darauf, keinen normalen Pfad.
 *
 * WICHTIG, Android-spezifische Falle: anders als POSIX rmdir() (das bei
 * einem nicht-leeren Verzeichnis von selbst mit ENOTEMPTY fehlschlägt),
 * löscht DocumentFile.delete() auf einem SAF-Verzeichnis bei den
 * meisten Providern REKURSIV, ohne Rückfrage. Jede Löschfunktion hier
 * prüft deshalb VORHER explizit auf Leerheit -- siehe deleteEmptyDir(). */
object SafFileOps {

    private const val CONFLICT_INFIX = ".syncconflict-"
    private const val TMP_PREFIX = ".astrapi-sync-tmp-"
    private const val MIME_OCTET_STREAM = "application/octet-stream"

    fun root(context: Context, treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Konnte SAF-Baum nicht öffnen: $treeUri")

    /** Läuft den kompletten lokalen Baum ab, liefert alle Dateien als
     * (relativer Pfad -> DocumentFile) -- Pendant zu
     * engine.py::_local_files(). Konflikt-Kopien werden übersprungen,
     * damit sie nicht selbst wieder synchronisiert werden. */
    fun listLocalFiles(root: DocumentFile): Map<String, DocumentFile> {
        val result = LinkedHashMap<String, DocumentFile>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
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
    fun listLocalEmptyDirs(root: DocumentFile): List<String> {
        val result = mutableListOf<String>()
        fun hasAnyFile(dir: DocumentFile): Boolean {
            for (child in dir.listFiles()) {
                if (child.isDirectory) {
                    if (hasAnyFile(child)) return true
                } else {
                    return true
                }
            }
            return false
        }
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                if (!child.isDirectory) continue
                val name = child.name ?: continue
                val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
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
    fun findOrCreateParentDir(root: DocumentFile, relPath: String): DocumentFile {
        val parts = relPath.split("/").dropLast(1)
        var cur = root
        for (part in parts) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(part)
                ?: throw IllegalStateException("Konnte Verzeichnis '$part' nicht anlegen")
        }
        return cur
    }

    fun findFile(root: DocumentFile, relPath: String): DocumentFile? {
        val parts = relPath.split("/")
        var cur = root
        for ((i, part) in parts.withIndex()) {
            val next = cur.findFile(part) ?: return null
            if (i < parts.lastIndex && !next.isDirectory) return null
            cur = next
        }
        return cur
    }

    fun findDir(root: DocumentFile, relPath: String): DocumentFile? {
        val parts = relPath.split("/")
        var cur = root
        for (part in parts) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    /** Findet oder legt das Verzeichnis [relPath] selbst an (inkl. aller
     * fehlenden Zwischenverzeichnisse). */
    fun findOrCreateDir(root: DocumentFile, relPath: String): DocumentFile {
        var cur = root
        for (part in relPath.split("/")) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(part)
                ?: throw IllegalStateException("Konnte Verzeichnis '$part' nicht anlegen")
        }
        return cur
    }

    /** Erstellt/ersetzt eine Datei an [relPath] -- schreibt zunächst
     * unter einem Temp-Namen im selben Zielverzeichnis und benennt erst
     * nach vollständigem Schreiben um (siehe SyncEngine.downloadTo()).
     * Analog zum atomaren Temp-Datei-plus-rename-Muster des
     * Python-Clients (api_client.py::download()). */
    fun createTempTarget(root: DocumentFile, relPath: String): DocumentFile {
        val parent = findOrCreateParentDir(root, relPath)
        val name = relPath.substringAfterLast("/")
        val tmpName = "$TMP_PREFIX${System.nanoTime()}-$name"
        return parent.createFile(MIME_OCTET_STREAM, tmpName)
            ?: throw IllegalStateException("Konnte Temp-Datei für '$relPath' nicht anlegen")
    }

    /** Ersetzt eine ggf. bestehende Zieldatei durch [tmp] (atomarer
     * Umbenennungs-Schritt). */
    fun commitTempTarget(root: DocumentFile, relPath: String, tmp: DocumentFile) {
        val parent = findOrCreateParentDir(root, relPath)
        val name = relPath.substringAfterLast("/")
        parent.findFile(name)?.delete()
        if (!tmp.renameTo(name)) {
            throw IllegalStateException("Konnte '$relPath' nicht fertigstellen (renameTo fehlgeschlagen)")
        }
    }

    fun deleteFile(doc: DocumentFile) {
        doc.delete()
    }

    /** Löscht ein Verzeichnis NUR, wenn es tatsächlich leer ist -- siehe
     * Klassen-Dokumentation, DocumentFile.delete() würde sonst
     * rekursiv löschen. Gibt zurück, ob wirklich gelöscht wurde. */
    fun deleteEmptyDir(dir: DocumentFile): Boolean {
        if (dir.listFiles().isNotEmpty()) return false
        return dir.delete()
    }

    fun openInputPfd(context: Context, doc: DocumentFile): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(doc.uri, "r")
            ?: throw IllegalStateException("Konnte '${doc.name}' nicht zum Lesen öffnen")

    fun hashDocument(context: Context, doc: DocumentFile, blockSize: Int = BlockHash.DEFAULT_BLOCK_SIZE): BlockHash.HashResult {
        openInputPfd(context, doc).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                return BlockHash.hash(stream, blockSize)
            }
        }
    }

    /** Liest genau die angegebenen Blockindizes (aufsteigend sortiert
     * erwartet, wie server-seitig auch) und hängt sie aneinander --
     * Pendant zu api_client.py's `read_block()`-Aufrufen beim Upload. */
    fun readBlocks(context: Context, doc: DocumentFile, indices: List<Int>, blockSize: Int): ByteArray {
        if (indices.isEmpty()) return ByteArray(0)
        openInputPfd(context, doc).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
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
    }

    fun size(doc: DocumentFile): Long = doc.length()

    /** Android/SAF-Einschränkung: DocumentFile bietet keine öffentliche
     * Methode, die Änderungszeit einer Datei EXPLIZIT zu setzen (anders
     * als os.utime() unter POSIX) -- der Server-mtime-Wert wird beim
     * Download zwar mitgeliefert, kann hier aber nicht auf die lokale
     * Datei angewendet werden. Für die Sync-Logik unproblematisch (die
     * arbeitet ausschließlich mit Hashes, nicht mit mtime-Vergleichen),
     * nur beim Anzeigen des "echten" Änderungsdatums relevant. */
    fun lastModifiedSeconds(doc: DocumentFile): Double = doc.lastModified() / 1000.0

    /** Legt eine `.syncconflict-<Zeit>-<Gerät>`-Kopie neben der Originaldatei an
     * -- Pendant zu engine.py::_conflict_copy(). */
    fun conflictCopy(context: Context, root: DocumentFile, relPath: String, original: DocumentFile, deviceLabel: String): DocumentFile {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dotIdx = relPath.substringAfterLast("/").lastIndexOf('.')
        val name = relPath.substringAfterLast("/")
        val conflictName = if (dotIdx >= 0) {
            name.substring(0, dotIdx) + "$CONFLICT_INFIX$ts-$deviceLabel" + name.substring(dotIdx)
        } else {
            "$name$CONFLICT_INFIX$ts-$deviceLabel"
        }
        val parent = findOrCreateParentDir(root, relPath)
        val copy = parent.createFile(MIME_OCTET_STREAM, conflictName)
            ?: throw IllegalStateException("Konnte Konflikt-Kopie für '$relPath' nicht anlegen")
        context.contentResolver.openInputStream(original.uri)!!.use { input ->
            context.contentResolver.openOutputStream(copy.uri)!!.use { output ->
                input.copyTo(output)
            }
        }
        return copy
    }
}
