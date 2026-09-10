package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect

/**
 * The paper the note does **not** own — the band below, and if it is narrower the band right of,
 * an old note's page laid top-left in the editor's full-bleed view (arc 33 / F2, decision 5).
 *
 * Since F2 a new note's content is the whole window, so on this device nothing is left over. An
 * older row carries the size it was authored at — a pre-arc note is one top bar shorter, a foreign
 * one can be any size — and g-paper leaves everything beyond the page **white and writable**: the
 * area is not paper, but the pen would ink there all the same and the strokes would land outside
 * the note. This exclusion is the only thing that keeps the ink inside it.
 *
 * The two bands never overlap: the one below spans the view's full width, the one on the right
 * only the page's own height. Pure — JVM-tested; the geometry is in **paper px**, the same space
 * `setExclusionRects` takes, so the activity hands these straight over without translating.
 *
 * [Rect] is deliberately not the return type: `android.graphics.Rect` is a stub on the JVM (the
 * mockable android jar leaves its constructor empty), so a rect built in a unit test carries no
 * numbers at all. [Band] is what the rule is tested through, and [toRect] is the one Android line.
 */
object StickyPageRects {

    /** One excluded band in paper px, `right` / `bottom` exclusive — [Rect]'s own convention. */
    data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * The bands of [viewW] × [viewH] that the [pageW] × [pageH] page (laid top-left, 1:1) leaves
     * over. Empty when the page covers the view — or is larger than it — in both dimensions, and
     * empty for a view that has not been laid out.
     */
    fun offPage(pageW: Int, pageH: Int, viewW: Int, viewH: Int): List<Band> {
        if (viewW <= 0 || viewH <= 0) return emptyList()
        val bands = ArrayList<Band>(2)
        // Below the page: the full width of the view, so it also covers the corner.
        if (pageH < viewH) bands += Band(0, pageH, viewW, viewH)
        // Right of the page: only as tall as the page, so the two never overlap.
        if (pageW < viewW) bands += Band(pageW, 0, viewW, pageH)
        return bands
    }
}

/** The one Android-typed line: a [StickyPageRects.Band] as the rect `setExclusionRects` takes. */
fun StickyPageRects.Band.toRect(): Rect = Rect(left, top, right, bottom)
