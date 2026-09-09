package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
