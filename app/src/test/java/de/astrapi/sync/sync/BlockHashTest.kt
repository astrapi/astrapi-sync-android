package de.astrapi.sync.sync

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BlockHashTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `Gesamt-Hash stimmt mit direkt berechnetem SHA256 ueberein`() {
        val data = "hallo astrapi-sync".toByteArray()
        val result = BlockHash.hash(ByteArrayInputStream(data), blockSize = 5)
        assertEquals(sha256Hex(data), result.sha256)
    }

    @Test
    fun `Blockanzahl und -groesse passen zur Eingabelaenge`() {
        val blockSize = 4
        val data = ByteArray(10) { it.toByte() } // 10 Bytes -> 3 Bloecke (4,4,2)
        val result = BlockHash.hash(ByteArrayInputStream(data), blockSize)
        assertEquals(3, result.blocks.size)
        assertEquals(sha256Hex(data.copyOfRange(0, 4)), result.blocks[0])
        assertEquals(sha256Hex(data.copyOfRange(4, 8)), result.blocks[1])
        assertEquals(sha256Hex(data.copyOfRange(8, 10)), result.blocks[2])
    }

    @Test
    fun `leere Eingabe ergibt keine Bloecke, aber den leeren SHA256`() {
        val result = BlockHash.hash(ByteArrayInputStream(ByteArray(0)))
        assertEquals(0, result.blocks.size)
        assertEquals(sha256Hex(ByteArray(0)), result.sha256)
    }

    @Test
    fun `unterschiedlicher Inhalt ergibt unterschiedliche Hashes`() {
        val a = BlockHash.hash(ByteArrayInputStream("aaa".toByteArray()), blockSize = 1024)
        val b = BlockHash.hash(ByteArrayInputStream("bbb".toByteArray()), blockSize = 1024)
        assertNotEquals(a.sha256, b.sha256)
    }
}
