package de.astrapi.sync.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.astrapi.sync.data.KnownDirEntity
import de.astrapi.sync.data.KnownFileEntity
import de.astrapi.sync.data.SyncStateDao
import de.astrapi.sync.network.ApiClient
import de.astrapi.sync.network.ConflictException
import de.astrapi.sync.network.FileEntry
import de.astrapi.sync.network.SyncSummary
import de.astrapi.sync.network.UploadMeta
import de.astrapi.sync.network.UploadResult

/** Kotlin-Port von astrapi_sync_cli/engine.py::sync_folder_once() --
 * Drei-Wege-Vergleich (lokal / Server / letzter bekannter Stand aus
 * SyncStateDao). Übernimmt bewusst zwei Korrekturen gegenüber dem
 * Python-Original, die dort erst nachträglich als Lücken gefunden
 * wurden (siehe astrapi-hub-Vault, projects/sync/):
 *
 * - T-215-SYNC: existiert eine Datei beim allerersten Sync (kein
 *   bekannter Stand) bereits mit UNTERSCHIEDLICHEM Inhalt auf beiden
 *   Seiten, wird das hier als Konflikt behandelt (Sicherungskopie +
 *   Server-Version übernehmen) statt die lokale Version stillschweigend
 *   gewinnen zu lassen.
 * - T-222-SYNC: löscht der Server eine Datei zwischen einem
 *   Upload-Konflikt und dem Nachschlage-Versuch, wird der veraltete
 *   bekannte Zustand für diesen Pfad verworfen statt stehen zu bleiben.
 */
