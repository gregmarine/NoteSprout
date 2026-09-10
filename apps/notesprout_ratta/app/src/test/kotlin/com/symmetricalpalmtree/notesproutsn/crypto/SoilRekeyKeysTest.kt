package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the re-key's **source** side spells its key (arc 34 / L17). The transform itself is
 * SQLCipher and cannot run on the JVM — the debug menu's *Rekey one notebook round-trip*
 * (`RekeyProbe`) is its on-device pin, as it has been since arc 26 / U2. What is pure here is the
 * choice: a caller that already holds the file's verified raw key hands it over, and the two KDFs
 * the source side would otherwise pay (once to absorb the WAL, once to attach) are not paid at all.
 */
class SoilRekeyKeysTest {

    @Test
    fun `a raw key is spelled as SQLCiphers blob literal`() {
        val raw = byteArrayOf(0x01, 0x12, 0x23, 0x34.toByte(), 0xff.toByte())
        assertEquals("x'01122334ff'", SoilRekey.attachLiteral("ignored", raw))
    }

    @Test
    fun `no raw key falls back to the passphrase, quoted`() {
        assertEquals("'walkpass'", SoilRekey.attachLiteral("walkpass", null))
    }

    @Test
    fun `a passphrase with a quote in it is doubled, never truncated`() {
        assertEquals("'it''s mine'", SoilRekey.attachLiteral("it's mine", null))
    }
}
