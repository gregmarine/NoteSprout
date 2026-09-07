package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D5's table, the sticky row (arc 28 / H5): a lone sticky is [SelectionMode.STICKY] — the base bar
 * with no button of its own, because a note's verb is a finger tap on its icon.
 *
 * The `isSticky` predicate arrived with a `{ false }` default so H1–H4's callers went on meaning
 * exactly what they meant; the last test here is what keeps that promise honest.
 */
class SelectionModesStickyTest {

    private val headings = setOf("h1")
    private val links = setOf("l1")
    private val texts = setOf("t1")
    private val shapes = setOf("s1")
    private val stickies = setOf("k1", "k2")

    private fun classify(strokes: Int, vararg contentIds: String): SelectionMode =
        SelectionModes.classify(
            strokes, contentIds.toList(),
            isHeading = { it in headings },
            isLink = { it in links },
            isText = { it in texts },
            isShape = { it in shapes },
            isSticky = { it in stickies },
        )

    @Test
    fun `one sticky alone is STICKY`() {
        assertEquals(SelectionMode.STICKY, classify(0, "k1"))
    }

    @Test
    fun `a sticky with ink beside it is MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(1, "k1"))
    }

    @Test
    fun `two stickies are MIXED — there is no one note to open`() {
        assertEquals(SelectionMode.MIXED, classify(0, "k1", "k2"))
    }

    @Test
    fun `a sticky beside another kind is MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(0, "k1", "t1"))
        assertEquals(SelectionMode.MIXED, classify(0, "k1", "s1"))
        assertEquals(SelectionMode.MIXED, classify(0, "k1", "h1"))
    }

    @Test
    fun `a sticky and a link are MIXED_WITH_LINK — the no-nesting rule outranks the note`() {
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "k1", "l1"))
    }

    @Test
    fun `the default predicate keeps every pre-H5 caller's answer`() {
        // A caller that never learned about stickies asks with four predicates and still gets
        // MIXED for a note, exactly as it did through H1–H4.
        val mode = SelectionModes.classify(
            strokeCount = 0, contentIds = listOf("k1"),
            isHeading = { it in headings },
            isLink = { it in links },
            isText = { it in texts },
            isShape = { it in shapes },
        )
        assertEquals(SelectionMode.MIXED, mode)
    }
}
