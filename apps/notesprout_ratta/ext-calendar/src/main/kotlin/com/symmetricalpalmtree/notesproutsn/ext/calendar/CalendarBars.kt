package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.res.Resources

/**
 * The two bars' heights **as the layout lays them out** — what the screen passes [CalendarGeometry]
 * as its insets, rebuilt without a view (arc 31 / HV4). Each bar is one `toolbar_bar_thickness` row
 * plus its `calendar_bar_rule` hairline, and `TopGuard` adds nothing on Ratta.
 *
 * Why the render needs them: the ink on a page was written against the grid the *screen* drew,
 * and that grid starts under the top bar. A grid painted at inset 0 sits one bar higher than the
 * ink expects, and a word in the 13th lands across the 20th (the HV4 walk found exactly that).
 * The exported page therefore carries the screen's insets — blank bands top and bottom where the
 * bars were, which is also what the ink-only page (template off) already is.
 */
object CalendarBars {
    fun topInsetPx(res: Resources): Int =
        res.getDimensionPixelSize(R.dimen.toolbar_bar_thickness) + res.getDimensionPixelSize(R.dimen.calendar_bar_rule)

    fun bottomInsetPx(res: Resources): Int = topInsetPx(res)
}
