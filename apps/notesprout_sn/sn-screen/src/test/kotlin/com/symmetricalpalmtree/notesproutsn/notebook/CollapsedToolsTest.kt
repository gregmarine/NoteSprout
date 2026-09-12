package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.screen.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapsed chrome's rules (arc 36 / C1): the corner button's glyph per tool, the armed
 * mini-toolbar button, and the outside-tap dismissal — the one with a trap in it (the corner
 * button must be excluded or its own tap would close-then-reopen the rows).
 */
class CollapsedToolsTest {

    @Test fun `the order is pen, point eraser, lasso eraser, lasso`() {
        assertEquals(listOf(Tool.PEN, Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO), CollapsedTools.ORDER)
    }

    @Test fun `each tool wears its own glyph`() {
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.PEN))
        assertEquals(R.drawable.ic_eraser, CollapsedTools.iconFor(Tool.ERASER))
        assertEquals(R.drawable.ic_lasso_eraser, CollapsedTools.iconFor(Tool.LASSO_ERASER))
        assertEquals(R.drawable.ic_lasso, CollapsedTools.iconFor(Tool.LASSO))
    }

    @Test fun `the lasso wears the clipboard mark while objects are on the clipboard`() {
        assertEquals(R.drawable.ic_lasso_clipboard, CollapsedTools.iconFor(Tool.LASSO, clipboardLoaded = true))
        // Only the lasso: a loaded clipboard changes nothing about the other glyphs.
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.PEN, clipboardLoaded = true))
        assertEquals(R.drawable.ic_eraser, CollapsedTools.iconFor(Tool.ERASER, clipboardLoaded = true))
    }

    @Test fun `NONE wears the pen and arms no button`() {
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.NONE))
        assertNull(CollapsedTools.selectedFor(Tool.NONE))
    }

    @Test fun `every ordered tool selects itself`() {
        CollapsedTools.ORDER.forEach { assertEquals(it, CollapsedTools.selectedFor(it)) }
    }

    @Test fun `an overflow of one or two sits on the mini toolbar - three or more go behind the dots`() {
        assertFalse(CollapsedTools.overflowInline(0))
        assertTrue(CollapsedTools.overflowInline(1))
        assertTrue(CollapsedTools.overflowInline(2))
        assertFalse(CollapsedTools.overflowInline(3))
        assertFalse(CollapsedTools.overflowInline(8))
    }

    @Test fun `nothing showing - nothing to dismiss`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = false, onChrome = false, keep = false))
    }

    @Test fun `a contact on the corner button or the rows never dismisses - the button's click toggles`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = true, onChrome = true, keep = false))
    }

    @Test fun `a contact inside a sub-bar hung off the rows keeps them`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = true, onChrome = false, keep = true))
    }

    @Test fun `any other contact takes the rows down`() {
        assertTrue(CollapsedTools.outsideTapDismisses(showing = true, onChrome = false, keep = false))
    }
}
