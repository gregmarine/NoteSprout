package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * Where an **inserted** text object lands (arc 28 / H2): the page centre, clamped onto the page.
 * Pure arithmetic, like [ObjectPlacement] beside it — [TextFlow] does the measuring (which needs a
 * `StaticLayout` and therefore a device) and this does the placing, so the half that can be proved
 * off-device is.
 *
 * Insert is an aim, not a corner: the box's *middle* goes to the page's middle. A box wider or
 * taller than the page cannot be centred without losing its top-left, so on that axis it starts at
 * 0 — the beginning of the text is the part worth having on screen (the paste clamp's rule).
 */
object TextPlacement {

    /** The top-left that centres a [w] × [h] box on a [pageW] × [pageH] page, never negative. */
    fun centred(pageW: Float, pageH: Float, w: Float, h: Float): Pair<Float, Float> =
        axis(pageW, w) to axis(pageH, h)

    private fun axis(page: Float, size: Float): Float {
        if (!page.isFinite() || !size.isFinite()) return 0f
        val at = (page - size) / 2f
        return if (at > 0f) at else 0f
    }
}
