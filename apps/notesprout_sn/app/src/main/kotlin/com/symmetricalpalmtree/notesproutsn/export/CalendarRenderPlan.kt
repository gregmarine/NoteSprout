package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import java.time.LocalDate

/**
 * **What one calendar export draws, and what it is called** (arc 31 / HV4) — pure, JVM-tested, and
 * the whole of the calendar mode's arithmetic on the host's side.
 *
 * The Export screen holds one [CalendarTarget] — the page the calendar was showing when its Export
 * door was tapped — and everything else follows from it by rule:
 *
 *  - **A Day is two pages, AM then PM.** A day *page* on the calendar is one half, but a day
 *    *exported* is the day: nobody asks for half a Tuesday. So the half that came through the door
 *    is irrelevant — both are drawn, in reading order, and a per-page exporter writes two files.
 *    Month and Week are one page each.
 *  - **The flags are settled, not asked** (the user's phase-start call): ink, today's ring and the
 *    day marks always; the grid only when the exporter's page-template toggle is on — which is the
 *    same question the notebook's pages answer with the same control, so the screen keeps one row
 *    and one label for both.
 *  - **[stems] name the files** one per page, and [singleStem] names the one file a
 *    one-file exporter writes — the same name without the ` AM` / ` PM` that only means anything
 *    when there are two of them beside each other.
 *  - **[label] is the header line** under "Export": what the person would call the page they came
 *    from, which for a Day is the day without the half it happened to be showing.
 *
 * Every string [ExportNaming.calendarStem] builds is already made of letters, digits, spaces and
 * hyphens, so the sanitize would not change one — but the file on disk still goes through the same
 * naming object as every other export, because *where a filename comes from* is one question in
 * this app and not two.
 */
class CalendarRenderPlan(
    /** The pages to draw, in order — one per exported page, the render's own argument. */
    val targets: List<CalendarTarget>,
    /** The `RENDER_*` mask handed to `ICalendar.render`. */
    val flags: Int,
    /** One filename stem per page of [targets], in the same order (per-page delivery's names). */
    val stems: List<String>,
    /** The header line under the screen's title — the page the export came from, in words. */
    val label: String,
    /** The filename stem of a one-file export of the whole thing. */
    val singleStem: String,
) {

    companion object {

        /** How many pages an export of [target] draws: a Day is both halves, everything else one. */
        fun pages(target: CalendarTarget): Int =
            if (target.kind == CalendarTarget.KIND_DAY) 2 else 1

        /**
         * The plan for [target]. [includeGrid] is the exporter's page-template answer — the one
         * flag the person chooses; ink, ring and marks are always drawn.
         */
        fun of(target: CalendarTarget, includeGrid: Boolean): CalendarRenderPlan {
            val day = target.localDate
            val targets = if (target.kind == CalendarTarget.KIND_DAY) {
                listOf(
                    CalendarTarget(target.kind, target.date, CalendarTarget.HALF_AM),
                    CalendarTarget(target.kind, target.date, CalendarTarget.HALF_PM),
                )
            } else {
                listOf(target)
            }
            val flags = (if (includeGrid) ExtensionContract.RENDER_GRID else 0) or
                ExtensionContract.RENDER_INK or
                ExtensionContract.RENDER_RING or
                ExtensionContract.RENDER_MARKS
            val stem = ExportNaming.calendarStem(target)
            val stems = if (target.kind == CalendarTarget.KIND_DAY) {
                listOf("$stem AM", "$stem PM")
            } else {
                listOf(stem)
            }
            return CalendarRenderPlan(targets, flags, stems, labelOf(target.kind, day), stem)
        }

        /**
         * The header line: the month, the week, or the day **without the ` · AM` tail**
         * [CalendarDates.dayTitle] carries — an export of a day is the whole day, so naming one
         * half of it in the header would be the screen contradicting the file it is about to write.
         * A tiny helper here rather than a fifth title in [CalendarDates], which is the seam's and
         * is shared with the extension.
         */
        private fun labelOf(kind: Int, day: LocalDate): String = when (kind) {
            CalendarTarget.KIND_MONTH -> CalendarDates.monthTitle(day)
            CalendarTarget.KIND_WEEK -> CalendarDates.weekTitle(day)
            else -> CalendarDates.dayTitle(day, CalendarTarget.HALF_AM).substringBeforeLast(" · ")
        }
    }
}
