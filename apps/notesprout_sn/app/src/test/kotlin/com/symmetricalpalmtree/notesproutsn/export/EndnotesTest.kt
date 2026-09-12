package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EndnotesTest {

    private fun source(
        id: String = "s",
        fromPage: Int = 1,
        fromPageLabel: Int = fromPage,
        icon: FloatArray = floatArrayOf(100f, 200f, 172f, 272f),
        contentW: Int = 800,
        contentH: Int = 600,
        pageW: Int = 1404,
        pageH: Int = 1872,
    ) = Endnotes.Source(id, fromPage, fromPageLabel, icon[0], icon[1], icon[2], icon[3], contentW, contentH, pageW, pageH)

    @Test
    fun captionIsOgsWordingVerbatim() {
        assertEquals("Note 3 — from page 7", Endnotes.caption(3, 7))
    }

    /** Arc 34 / L15: a page-scoped bake has ONE page in it, so its endnote links address page 1 —
     *  but the caption must still say the page the notebook calls it. */
    @Test
    fun aNarrowedBakeCaptionsTheNotebooksPageNumberAndLinksTheBundles() {
        val plan = Endnotes.plan(listOf(source("s", fromPage = 1, fromPageLabel = 7)), pageCount = 1)
        val note = plan.notes.single()
        assertEquals(1, note.fromPage)
        assertEquals(7, note.fromPageLabel)
        assertEquals("Note 1 — from page 7", Endnotes.caption(note.number, note.fromPageLabel))
        // Both links stay bundle-relative: the icon on page 1 jumps to the note, the caption home.
        assertEquals(listOf(1, 2), plan.links.map { it.fromPage })
        assertEquals(listOf(2, 1), plan.links.map { it.toPage })
    }

    @Test
    fun numbersInArrivalOrderAndPlacesNotesAfterThePages() {
        val plan = Endnotes.plan(listOf(source("a", fromPage = 2), source("b", fromPage = 1)), pageCount = 3)
        assertEquals(listOf(1, 2), plan.notes.map { it.number })
        assertEquals(listOf(4, 5), plan.notes.map { it.page })
        assertEquals(listOf("a", "b"), plan.notes.map { it.stickyId })
        assertEquals(listOf(2, 1), plan.notes.map { it.fromPage })
        assertEquals(800, plan.notes[0].widthPx)
        assertEquals(600 + Endnotes.CAPTION_PX, plan.notes[0].heightPx)
    }

    @Test
    fun twoLinksPerNoteIconToNoteAndCaptionHome() {
        val plan = Endnotes.plan(listOf(source(fromPage = 2)), pageCount = 2)
        assertEquals(2, plan.links.size)
        val icon = plan.links[0]
        assertEquals(2, icon.fromPage); assertEquals(3, icon.toPage)
        assertEquals(100f, icon.l); assertEquals(200f, icon.t); assertEquals(172f, icon.r); assertEquals(272f, icon.b)
        val caption = plan.links[1]
        assertEquals(3, caption.fromPage); assertEquals(2, caption.toPage)
        assertEquals(0f, caption.l); assertEquals(600f, caption.t)
        assertEquals(800f, caption.r); assertEquals(660f, caption.b)
    }

    @Test
    fun aZeroAreaIconLinksNowhereButTheCaptionStillLinksHome() {
        val plan = Endnotes.plan(listOf(source(icon = floatArrayOf(10f, 10f, 10f, 10f))), pageCount = 1)
        assertEquals(1, plan.links.size)
        assertEquals(1, plan.links[0].toPage)
    }

    @Test
    fun aRowWithNoContentSizeTakesTheSourcePagesSize() {
        val (w, h) = Endnotes.contentSize(source(contentW = 0, contentH = 0))
        assertEquals(1404, w); assertEquals(1872, h)
    }

    @Test
    fun sizesStayInsideTheContainersCap() {
        val (w, h) = Endnotes.contentSize(source(contentW = 1_000_000, contentH = 1_000_000))
        assertEquals(PageBundle.MAX_DIMENSION_PX, w)
        assertEquals(PageBundle.MAX_DIMENSION_PX, h + Endnotes.CAPTION_PX)
        // The plan's own links must satisfy the bundle's writer for that page.
        val plan = Endnotes.plan(listOf(source(contentW = 1_000_000, contentH = 1_000_000)), 1)
        assertTrue(plan.links.all { it.b <= PageBundle.MAX_DIMENSION_PX })
    }

    @Test
    fun refusesASourceOffThePageList() {
        assertThrows(IllegalArgumentException::class.java) { Endnotes.plan(listOf(source(fromPage = 3)), 2) }
        assertThrows(IllegalArgumentException::class.java) { Endnotes.plan(emptyList(), 0) }
    }

    @Test
    fun noSourcesIsAnEmptyPlan() {
        val plan = Endnotes.plan(emptyList(), 5)
        assertTrue(plan.notes.isEmpty())
        assertTrue(plan.links.isEmpty())
    }
}
