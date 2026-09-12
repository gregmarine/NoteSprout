package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parked target `outgoingTarget` answers (arc 31 / HV4) — set by the screen, cleared by `end`. */
class CalendarSessionTargetTest {

    private val week = CalendarTarget(CalendarTarget.KIND_WEEK, "2026-09-06", 0)

    @Test
    fun parkedTargetIsAnsweredUntilEndClearsIt() {
        CalendarSession.clear()
        assertNull(CalendarSession.outboundTarget)
        CalendarSession.parkTarget(week)
        assertEquals(week, CalendarSession.outboundTarget)
        CalendarSession.parkTarget(null)          // a selection send
        assertNull(CalendarSession.outboundTarget)
        CalendarSession.parkTarget(week)
        CalendarSession.clear()                   // `end`
        assertNull(CalendarSession.outboundTarget)
    }

    private val am = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-10", CalendarTarget.HALF_AM)
    private val pm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-10", CalendarTarget.HALF_PM)
    private val one = listOf(listOf(WireStroke(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(1f, 1f), floatArrayOf(0f, 0f), 3f, -16777216, "PEN")))

    /** Arc 35 / HA1: a queued page moves into place on `advance`, in order, and `end` drops it. */
    @Test
    fun queuedPagesAdvanceInOrderUntilEmpty() {
        CalendarSession.clear()
        CalendarSession.park(one, 1404f, 1872f)
        CalendarSession.parkTarget(am)
        CalendarSession.queueAfterCurrent(listOf(CalendarSession.OutboundPage(emptyList(), 1404f, 1872f, pm)))
        assertEquals(1, CalendarSession.queuedCount)
        assertEquals(am, CalendarSession.outboundTarget)
        assertEquals(1, CalendarSession.outgoing(0).strokes.size)

        assertTrue(CalendarSession.advance())
        assertEquals(0, CalendarSession.queuedCount)
        assertEquals(pm, CalendarSession.outboundTarget)
        assertEquals(0, CalendarSession.outgoing(0).strokes.size)   // an unminted half: paper only
        assertEquals(1872f, CalendarSession.outgoing(0).pageHeight)

        assertFalse(CalendarSession.advance())                     // idempotent at the end
        assertEquals(pm, CalendarSession.outboundTarget)

        CalendarSession.queueAfterCurrent(listOf(CalendarSession.OutboundPage(one, 1f, 1f, am)))
        CalendarSession.clear()                                    // `end`
        assertEquals(0, CalendarSession.queuedCount)
        assertFalse(CalendarSession.advance())
        assertNull(CalendarSession.outboundTarget)
    }
}
