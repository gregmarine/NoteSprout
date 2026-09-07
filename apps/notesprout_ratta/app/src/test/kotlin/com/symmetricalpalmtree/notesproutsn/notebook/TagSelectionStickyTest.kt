package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The lasso's Tag button against a lone sticky (arc 28 / H5). A note carries no words the page can
 * see — its ink lives inside it, in the note's own space, and the icon on the page is a glyph — so
 * there is nothing to make a tag of and nothing to recognize. [SelectionMode.STICKY] is
 * [TagFlow.NONE], and the button is GONE even with a tag manager installed.
 */
class TagSelectionStickyTest {

    @Test
    fun `a lone sticky has no tag flow`() {
        assertEquals(TagFlow.NONE, TagSelection.flowFor(SelectionMode.STICKY))
    }

    @Test
    fun `and so it is never offered, tag manager or not`() {
        assertFalse(TagSelection.offered(SelectionMode.STICKY, tagsAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.STICKY, tagsAvailable = false))
    }

    @Test
    fun `the offered set is still exactly heading and ink`() {
        // The arc-28 kinds added three modes and none of them widened this button.
        val offered = SelectionMode.entries.filter { TagSelection.offered(it, tagsAvailable = true) }
        assertEquals(listOf(SelectionMode.STROKES, SelectionMode.HEADING), offered)
    }
}
