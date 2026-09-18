package de.astrapi.sync.sync

import java.io.File
import java.io.FileOutputStream
import de.astrapi.sync.data.KnownDirEntity
import de.astrapi.sync.data.KnownFileEntity
import de.astrapi.sync.data.PendingConflictEntity
import de.astrapi.sync.data.SyncStateDao
import de.astrapi.sync.network.ApiClient
import de.astrapi.sync.network.ConflictException
import de.astrapi.sync.network.FileEntry
import de.astrapi.sync.network.SyncSummary
import de.astrapi.sync.network.UploadMeta
import de.astrapi.sync.network.UploadResult

/** Kotlin-Port von astrapi_sync_cli/engine.py::sync_folder_once() --
 * Drei-Wege-Vergleich (lokal / Server / letzter bekannter Stand aus
 * SyncStateDao). Übernimmt bewusst eine Korrektur gegenüber dem
 * Python-Original, die dort erst nachträglich als Lücke gefunden wurde
 * (siehe astrapi-hub-Vault, projects/sync/):
 *
 * - T-215-SYNC: existiert eine Datei beim allerersten Sync (kein
 *   bekannter Stand) bereits mit UNTERSCHIEDLICHEM Inhalt auf beiden
 *   Seiten, wird das hier als Konflikt behandelt statt die lokale
 *   Version stillschweigend gewinnen zu lassen.
 *
 * Anders als der Python-Client (der einen echten Konflikt automatisch
 * auflöst: Sicherungskopie + Server-Version übernehmen) pausiert diese
 * Engine den Sync für die betroffene Datei -- weder lokale noch
 * Server-Version werden angefasst, stattdessen landet eine Zeile in
 * pending_conflicts (siehe PendingConflictEntity), bis der Nutzer in der
 * Konflikt-Liste explizit "meine Version" oder "Server-Version" wählt
 * (resolveConflict()). Nutzerentscheidung 2026-09-17, astrapi-hub-Vault:
 * automatische Auflösung war zu intransparent, gerade bei
 * Hintergrund-Syncs ohne offene App. */
