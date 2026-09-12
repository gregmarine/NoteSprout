package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.export.ExportNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Save-as-template seed (arc 31 / HV2): a heading names the paper, a page without one is named
 * by its position, and the answer is never empty — a name dialog that opens blank has thrown away
 * the one thing the app already knew about the page.
 *
 * [PageRaster], the other half of the phase, has no test here on purpose: it is `Bitmap`, `Canvas`
 * and the platform encoder end to end, all stubbed off-device.
 */
class TemplateSeedNameTest {

    @Test
    fun `a heading seeds its text`() {
        assertEquals("Monday standup", TemplateSeedName.of("Monday standup", 3))
    }

    @Test
    fun `a heading is trimmed`() {
        assertEquals("Monday standup", TemplateSeedName.of("   Monday standup  ", 3))
    }

    @Test
    fun `no heading seeds the page number`() {
        assertEquals("page 7", TemplateSeedName.of(null, 7))
    }

    @Test
    fun `a blank heading seeds the page number`() {
        assertEquals("page 1", TemplateSeedName.of("   ", 1))
    }

    @Test
    fun `a long heading is capped`() {
        val long = "n".repeat(ExportNaming.MAX_TITLE_CHARS + 40)
        val seeded = TemplateSeedName.of(long, 2)
        assertEquals(ExportNaming.MAX_TITLE_CHARS, seeded.length)
        assertTrue(long.startsWith(seeded))
    }

    @Test
    fun `a capped heading is trimmed again`() {
        // The cut can land on a space; a name that ends in one reads as a typo.
        val long = "n".repeat(ExportNaming.MAX_TITLE_CHARS - 1) + "  tail"
        assertEquals("n".repeat(ExportNaming.MAX_TITLE_CHARS - 1), TemplateSeedName.of(long, 2))
    }

    @Test
    fun `an unplaced page is named page`() {
        assertEquals("page", TemplateSeedName.of(null, 0))
        assertEquals("page", TemplateSeedName.of("", -1))
    }

    @Test
    fun `characters outside the name charset are dropped`() {
        assertEquals("Q3 plan", TemplateSeedName.of("Q3: plan?", 2))
        assertEquals("Meeting notes", TemplateSeedName.of("Meeting — notes", 2))
        assertEquals("Meeting - notes", TemplateSeedName.of("Meeting - notes", 2))
    }

    @Test
    fun `a heading that strips to nothing seeds the page number`() {
        assertEquals("page 4", TemplateSeedName.of("？！", 4))
    }

    @Test
    fun `a dot-only heading is not offered`() {
        assertEquals("page 4", TemplateSeedName.of("..", 4))
    }

    @Test
    fun `every seed passes NameRules`() {
        for (h in listOf("Q3: plan?", "a/b\\c", "tabs\tand\nlines", "..", "  ", null)) {
            assertEquals(null, com.symmetricalpalmtree.notesproutsn.library.NameRules.validate(TemplateSeedName.of(h, 1)))
        }
    }
}
