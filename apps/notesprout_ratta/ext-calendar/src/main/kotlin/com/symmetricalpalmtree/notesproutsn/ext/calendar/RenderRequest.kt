package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle

/**
 * One `ICalendar.render` call's arguments, checked (arc 31 / HV4) — pure, so the seam's refusals
 * are JVM-tested. Every `require` here crosses Binder as an `IllegalArgumentException`, which is
 * the family rule: unmarshal is validation, and a host that asks for zero pages or a flag the
 * contract does not name has a bug, not a rounding problem. The targets themselves are already
 * through `CalendarTarget.requireValid` at unmarshal.
 *
 * [pageSize] is the one arithmetic the render owns: a minted page keeps its **stored** size, an
 * unminted one (no row, or the `0 × 0` a placement mints) takes the host's [widthPx] × [heightPx]
 * — the screen's own rule (`CalendarDocument.show`), with the display's size replaced by what the
 * host asked for. A stored size over the bundle's ceiling is refused rather than rendered: the
 * bitmap it would need is the bundle's `MAX_DIMENSION_PX` squared in RGB_565, and an extension
 * must never be the one to find out whether that allocates.
 */
class RenderRequest(
    val targets: List<CalendarTarget>,
    val widthPx: Int,
    val heightPx: Int,
    val flags: Int,
) {
    init {
        require(targets.isNotEmpty()) { "no targets" }
        require(targets.size <= ExtensionContract.RENDER_MAX_TARGETS) { "too many targets (${targets.size})" }
        require(widthPx in 1..PageBundle.MAX_DIMENSION_PX) { "widthPx out of range" }
        require(heightPx in 1..PageBundle.MAX_DIMENSION_PX) { "heightPx out of range" }
        require(flags and ExtensionContract.RENDER_ALL.inv() == 0) { "unknown render flag" }
    }

    val grid: Boolean get() = flags and ExtensionContract.RENDER_GRID != 0
    val ink: Boolean get() = flags and ExtensionContract.RENDER_INK != 0
    val ring: Boolean get() = flags and ExtensionContract.RENDER_RING != 0
    val marks: Boolean get() = flags and ExtensionContract.RENDER_MARKS != 0

    /** The page size a target renders at: its stored size when it has one, else the host's. */
    fun pageSize(storedWidth: Float, storedHeight: Float): Pair<Int, Int> {
        if (storedWidth <= 0f || storedHeight <= 0f) return widthPx to heightPx
        val w = storedWidth.toInt()
        val h = storedHeight.toInt()
        require(w in 1..PageBundle.MAX_DIMENSION_PX && h in 1..PageBundle.MAX_DIMENSION_PX) { "stored page size out of range" }
        return w to h
    }
}
