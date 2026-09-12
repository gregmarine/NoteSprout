package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arc 31 / HV4: what one calendar export draws, how many pages it is, and what each is called.
 *
 * The parcelables are constructed directly, no `Parcel` touched (the family's test shape).
 */
class CalendarRenderPlanTest {

    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", CalendarTarget.HALF_AM)
    private val week = CalendarTarget(CalendarTarget.KIND_WEEK, "2026-09-06", CalendarTarget.HALF_AM)
    private val dayAm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", CalendarTarget.HALF_AM)
    private val dayPm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", CalendarTarget.HALF_PM)

    // ── How many pages ───────────────────────────────────────────────────────

    @Test
    fun monthAndWeekAreOnePageAndADayIsTwo() {
        assertEquals(1, CalendarRenderPlan.pages(month))
        assertEquals(1, CalendarRenderPlan.pages(week))
        assertEquals(2, CalendarRenderPlan.pages(dayAm))
        assertEquals(2, CalendarRenderPlan.pages(dayPm))
    }

    @Test
    fun aDayDrawsBothHalvesInOrderWhicheverHalfCameThroughTheDoor() {
        for (door in listOf(dayAm, dayPm)) {
            val plan = CalendarRenderPlan.of(door, includeGrid = true)
            assertEquals(2, plan.targets.size)
            assertEquals(listOf(CalendarTarget.HALF_AM, CalendarTarget.HALF_PM), plan.targets.map { it.half })
            assertTrue(plan.targets.all { it.kind == CalendarTarget.KIND_DAY && it.date == "2026-09-08" })
        }
    }

    @Test
    fun aMonthOrAWeekDrawsTheOnePageItWasGiven() {
        assertEquals(listOf(month), CalendarRenderPlan.of(month, includeGrid = true).targets)
        assertEquals(listOf(week), CalendarRenderPlan.of(week, includeGrid = false).targets)
    }

    // ── The flags ────────────────────────────────────────────────────────────

    @Test
    fun inkRingAndMarksAreAlwaysDrawn() {
        for (grid in listOf(true, false)) {
            val flags = CalendarRenderPlan.of(month, includeGrid = grid).flags
            for (bit in listOf(
                ExtensionContract.RENDER_INK, ExtensionContract.RENDER_RING, ExtensionContract.RENDER_MARKS,
            )) {
                assertEquals("flag $bit must be set at grid=$grid", bit, flags and bit)
            }
        }
    }

    @Test
    fun theGridIsTheOnlyFlagTheToggleDecides() {
        assertEquals(ExtensionContract.RENDER_ALL, CalendarRenderPlan.of(month, includeGrid = true).flags)
        assertEquals(
            ExtensionContract.RENDER_ALL and ExtensionContract.RENDER_GRID.inv(),
            CalendarRenderPlan.of(month, includeGrid = false).flags,
        )
    }

    @Test
    fun noFlagOutsideTheContractIsEverSet() {
        for (target in listOf(month, week, dayAm)) {
            for (grid in listOf(true, false)) {
                val flags = CalendarRenderPlan.of(target, grid).flags
                assertEquals(0, flags and ExtensionContract.RENDER_ALL.inv())
            }
        }
    }

    // ── The names ────────────────────────────────────────────────────────────

    @Test
    fun oneFileIsNamedForThePeriodWithNoHalfInIt() {
        assertEquals("Calendar - September 2026", CalendarRenderPlan.of(month, true).singleStem)
        assertEquals("Calendar - Week of 2026-09-06", CalendarRenderPlan.of(week, true).singleStem)
        assertEquals("Calendar - 2026-09-08", CalendarRenderPlan.of(dayPm, true).singleStem)
    }

    @Test
    fun perPageStemsFollowThePagesAndADayTellsItsTwoApart() {
        assertEquals(listOf("Calendar - September 2026"), CalendarRenderPlan.of(month, true).stems)
        assertEquals(listOf("Calendar - Week of 2026-09-06"), CalendarRenderPlan.of(week, true).stems)
        assertEquals(
            listOf("Calendar - 2026-09-08 AM", "Calendar - 2026-09-08 PM"),
            CalendarRenderPlan.of(dayAm, true).stems,
        )
    }

    @Test
    fun thereIsOneStemPerPage() {
        for (target in listOf(month, week, dayAm, dayPm)) {
            val plan = CalendarRenderPlan.of(target, true)
            assertEquals(plan.targets.size, plan.stems.size)
            assertEquals(CalendarRenderPlan.pages(target), plan.stems.size)
        }
    }

    // ── The header line ──────────────────────────────────────────────────────

    @Test
    fun theLabelIsThePeriodInWords() {
        assertEquals("September 2026", CalendarRenderPlan.of(month, true).label)
        assertEquals("Sep 6 – 12, 2026", CalendarRenderPlan.of(week, true).label)
    }

    @Test
    fun aDaysLabelDropsTheHalfItCameFrom() {
        // An export of a day is the whole day: naming one half in the header would contradict the
        // two files it is about to write.
        val expected = "Tue, Sep 8, 2026"
        assertEquals(expected, CalendarRenderPlan.of(dayAm, true).label)
        assertEquals(expected, CalendarRenderPlan.of(dayPm, true).label)
    }
}