class SyncEngine(
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
        /** Alle Pfade, die in DIESEM Lauf als (weiterhin) im Konflikt
         * erkannt wurden -- unabhängig davon, ob der Konflikt neu ist
         * oder schon aus einem früheren Lauf in pending_conflicts steht. */
        val conflicts: List<String> = emptyList(),
        /** Teilmenge von [conflicts]: Pfade, die VOR diesem Lauf noch
         * nicht in pending_conflicts standen -- Grundlage für
         * SyncWorker's Benachrichtigung, damit ein einmal erkannter,
         * weiterhin ungelöster Konflikt nicht bei jedem periodischen Lauf
         * erneut eine Notification auslöst. */
        val newConflicts: List<String> = emptyList(),
        val dirsCreatedLocal: List<String> = emptyList(),
        val dirsCreatedRemote: List<String> = emptyList(),
        val dirsDeletedLocal: List<String> = emptyList(),
        val dirsDeletedRemote: List<String> = emptyList(),
    )

    suspend fun syncFolderOnce(
        folderId: String,
        rootDir: File,
        deviceLabel: String,
        confirmDeletes: Boolean = false,
        maxAutoDelete: Int = MAX_AUTO_DELETE,
    ): SyncResult {
        val root = FileOps.root(rootDir.absolutePath)
        val index = client.getIndex(folderId)
        val remoteIndex: Map<String, FileEntry> = index.files.associateBy { it.path }
        val remoteDirs = index.dirs.toSet()

        val localFiles = FileOps.listLocalFiles(root)
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
        val newConflicts = mutableListOf<String>()

        val allPaths = (remoteIndex.keys + localFiles.keys + knownFiles.keys).toSortedSet()
        for (relPath in allPaths) {
            val remote = remoteIndex[relPath]
            val localDoc = localFiles[relPath]
            val lastKnown = knownFiles[relPath]
            val localHash = localDoc?.let { FileOps.hashDocument(it, BLOCK_SIZE).sha256 }

            when (SyncDecision.decide(remote, localDoc != null, localHash, lastKnown)) {
                FileAction.Forget -> knownFiles.remove(relPath)

                FileAction.DeleteLocal -> {
                    FileOps.deleteFile(localDoc!!)
                    knownFiles.remove(relPath)
                    deletedLocal.add(relPath)
                }

                FileAction.UploadNew -> {
                    val info = uploadFile(folderId, relPath, localDoc!!, remoteBlocks = null, expected = null)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, info.sha256, FileOps.size(localDoc))
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
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, localHash!!, FileOps.size(localDoc!!))
                }

                FileAction.Conflict -> {
                    // Bewusst KEIN Zugriff auf lokale/Server-Datei und KEIN
                    // Update von knownFiles[relPath] -- die Datei bleibt
                    // unangetastet, bis der Nutzer in der Konflikt-Liste
                    // entscheidet (siehe Klassen-Doc-Kommentar). Bleibt der
                    // Eintrag dadurch unverändert, liefert SyncDecision beim
                    // nächsten Lauf wieder Conflict für denselben Pfad --
                    // gewollt, damit die Datei so lange "eingefroren" bleibt.
                    val isNew = recordPendingConflict(folderId, relPath, localHash!!, FileOps.size(localDoc!!), remote!!)
                    conflicts.add(relPath)
                    if (isNew) newConflicts.add(relPath)
                }

                FileAction.DownloadChanged -> {
                    downloadInto(root, folderId, relPath)
                    knownFiles[relPath] = KnownFileEntity(folderId, relPath, remote!!.sha256, remote.size)
                    downloaded.add(relPath)
                }

                FileAction.UploadChanged -> {
                    try {
                        val info = uploadFile(folderId, relPath, localDoc!!, remote!!.blocks, remote.sha256)
                        knownFiles[relPath] = KnownFileEntity(folderId, relPath, info.sha256, FileOps.size(localDoc))
                        uploaded.add(relPath)
                    } catch (e: ConflictException) {
                        // Wettlauf zwischen Index-Abruf und Upload: Server hat
                        // sich zwischenzeitlich veraendert -> wie einen echten
                        // Konflikt behandeln (siehe FileAction.Conflict oben,
                        // gleiche Begruendung: nichts anfassen, Nutzer
                        // entscheidet in der Konflikt-Liste).
                        val fresh = client.getIndex(folderId).files.firstOrNull { it.path == relPath }
                        if (fresh != null) {
                            val freshLocalHash = FileOps.hashDocument(localDoc!!, BLOCK_SIZE).sha256
                            val isNew = recordPendingConflict(folderId, relPath, freshLocalHash, FileOps.size(localDoc), fresh)
                            conflicts.add(relPath)
                            if (isNew) newConflicts.add(relPath)
                        } else {
                            // T-222-SYNC-Fix: Server hat die Datei
                            // zwischenzeitlich geloescht -- veralteten
                            // known-Eintrag verwerfen statt stehen zu lassen,
                            // kein Konflikt mehr (naechster Lauf laedt die
                            // lokale Version als UploadNew wieder hoch).
                            knownFiles.remove(relPath)
                        }
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
            newConflicts = newConflicts,
            dirsCreatedLocal = dirResult.createdLocal,
            dirsCreatedRemote = dirResult.createdRemote,
            dirsDeletedLocal = dirResult.deletedLocal,
            dirsDeletedRemote = dirResult.deletedRemote,
        )
        logSummary(folderId, result)
        return result
    }

    /** Legt eine pending_conflicts-Zeile an oder aktualisiert sie, falls
     * sich lokaler oder Server-Stand seit dem letzten Lauf nochmal
     * geändert haben (z.B. Nutzer bearbeitet die Datei weiter, während
     * der Konflikt schon offen ist). Gibt zurück, ob der Konflikt VORHER
     * noch nicht bekannt war -- Grundlage für die Benachrichtigung im
     * SyncWorker, siehe SyncResult.newConflicts. Unverändert gebliebene,
     * bereits bekannte Konflikte werden nicht neu geschrieben, damit
     * detectedAt stabil bleibt (Sortierung in der Konflikt-Liste). */
    private suspend fun recordPendingConflict(
        folderId: String,
        relPath: String,
        localSha256: String,
        localSize: Long,
        remote: FileEntry,
    ): Boolean {
        val existing = dao.pendingConflict(folderId, relPath)
        val unchanged = existing != null &&
            existing.localSha256 == localSha256 &&
            existing.remoteSha256 == remote.sha256
        if (!unchanged) {
            dao.upsertPendingConflict(
                PendingConflictEntity(
                    folderId = folderId,
                    path = relPath,
                    localSha256 = localSha256,
                    localSize = localSize,
                    remoteSha256 = remote.sha256,
                    remoteSize = remote.size,
                    detectedAt = System.currentTimeMillis(),
                ),
            )
        }
        return existing == null
    }

    /** Löst einen in pending_conflicts stehenden Konflikt gemäß
     * Nutzerentscheidung auf -- aufgerufen von ConflictsViewModel, nicht
     * Teil des regulären syncFolderOnce()-Laufs. [keepLocal] = true:
     * lokale Version gewinnt (überschreibt den Server-Stand); false:
     * Server-Version gewinnt (lokale Version wird vorher sicherheitshalber
     * als .syncconflict-Kopie gesichert, siehe Klassen-Doc-Kommentar --
     * kein stiller Datenverlust, obwohl der Nutzer aktiv "Server" gewählt
     * hat). Holt den Server-Stand jeweils frisch, da seit dem Erkennen des
     * Konflikts (ggf. beim letzten Hintergrund-Lauf) weitere Zeit
     * vergangen sein kann. */
    suspend fun resolveConflict(folderId: String, rootDir: File, deviceLabel: String, relPath: String, keepLocal: Boolean) {
        val root = FileOps.root(rootDir.absolutePath)
        val localDoc = FileOps.findFile(root, relPath)

        if (keepLocal) {
            if (localDoc == null) {
                // Nutzer hat die lokale Datei zwischenzeitlich selbst
                // gelöscht -- nichts mehr zu behalten, Konflikt einfach
                // fallenlassen (naechster Lauf sieht dann wieder die
                // Server-Version als "nur remote vorhanden").
                dao.deletePendingConflict(folderId, relPath)
                return
            }
            val freshRemote = client.getIndex(folderId).files.firstOrNull { it.path == relPath }
            try {
                val info = uploadFile(folderId, relPath, localDoc, freshRemote?.blocks, freshRemote?.sha256)
                dao.upsertFiles(listOf(KnownFileEntity(folderId, relPath, info.sha256, FileOps.size(localDoc))))
                dao.deletePendingConflict(folderId, relPath)
            } catch (e: ConflictException) {
                // Server hat sich seit freshRemote schon wieder geaendert --
                // Konflikt bleibt offen, mit aktualisiertem Server-Stand,
                // statt den Upload-Versuch stillschweigend zu verwerfen.
                val fresh2 = client.getIndex(folderId).files.firstOrNull { it.path == relPath }
                if (fresh2 != null) {
                    recordPendingConflict(
                        folderId,
                        relPath,
                        FileOps.hashDocument(localDoc, BLOCK_SIZE).sha256,
                        FileOps.size(localDoc),
                        fresh2,
                    )
                }
                throw e
            }
        } else {
            val freshRemote = client.getIndex(folderId).files.firstOrNull { it.path == relPath }
            if (freshRemote == null) {
                // Server hat die Datei zwischenzeitlich geloescht -- keine
                // Server-Version mehr zu uebernehmen, Konflikt fallenlassen
                // (naechster Lauf laedt die lokale Version als UploadNew hoch).
                dao.deletePendingConflict(folderId, relPath)
                return
            }
            if (localDoc != null) {
                FileOps.conflictCopy(root, relPath, localDoc, deviceLabel)
            }
            downloadInto(root, folderId, relPath)
            dao.upsertFiles(listOf(KnownFileEntity(folderId, relPath, freshRemote.sha256, freshRemote.size)))
            dao.deletePendingConflict(folderId, relPath)
        }
    }

    private fun planDeletions(
        remoteIndex: Map<String, FileEntry>,
        localFiles: Map<String, File>,
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
                val localHash = FileOps.hashDocument(localDoc).sha256
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
        root: File,
        remoteDirs: Set<String>,
        knownDirs: MutableSet<String>,
    ): DirSyncResult {
        val localDirs = FileOps.listLocalEmptyDirs(root).toSet()
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
                        val dir = FileOps.findDir(root, relPath)
                        if (dir != null && FileOps.deleteEmptyDir(dir)) {
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
                        FileOps.findOrCreateDir(root, relPath)
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
        localDoc: File,
        remoteBlocks: List<String>?,
        expected: String?,
    ): UploadResult {
        val hashResult = FileOps.hashDocument(localDoc, BLOCK_SIZE)
        val remoteB = remoteBlocks ?: emptyList()
        val changed = hashResult.blocks.indices.filter { i -> i >= remoteB.size || remoteB[i] != hashResult.blocks[i] }
        val changedBytes = FileOps.readBlocks(localDoc, changed, BLOCK_SIZE)
        val meta = UploadMeta(
            size = FileOps.size(localDoc),
            mtime = FileOps.lastModifiedSeconds(localDoc),
            blockSize = BLOCK_SIZE,
            blocks = hashResult.blocks,
            changed = changed,
            expectedServerSha256 = expected,
        )
        return client.upload(folderId, relPath, meta, changedBytes)
    }

    private suspend fun downloadInto(root: File, folderId: String, relPath: String) {
        val tmp = FileOps.createTempTarget(root, relPath)
        FileOutputStream(tmp).use { out ->
            client.download(folderId, relPath, out)
        }
        FileOps.commitTempTarget(root, relPath, tmp)
    }

    /** Best-effort -- darf den bereits abgeschlossenen Sync-Lauf nicht
     * nachtraeglich als fehlgeschlagen erscheinen lassen, wenn der
     * Server beim Melden kurz nicht erreichbar ist. Nur wenn sich
     * tatsaechlich etwas geaendert hat, sonst wuerde jeder Lauf ohne
     * Aenderungen das Activity Log zuspammen (siehe T-212-SYNC). */
    private suspend fun logSummary(folderId: String, result: SyncResult) {
        val total = result.uploaded.size + result.downloaded.size +
            result.deletedLocal.size + result.deletedRemote.size +
            result.dirsCreatedLocal.size + result.dirsCreatedRemote.size +
            result.dirsDeletedLocal.size + result.dirsDeletedRemote.size
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
                    uploadedPaths = result.uploaded,
                    downloadedPaths = result.downloaded,
                    deletedLocalPaths = result.deletedLocal,
                    deletedRemotePaths = result.deletedRemote,
                    conflictPaths = result.conflicts,
                    dirsCreatedLocalPaths = result.dirsCreatedLocal,
                    dirsCreatedRemotePaths = result.dirsCreatedRemote,
                    dirsDeletedLocalPaths = result.dirsDeletedLocal,
                    dirsDeletedRemotePaths = result.dirsDeletedRemote,
                ),
            )
        } catch (_: Exception) {
            // absichtlich verschluckt, siehe Doc-Kommentar
        }
    }
}