class SyncEngine(
    private val context: Context,
    private val client: ApiClient,
    private val dao: SyncStateDao,
) {

    companion object {
        /** Siehe astrapi_sync_cli/engine.py -- gleiche Begründung: der
         * Client kann nicht unterscheiden zwischen "bewusst gelöscht"
         * und "Ordner/Server hat plötzlich unerwartet nichts mehr". */
        const val MAX_AUTO_DELETE = 3
        private const val BLOCK_SIZE = BlockHash.DEFAULT_BLOCK_SIZE
    }

    data class SyncResult(
        val aborted: Boolean = false,
        val reason: String? = null,
        val wouldDeleteLocal: List<String> = emptyList(),
        val wouldDeleteRemote: List<String> = emptyList(),
        val uploaded: List<String> = emptyList(),
        val downloaded: List<String> = emptyList(),
        val deletedLocal: List<String> = emptyList(),
        val deletedRemote: List<String> = emptyList(),
        val conflicts: List<String> = emptyList(),
        val dirsCreatedLocal: List<String> = emptyList(),
        val dirsCreatedRemote: List<String> = emptyList(),
        val dirsDeletedLocal: List<String> = emptyList(),
        val dirsDeletedRemote: List<String> = emptyList(),
    )

    suspend fun syncFolderOnce(
        folderId: String,
        rootUri: Uri,
        deviceLabel: String,
        confirmDeletes: Boolean = false,
        maxAutoDelete: Int = MAX_AUTO_DELETE,
    ): SyncResult {
        val root = SafFileOps.root(context, rootUri)
        val index = client.getIndex(folderId)
        val remoteIndex: Map<String, FileEntry> = index.files.associateBy { it.path }
        val remoteDirs = index.dirs.toSet()

        val localFiles = SafFileOps.listLocalFiles(root)
        val knownFiles = dao.knownFiles(folderId).associateBy { it.path }.toMutableMap()
        val knownDirs = dao.knownDirs(folderId).map { it.path }.toMutableSet()

        val (wouldDeleteLocal, wouldDeleteRemote) = planDeletions(remoteIndex, localFiles, knownFiles)
        val totalDeletes = wouldDeleteLocal.size + wouldDeleteRemote.size
        if (totalDeletes > maxAutoDelete && !confirmDeletes) {
            return SyncResult(
                aborted = true,
                reason = "$totalDeletes Löschungen in einem Lauf (Grenze: $maxAutoDelete) -- " +
                    "ohne Bestätigung nicht ausgeführt",
                wouldDeleteLocal = wouldDeleteLocal,
                wouldDeleteRemote = wouldDeleteRemote,
            )
        }

        val uploaded = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val deletedLocal = mutableListOf<String>()
        val deletedRemote = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        val allPaths = (remoteIndex.keys + localFiles.keys + knownFiles.keys).toSortedSet()
        for (relPath in allPaths) {
            val remote = remoteIndex[relPath]
            val localDoc = localFiles[relPath]
            val lastKnown = knownFiles[relPath]
            val localHash = localDoc?.let { SafFileOps.hashDocument(context, it, BLOCK_SIZE).sha256 }

            when (SyncDecision.decide(remote, localDoc != null, localHash, lastKnown)) {
                FileAction.Forget -> knownFiles.remove(relPath)

                FileAction.DeleteLocal -> {
                    SafFileOps.deleteFile(localDoc!!)
                    knownFiles.remove(relPath)
                    deletedLocal.add(relPath)
                }

                FileAction.UploadNew -> {
                    val info = uploadFile(folderId, relPath, localDoc!!, remoteBlocks = null, expected = null)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, info.sha256, SafFileOps.size(localDoc))
                    uploaded.add(relPath)
                }

                FileAction.DeleteRemote -> {
                    client.delete(folderId, relPath)
                    knownFiles.remove(relPath)
                    deletedRemote.add(relPath)
                }

                FileAction.DownloadNew -> {
                    downloadInto(root, folderId, relPath)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, remote!!.sha256, remote.size)
                    downloaded.add(relPath)
                }

                FileAction.KeepInSync -> {
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, localHash!!, SafFileOps.size(localDoc!!))
                }

                FileAction.Conflict -> {
                    SafFileOps.conflictCopy(context, root, relPath, localDoc!!, deviceLabel)
                    downloadInto(root, folderId, relPath)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, remote!!.sha256, remote.size)
                    conflicts.add(relPath)
                }

                FileAction.DownloadChanged -> {
                    downloadInto(root, folderId, relPath)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, remote!!.sha256, remote.size)
                    downloaded.add(relPath)
                }

                FileAction.UploadChanged -> {
                    try {
                        val info = uploadFile(folderId, relPath, localDoc!!, remote!!.blocks, remote.sha256)
                        knownFiles[relPath] = KnownFileEntity(folderId, relPath, info.sha256, SafFileOps.size(localDoc))
                        uploaded.add(relPath)
                    } catch (e: ConflictException) {
                        // Wettlauf zwischen Index-Abruf und Upload: Server hat
                        // sich zwischenzeitlich veraendert -> wie einen echten
                        // Konflikt behandeln.
                        SafFileOps.conflictCopy(context, root, relPath, localDoc!!, deviceLabel)
                        val fresh = client.getIndex(folderId).files.firstOrNull { it.path == relPath }
                        if (fresh != null) {
                            downloadInto(root, folderId, relPath)
                            knownFiles[relPath] = KnownFileEntity(folderId, relPath, fresh.sha256, fresh.size)
                        } else {
                            // T-222-SYNC-Fix: Server hat die Datei
                            // zwischenzeitlich geloescht -- veralteten
                            // known-Eintrag verwerfen statt stehen zu lassen.
                            knownFiles.remove(relPath)
                        }
                        conflicts.add(relPath)
                    }
                }
            }
        }

        val dirResult = syncEmptyDirs(folderId, root, remoteDirs, knownDirs)

        dao.replaceKnownState(
            folderId,
            knownFiles.values.toList(),
            knownDirs.map { KnownDirEntity(folderId, it) },
        )

        val result = SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            conflicts = conflicts,
            dirsCreatedLocal = dirResult.createdLocal,
            dirsCreatedRemote = dirResult.createdRemote,
            dirsDeletedLocal = dirResult.deletedLocal,
            dirsDeletedRemote = dirResult.deletedRemote,
        )
        logSummary(folderId, result)
        return result
    }

    private fun planDeletions(
        remoteIndex: Map<String, FileEntry>,
        localFiles: Map<String, DocumentFile>,
        known: Map<String, KnownFileEntity>,
    ): Pair<List<String>, List<String>> {
        val localDeletes = mutableListOf<String>()
        val remoteDeletes = mutableListOf<String>()
        val allPaths = remoteIndex.keys + localFiles.keys + known.keys
        for (relPath in allPaths) {
            val lastKnown = known[relPath] ?: continue
            val remote = remoteIndex[relPath]
            val localDoc = localFiles[relPath]
            if (remote == null && localDoc != null) {
                val localHash = SafFileOps.hashDocument(context, localDoc).sha256
                if (localHash == lastKnown.sha256) localDeletes.add(relPath)
            } else if (remote != null && localDoc == null) {
                if (lastKnown.sha256 == remote.sha256) remoteDeletes.add(relPath)
            }
        }
        return localDeletes to remoteDeletes
    }

    private data class DirSyncResult(
        val createdLocal: List<String>,
        val createdRemote: List<String>,
        val deletedLocal: List<String>,
        val deletedRemote: List<String>,
    )

    /** Muss NACH dem Datei-Sync-Loop laufen -- ein Verzeichnis, das
     * gerade erst eine Datei bekommen/verloren hat, darf nicht mit
     * einem veralteten Leer-Zustand bewertet werden (siehe
     * engine.py-Kommentar, gleiche Begründung). Kein
     * Massenlöschungs-Schutz nötig: ein leeres Verzeichnis kann keine
     * Daten verlieren. */
    private suspend fun syncEmptyDirs(
        folderId: String,
        root: DocumentFile,
        remoteDirs: Set<String>,
        knownDirs: MutableSet<String>,
    ): DirSyncResult {
        val localDirs = SafFileOps.listLocalEmptyDirs(root).toSet()
        val createdLocal = mutableListOf<String>()
        val createdRemote = mutableListOf<String>()
        val deletedLocal = mutableListOf<String>()
        val deletedRemote = mutableListOf<String>()

        for (relPath in (localDirs + remoteDirs + knownDirs).toSortedSet()) {
            val inLocal = relPath in localDirs
            val inRemote = relPath in remoteDirs
            val wasKnown = relPath in knownDirs

            when {
                inLocal && inRemote -> knownDirs.add(relPath)

                inLocal && !inRemote -> {
                    if (wasKnown) {
                        val dir = SafFileOps.findDir(root, relPath)
                        if (dir != null && SafFileOps.deleteEmptyDir(dir)) {
                            knownDirs.remove(relPath)
                            deletedLocal.add(relPath)
                        } else {
                            knownDirs.add(relPath)
                        }
                    } else {
                        client.createDir(folderId, relPath)
                        knownDirs.add(relPath)
                        createdRemote.add(relPath)
                    }
                }

                inRemote && !inLocal -> {
                    if (wasKnown) {
                        if (client.deleteDir(folderId, relPath)) {
                            knownDirs.remove(relPath)
                            deletedRemote.add(relPath)
                        } else {
                            knownDirs.add(relPath)
                        }
                    } else {
                        SafFileOps.findOrCreateDir(root, relPath)
                        knownDirs.add(relPath)
                        createdLocal.add(relPath)
                    }
                }

                else -> knownDirs.remove(relPath)
            }
        }
        return DirSyncResult(createdLocal, createdRemote, deletedLocal, deletedRemote)
    }

    private suspend fun uploadFile(
        folderId: String,
        relPath: String,
        localDoc: DocumentFile,
        remoteBlocks: List<String>?,
        expected: String?,
    ): UploadResult {
        val hashResult = SafFileOps.hashDocument(context, localDoc, BLOCK_SIZE)
        val remoteB = remoteBlocks ?: emptyList()
        val changed = hashResult.blocks.indices.filter { i -> i >= remoteB.size || remoteB[i] != hashResult.blocks[i] }
        val changedBytes = SafFileOps.readBlocks(context, localDoc, changed, BLOCK_SIZE)
        val meta = UploadMeta(
            size = SafFileOps.size(localDoc),
            mtime = SafFileOps.lastModifiedSeconds(localDoc),
            blockSize = BLOCK_SIZE,
            blocks = hashResult.blocks,
            changed = changed,
            expectedServerSha256 = expected,
        )
        return client.upload(folderId, relPath, meta, changedBytes)
    }

    private suspend fun downloadInto(root: DocumentFile, folderId: String, relPath: String) {
        val tmp = SafFileOps.createTempTarget(root, relPath)
        context.contentResolver.openOutputStream(tmp.uri)!!.use { out ->
            client.download(folderId, relPath, out)
        }
        SafFileOps.commitTempTarget(root, relPath, tmp)
    }

    /** Best-effort -- darf den bereits abgeschlossenen Sync-Lauf nicht
     * nachtraeglich als fehlgeschlagen erscheinen lassen, wenn der
     * Server beim Melden kurz nicht erreichbar ist. Nur wenn sich
     * tatsaechlich etwas geaendert hat, sonst wuerde jeder Lauf ohne
     * Aenderungen das Activity Log zuspammen (siehe T-212-SYNC). */
    private suspend fun logSummary(folderId: String, result: SyncResult) {
        val total = result.uploaded.size + result.downloaded.size +
            result.deletedLocal.size + result.deletedRemote.size
        if (total == 0) return
        try {
            client.logSync(
                folderId,
                SyncSummary(
                    uploaded = result.uploaded.size,
                    downloaded = result.downloaded.size,
                    deletedLocal = result.deletedLocal.size,
                    deletedRemote = result.deletedRemote.size,
                    conflicts = result.conflicts.size,
                ),
            )
        } catch (_: Exception) {
            // absichtlich verschluckt, siehe Doc-Kommentar
        }
    }
}
