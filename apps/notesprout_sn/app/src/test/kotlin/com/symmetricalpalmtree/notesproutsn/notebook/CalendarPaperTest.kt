package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.template.TemplateImport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds a page of paper drawn by another process has to pass before it becomes a template row
 * (arc 31 / HV5). Pure, so the one thing standing between an extension's bytes and this app's
 * database is JVM-tested rather than only walked.
 */
class CalendarPaperTest {

    private val w = 1404
    private val h = 1872

    @Test
    fun `paper the size of the page is accepted`() {
        assertTrue(CalendarPaper.accept(40_000, w, h, w, h))
    }

    @Test
    fun `paper at the blob ceiling is accepted and one byte over is not`() {
        assertTrue(CalendarPaper.accept(TemplateImport.MAX_BLOB_BYTES, w, h, w, h))
        assertFalse(CalendarPaper.accept(TemplateImport.MAX_BLOB_BYTES + 1, w, h, w, h))
    }

    @Test
    fun `a picture of another size is refused`() {
        // Too small, too large, and the page's own size transposed — none of the three is the page
        // the ink was written on, and the ink lands 1 to 1.
        assertFalse(CalendarPaper.accept(40_000, w - 1, h, w, h))
        assertFalse(CalendarPaper.accept(40_000, w, h + 1, w, h))
        assertFalse(CalendarPaper.accept(40_000, h, w, w, h))
    }

    @Test
    fun `nothing about a zero is a special case`() {
        assertFalse(CalendarPaper.accept(0, w, h, w, h))
        assertFalse(CalendarPaper.accept(-1, w, h, w, h))
        assertFalse(CalendarPaper.accept(40_000, 0, 0, w, h))
        assertFalse(CalendarPaper.accept(40_000, w, h, 0, h))
        assertFalse(CalendarPaper.accept(40_000, w, h, w, 0))
        assertFalse(CalendarPaper.accept(40_000, 0, 0, 0, 0))
    }
}
