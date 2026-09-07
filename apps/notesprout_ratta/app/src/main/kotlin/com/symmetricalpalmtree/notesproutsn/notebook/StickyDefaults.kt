package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * A sticky note as the Insert bar places it (arc 28 / H5, decision 2 + D2): a [StickyRows.ICON_DP]
 * square icon at the page centre, clamped onto the page, with the note's **content size** fixed to
 * the creating device's editor paper — the editor's screen minus its one top bar. Pure — JVM-tested.
 *
 * The content size is computed here, by the notebook, rather than minted by the editor at its
 * first layout: the two screens share one window on one portrait-locked device, so the editor's
 * paper area is *knowable* before it opens, and a row that is complete from its first write is
 * a row whose insert snapshot ([NotebookUndo.Action.StickyInserted]) can be redone without a second
 * write. The editor calls `setPageSize(contentW, contentH)` on every open and lays a foreign size
 * (another device's) top-left, 1:1 — the notebook's own foreign-page rule. A zero size (an old or
 * foreign row) makes the editor use its own paper area instead.
 */
object StickyDefaults {

    /** The icon box at the page centre, clamped so it never hangs off a small page. */
    fun at(
        id: String,
        pageWidth: Float,
        pageHeight: Float,
        density: Float,
        contentW: Int,
        contentH: Int,
    ): PageSticky {
        val edge = StickyRows.ICON_DP * density
        val x = ((pageWidth - edge) / 2f).coerceAtLeast(0f)
        val y = ((pageHeight - edge) / 2f).coerceAtLeast(0f)
        return PageSticky(
            id = id, x = x, y = y, width = edge, height = edge,
            contentW = contentW, contentH = contentH,
            // The store lands it at MAX(order) + 1 among the page's stickies; nothing reads this 0.
            order = 0,
        )
    }

    /**
     * The editor's paper area on this device: the window ([windowW] × [windowH]) minus the top
     * bar's laid-out height ([topBarPx] — the notebook measures its own, which is built to the same
     * recipe: one `toolbar_bar_thickness` row plus a 1 dp rule). Each dimension is at least 1 and
     * at most [StickyFlags.MAX] (the packing limit).
     */
    fun contentSize(windowW: Int, windowH: Int, topBarPx: Int): Pair<Int, Int> {
        val w = windowW.coerceIn(1, StickyFlags.MAX)
        val h = (windowH - topBarPx).coerceIn(1, StickyFlags.MAX)
        return w to h
    }
}
