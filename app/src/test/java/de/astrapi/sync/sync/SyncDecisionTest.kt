package de.astrapi.sync.sync

import de.astrapi.sync.data.KnownFileEntity
import de.astrapi.sync.network.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDecisionTest {

    private fun remoteEntry(sha256: String, path: String = "a.txt") =
        FileEntry(path = path, size = 10, mtime = 0.0, blockSize = 1024, sha256 = sha256, blocks = listOf(sha256))

    private fun known(sha256: String, path: String = "a.txt") =
        KnownFileEntity(folderId = "1", path = path, sha256 = sha256, size = 10)

    @Test
    fun `weder lokal noch remote, nur bekannt ergibt vergessen`() {
        val action = SyncDecision.decide(remote = null, localExists = false, localHash = null, lastKnown = known("x"))
        assertEquals(FileAction.Forget, action)
    }

    @Test
    fun `neue Datei nur lokal, kein bekannter Stand ergibt hochladen`() {
        val action = SyncDecision.decide(remote = null, localExists = true, localHash = "abc", lastKnown = null)
        assertEquals(FileAction.UploadNew, action)
    }

    @Test
    fun `Server hat Datei geloescht, bekannter Hash stimmt ergibt lokal nachziehen`() {
        val action = SyncDecision.decide(
            remote = null, localExists = true, localHash = "abc", lastKnown = known("abc"),
        )
        assertEquals(FileAction.DeleteLocal, action)
    }

    @Test
    fun `lokal seit Server-Loeschung veraendert ergibt trotzdem hochladen (wiederherstellen)`() {
        val action = SyncDecision.decide(
            remote = null, localExists = true, localHash = "neu", lastKnown = known("alt"),
        )
        assertEquals(FileAction.UploadNew, action)
    }

    @Test
    fun `neue Datei nur remote, kein bekannter Stand ergibt herunterladen`() {
        val action = SyncDecision.decide(remote = remoteEntry("abc"), localExists = false, localHash = null, lastKnown = null)
        assertEquals(FileAction.DownloadNew, action)
    }

    @Test
    fun `lokal geloescht, bekannter Hash stimmt mit Server ueberein ergibt dem Server mitteilen`() {
        val action = SyncDecision.decide(
            remote = remoteEntry("abc"), localExists = false, localHash = null, lastKnown = known("abc"),
        )
        assertEquals(FileAction.DeleteRemote, action)
    }

    @Test
    fun `beide Seiten identisch ergibt nur Zustand aktualisieren`() {
        val action = SyncDecision.decide(
            remote = remoteEntry("abc"), localExists = true, localHash = "abc", lastKnown = known("alt"),
        )
        assertEquals(FileAction.KeepInSync, action)
    }

    @Test
    fun `beide Seiten seit bekanntem Stand geaendert ergibt Konflikt`() {
        val action = SyncDecision.decide(
            remote = remoteEntry("serverNeu"), localExists = true, localHash = "lokalNeu", lastKnown = known("alt"),
        )
        assertEquals(FileAction.Conflict, action)
    }

    @Test
    fun `T-215-SYNC -- divergenter Erst-Sync ohne bekannten Stand gilt als Konflikt, nicht als stilles Ueberschreiben`() {
        // Genau der Fall, der im Python-Client (astrapi_sync_cli engine.py)
        // nicht als Konflikt erkannt wurde: last_known ist null, aber beide
        // Seiten haben bereits unterschiedlichen Inhalt -- die zusätzliche
        // "last_known != null"-Bedingung dort ließ die lokale Version
        // stillschweigend gewinnen. Hier MUSS das ein Konflikt sein.
        val action = SyncDecision.decide(
            remote = remoteEntry("serverInhalt"),
            localExists = true,
            localHash = "lokalerInhalt",
            lastKnown = null,
        )
        assertEquals(FileAction.Conflict, action)
    }

    @Test
    fun `nur remote geaendert ergibt herunterladen`() {
        val action = SyncDecision.decide(
            remote = remoteEntry("serverNeu"), localExists = true, localHash = "alt", lastKnown = known("alt"),
        )
        assertEquals(FileAction.DownloadChanged, action)
    }

    @Test
    fun `nur lokal geaendert ergibt hochladen`() {
        val action = SyncDecision.decide(
            remote = remoteEntry("alt"), localExists = true, localHash = "lokalNeu", lastKnown = known("alt"),
        )
        assertEquals(FileAction.UploadChanged, action)
    }
}
