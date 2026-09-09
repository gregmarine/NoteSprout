package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ICalendar.render`'s argument checks and the one arithmetic the render owns (arc 31 / HV4). */
class RenderRequestTest {

    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
    private val am = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", CalendarTarget.HALF_AM)
    private val pm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", CalendarTarget.HALF_PM)

    @Test
    fun flagsDecodeEachBit() {
        val r = RenderRequest(listOf(month), 1404, 1872, ExtensionContract.RENDER_GRID or ExtensionContract.RENDER_INK)
        assertTrue(r.grid); assertTrue(r.ink); assertFalse(r.ring); assertFalse(r.marks)
        val all = RenderRequest(listOf(month), 1404, 1872, ExtensionContract.RENDER_ALL)
        assertTrue(all.ring); assertTrue(all.marks)
        val none = RenderRequest(listOf(month), 1404, 1872, 0)   // a white page is a legal ask
        assertFalse(none.grid); assertFalse(none.ink)
    }

    @Test
    fun refusesWhatTheContractDoesNotName() {
        assertThrows(IllegalArgumentException::class.java) { RenderRequest(emptyList(), 1404, 1872, 0) }
        assertThrows(IllegalArgumentException::class.java) { RenderRequest(listOf(month), 1404, 1872, ExtensionContract.RENDER_ALL + 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderRequest(listOf(month), 0, 1872, 0) }
        assertThrows(IllegalArgumentException::class.java) { RenderRequest(listOf(month), 1404, PageBundle.MAX_DIMENSION_PX + 1, 0) }
        val tooMany = List(ExtensionContract.RENDER_MAX_TARGETS + 1) { month }
        assertThrows(IllegalArgumentException::class.java) { RenderRequest(tooMany, 1404, 1872, 0) }
        // Exactly the cap is fine; a Day's two halves are the usual ask.
        RenderRequest(List(ExtensionContract.RENDER_MAX_TARGETS) { month }, 1404, 1872, 0)
        assertEquals(2, RenderRequest(listOf(am, pm), 1404, 1872, 0).targets.size)
    }

    @Test
    fun pageSizeIsStoredElseTheHosts() {
        val r = RenderRequest(listOf(month), 1404, 1872, 0)
        assertEquals(1404 to 1872, r.pageSize(0f, 0f))          // unminted (a placement's 0 × 0)
        assertEquals(1404 to 1872, r.pageSize(-1f, 100f))       // no row at all
        assertEquals(1920 to 2560, r.pageSize(1920f, 2560f))    // minted — keeps its own
        assertEquals(1920 to 2560, r.pageSize(1920.7f, 2560.2f))
        assertThrows(IllegalArgumentException::class.java) { r.pageSize(PageBundle.MAX_DIMENSION_PX + 1f, 10f) }
    }
}
