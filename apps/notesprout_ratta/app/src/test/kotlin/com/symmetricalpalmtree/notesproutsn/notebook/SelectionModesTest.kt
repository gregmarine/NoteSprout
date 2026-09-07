package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D5's table, row by row (arc 28 / H2): which selection is which [SelectionMode]. The classification
 * used to be a `when` inside `NotebookActivity.showSelectionToolbar`, where nothing could read it.
 */
class SelectionModesTest {

    private val headings = setOf("h1", "h2")
    private val links = setOf("l1", "l2")
    private val texts = setOf("t1", "t2")
    private val shapes = setOf("s1", "s2")

    /** Everything the screen would answer off its working copies — a sticky is simply in none of
     *  the four sets, exactly as an unknown id would be. */
    private fun classify(strokes: Int, vararg contentIds: String): SelectionMode =
        SelectionModes.classify(
            strokes, contentIds.toList(),
            isHeading = { it in headings },
            isLink = { it in links },
            isText = { it in texts },
            isShape = { it in shapes },
        )

    // ── The lone kinds ───────────────────────────────────────────────────────

    @Test
    fun `one heading alone is HEADING`() {
        assertEquals(SelectionMode.HEADING, classify(0, "h1"))
    }

    @Test
    fun `one text alone is TEXT`() {
        assertEquals(SelectionMode.TEXT, classify(0, "t1"))
    }

    @Test
    fun `one shape alone is SHAPE`() {
        assertEquals(SelectionMode.SHAPE, classify(0, "s1"))
    }

    @Test
    fun `one link alone is LINK`() {
        assertEquals(SelectionMode.LINK, classify(0, "l1"))
    }

    @Test
    fun `a lone kind needs the ink to be empty too`() {
        // A heading with one stroke beside it is not a heading selection: there is no single level
        // to report and the stroke would ride any act performed on it.
        assertEquals(SelectionMode.MIXED, classify(1, "h1"))
        assertEquals(SelectionMode.MIXED, classify(1, "t1"))
        assertEquals(SelectionMode.MIXED, classify(1, "s1"))
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(1, "l1"))
    }

    // ── Ink alone ────────────────────────────────────────────────────────────

    @Test
    fun `ink and nothing else is STROKES`() {
        assertEquals(SelectionMode.STROKES, classify(3))
    }

    @Test
    fun `an empty selection is MIXED, never STROKES`() {
        // It cannot happen from the engine, and if it did, offering a conversion of no ink would be
        // a button that can only fail.
        assertEquals(SelectionMode.MIXED, classify(0))
    }

    // ── Mixtures ─────────────────────────────────────────────────────────────

    @Test
    fun `text plus ink is MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(2, "t1"))
    }

    @Test
    fun `two texts are MIXED — lone means exactly one`() {
        assertEquals(SelectionMode.MIXED, classify(0, "t1", "t2"))
    }

    @Test
    fun `heading plus text is MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(0, "h1", "t1"))
    }

    @Test
    fun `two headings are MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(0, "h1", "h2"))
    }

    @Test
    fun `text plus link is MIXED_WITH_LINK — the no-nesting rule`() {
        // K1: Link is offered on every link-free selection and on none that already holds one.
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "t1", "l1"))
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "h1", "l1"))
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "l1", "l2"))
    }

    @Test
    fun `two shapes are MIXED — lone means exactly one`() {
        assertEquals(SelectionMode.MIXED, classify(0, "s1", "s2"))
    }

    @Test
    fun `a shape beside a text is MIXED`() {
        assertEquals(SelectionMode.MIXED, classify(0, "s1", "t1"))
    }

    // ── The kind whose mode has not landed yet (H5) ──────────────────────────

    @Test
    fun `a lone sticky is MIXED until its own phase`() {
        // Deliberate, and pinned: MIXED's row (Snap / Copy / Cut / Delete + a link-free Link) is
        // the honest offer for a kind whose own verbs do not exist yet.
        assertEquals(SelectionMode.MIXED, classify(0, "sticky-1"))
    }

    @Test
    fun `a shape beside a link still takes the link away`() {
        // The no-nesting rule outranks the lone-kind rule: a shape wrapped with a link in the same
        // selection has no Transform to offer, because the mode transforms exactly one object.
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "s1", "l1"))
        assertEquals(SelectionMode.MIXED_WITH_LINK, classify(0, "sticky-1", "l1"))
    }
}
