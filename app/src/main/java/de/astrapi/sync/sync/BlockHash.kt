package de.astrapi.sync.sync

import java.io.InputStream
import java.security.MessageDigest

/** Muss exakt zum Server (astrapi_sync/api/block_hash.py) und zum
 * Python-Client (astrapi_sync_cli/block_hash.py) passen -- gleiche
 * Blockgröße, gleiche Hash-Funktion, sonst stimmen die
 * Positions-Vergleiche nicht. */
object BlockHash {
    const val DEFAULT_BLOCK_SIZE = 1 shl 20 // 1 MiB

    data class HashResult(val sha256: String, val blocks: List<String>)

    /** Liest [input] einmal komplett, liefert Gesamt- UND Block-Hashes
     * aus demselben Lesedurchgang -- Server und Python-Client lesen für
     * beides bislang getrennt (siehe T-220-SYNC), hier von Anfang an
     * vermieden. */
    fun hash(input: InputStream, blockSize: Int = DEFAULT_BLOCK_SIZE): HashResult {
        val whole = MessageDigest.getInstance("SHA-256")
        val blocks = mutableListOf<String>()
        val buffer = ByteArray(blockSize)
        while (true) {
            val n = readFully(input, buffer)
            if (n <= 0) break
            whole.update(buffer, 0, n)
            val blockDigest = MessageDigest.getInstance("SHA-256")
            blockDigest.update(buffer, 0, n)
            blocks.add(blockDigest.digest().toHexString())
            if (n < blockSize) break
        }
        return HashResult(whole.digest().toHexString(), blocks)
    }

    /** Liest so lange, bis der Puffer voll ist oder der Stream endet --
     * ein einzelner InputStream.read() garantiert nicht, den Puffer
     * vollständig zu füllen. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
