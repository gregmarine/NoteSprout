package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.notebook.StickyPageRects.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fence around an old note (arc 33 / F2, decision 5): the paper beyond a page smaller than the
 * editor's full-bleed view is white and writable unless it is excluded, so these bands are the only
 * thing keeping ink inside the note.
 */
class StickyPageRectsTest {

    // The editor's view on the Nomad, and a pre-arc note authored one top bar shorter.
    private val viewW = 1404
    private val viewH = 1872

    @Test
    fun `a note both narrower and shorter is fenced below and to the right`() {
        val bands = StickyPageRects.offPage(pageW = 1000, pageH = 1700, viewW = viewW, viewH = viewH)
        assertEquals(
            listOf(
                Band(0, 1700, 1404, 1872),   // below: the full width, corner included
                Band(1000, 0, 1404, 1700),   // right: only as tall as the page
            ),
            bands,
        )
    }

    @Test
    fun `a pre-arc note — full width, one bar shorter — is fenced below only`() {
        val bands = StickyPageRects.offPage(pageW = viewW, pageH = 1700, viewW = viewW, viewH = viewH)
        assertEquals(listOf(Band(0, 1700, 1404, 1872)), bands)
    }

    @Test
    fun `a note that is only narrower is fenced on the right only`() {
        val bands = StickyPageRects.offPage(pageW = 900, pageH = viewH, viewW = viewW, viewH = viewH)
        assertEquals(listOf(Band(900, 0, 1404, 1872)), bands)
    }

    @Test
    fun `a note the size of the view leaves nothing over`() {
        // Every note authored since F2 is this one: contentSize is the whole window.
        assertEquals(emptyList<Band>(), StickyPageRects.offPage(viewW, viewH, viewW, viewH))
    }

    @Test
    fun `a foreign note larger than the view leaves nothing over`() {
        // Laid top-left 1:1, it runs off the screen instead — there is no free paper to fence.
        assertEquals(emptyList<Band>(), StickyPageRects.offPage(2000, 2600, viewW, viewH))
    }

    @Test
    fun `a view that has not been laid out is fenced nowhere`() {
        // pushExclusions blocks the whole surface until the note is shown; this is not that gate.
        assertEquals(emptyList<Band>(), StickyPageRects.offPage(800, 600, viewW = 0, viewH = 0))
        assertEquals(emptyList<Band>(), StickyPageRects.offPage(800, 600, viewW = -1, viewH = 100))
    }

    @Test
    fun `the two bands never overlap`() {
        val bands = StickyPageRects.offPage(pageW = 1000, pageH = 1700, viewW = viewW, viewH = viewH)
        assertEquals(2, bands.size)
        val (below, right) = bands
        // The right band stops where the below band starts — one shared edge, no shared pixel.
        assertTrue("the right band must end at the page's bottom", right.bottom <= below.top)
    }

    @Test
    fun `a one px short page yields a one px band`() {
        val bands = StickyPageRects.offPage(pageW = viewW, pageH = viewH - 1, viewW = viewW, viewH = viewH)
        assertEquals(listOf(Band(0, 1871, 1404, 1872)), bands)
        assertEquals(1, bands.single().bottom - bands.single().top)
    }
}
