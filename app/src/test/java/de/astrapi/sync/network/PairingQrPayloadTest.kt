package de.astrapi.sync.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrPayloadTest {

    @Test
    fun `parst gueltigen Server-Payload`() {
        val payload = PairingQrPayload.parse(
            """{"server_url": "http://sync.simpsons.lan:5004", "token": "abc123"}""",
        )
        assertEquals("http://sync.simpsons.lan:5004", payload?.serverUrl)
        assertEquals("abc123", payload?.token)
    }

    @Test
    fun `ignoriert unbekannte Zusatzfelder`() {
        val payload = PairingQrPayload.parse(
            """{"server_url": "http://x", "token": "y", "extra": "z"}""",
        )
        assertEquals("http://x", payload?.serverUrl)
        assertEquals("y", payload?.token)
    }

    @Test
    fun `liefert null bei kaputtem JSON`() {
        assertNull(PairingQrPayload.parse("nicht mal JSON"))
    }

    @Test
    fun `liefert null bei fehlendem Token-Feld`() {
        assertNull(PairingQrPayload.parse("""{"server_url": "http://x"}"""))
    }

    @Test
    fun `liefert null bei leerem Token`() {
        assertNull(PairingQrPayload.parse("""{"server_url": "http://x", "token": ""}"""))
    }

    @Test
    fun `liefert null fuer QR-Codes ohne Bezug zu astrapi-sync`() {
        assertNull(PairingQrPayload.parse("https://example.com"))
    }
}
