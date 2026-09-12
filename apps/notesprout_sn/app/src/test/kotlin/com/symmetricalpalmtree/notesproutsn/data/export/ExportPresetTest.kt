package com.symmetricalpalmtree.notesproutsn.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset blob's grammar (arc 31 / HV3): what survives a round trip, and what [ExportPreset.decode]
 * refuses. The refusals are the point — a blob this build cannot vouch for is one preset missing
 * from a radio list, never a screen that crashes.
 */
class ExportPresetTest {

    private fun roundTrip(preset: ExportPreset): ExportPreset? =
        ExportPreset.decode(ExportPreset.encode(preset))

    @Test
    fun `every field survives the round trip`() {
        val preset = ExportPreset(
            exporter = "com.example.pdf",
            values = mapOf("pageTemplate" to "1", "quality" to "high"),
            documentSource = true,
            destination = ExportPreset.DESTINATION_CLOUD,
            cloudPath = listOf("Exports", "Term 1"),
        )
        val back = roundTrip(preset)
        assertEquals(preset, back)
        assertEquals(ExportPreset.VERSION, back?.version)
    }

    @Test
    fun `a null cloud path and empty values survive as themselves`() {
        val preset = ExportPreset(exporter = "com.example.png")
        val back = roundTrip(preset)
        assertNotNull(back)
        assertNull(back?.cloudPath)
        assertTrue(back?.values.orEmpty().isEmpty())
        assertEquals(ExportPreset.DESTINATION_LOCAL, back?.destination)
    }

    @Test
    fun `nothing at all decodes to nothing`() {
        assertNull(ExportPreset.decode(null))
        assertNull(ExportPreset.decode(ByteArray(0)))
    }

    @Test
    fun `garbage never throws and never decodes`() {
        assertNull(ExportPreset.decode(byteArrayOf(0x7f, 0x00, 0x11, 0x22)))
        assertNull(ExportPreset.decode("not json at all".toByteArray()))
        assertNull(ExportPreset.decode("{\"exporter\":".toByteArray()))
    }

    @Test
    fun `a grammar this build does not know is refused`() {
        val newer = "{\"version\":2,\"exporter\":\"com.example.pdf\"}".toByteArray()
        assertNull(ExportPreset.decode(newer))
        val older = "{\"version\":0,\"exporter\":\"com.example.pdf\"}".toByteArray()
        assertNull(ExportPreset.decode(older))
    }

    @Test
    fun `an unreadable destination is refused rather than guessed`() {
        val blob = "{\"version\":1,\"exporter\":\"com.example.pdf\",\"destination\":\"FTP\"}".toByteArray()
        assertNull(ExportPreset.decode(blob))
    }

    @Test
    fun `a preset naming no exporter is refused`() {
        assertNull(ExportPreset.decode("{\"version\":1,\"exporter\":\"\"}".toByteArray()))
        assertNull(ExportPreset.decode("{\"version\":1,\"exporter\":\"   \"}".toByteArray()))
    }

    /** The additive-growth rule: a field a newer build wrote is ignored, not fatal. */
    @Test
    fun `unknown keys are ignored`() {
        val blob = ("{\"version\":1,\"exporter\":\"com.example.pdf\",\"somethingNew\":42," +
            "\"destination\":\"CLOUD\",\"cloudPath\":[\"Exports\"]}").toByteArray()
        val back = ExportPreset.decode(blob)
        assertEquals("com.example.pdf", back?.exporter)
        assertEquals(listOf("Exports"), back?.cloudPath)
    }
}
