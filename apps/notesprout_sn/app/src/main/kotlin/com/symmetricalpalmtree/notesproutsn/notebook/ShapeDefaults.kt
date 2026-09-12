package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * What each of the six shapes **is** the moment the Insert bar places it (arc 28 / H4, D3) — og's
 * defaults, in one pure function so the insert path, the tests and anything later that wants a
 * "fresh" shape cannot drift apart.
 *
 * Two families, and the difference is not decoration:
 *
 *  - the **closed** shapes (rectangle, ellipse, triangle, star) land as a [CLOSED_SIZE_DP] square,
 *    which is a size a hand can grab a handle on without hunting; three of them start
 *    **aspect-locked** because a square, a circle and a regular star are what those shapes are
 *    *for*, and the transform bar's toggle is how you say otherwise. The triangle does not, because
 *    an equilateral triangle is not the common case a rectangle's square is;
 *  - **line** and **arrow** land as half the page wide and [OPEN_HEIGHT_PX] tall, unlocked:
 *    [ShapeGeometry] draws both along the box's centre line and ignores the height, so the height is
 *    a placeholder rather than a size, and locking a ratio to it would mean nothing.
 *
 * Everything lands at the page centre with no rotation, `order = 0` (the store rebases it to
 * `MAX(order) + 1`) and the pen's own width — this arc has no width control (D3).
 */
object ShapeDefaults {

    /** The closed shapes' side at insert — og's 72 dp square. */
    const val CLOSED_SIZE_DP = 72f

    /** A line or an arrow spans half the page it is dropped on. */
    const val OPEN_WIDTH_FRACTION = 0.5f

    /** ...and has no height of its own; [ShapeGeometry] draws it along the centre line. */
    const val OPEN_HEIGHT_PX = 1f

    /** The floor the transform mode clamps every side at (D9) — dp, scaled by the caller. */
    const val MIN_SIZE_DP = 24f

    /** A brand-new [PageShape] of [type], centred on a [pageWidth] × [pageHeight] page. */
    fun at(
        id: String,
        type: ShapeType,
        pageWidth: Float,
        pageHeight: Float,
        density: Float,
    ): PageShape {
        val open = isOpen(type)
        return PageShape(
            id = id,
            type = type,
            cx = pageWidth / 2f,
            cy = pageHeight / 2f,
            width = if (open) OPEN_WIDTH_FRACTION * pageWidth else CLOSED_SIZE_DP * density,
            height = if (open) OPEN_HEIGHT_PX else CLOSED_SIZE_DP * density,
            strokeWidth = ShapeRows.DEFAULT_STROKE_WIDTH_PX,
            rotationDeg = 0f,
            aspectLocked = locked(type),
            pointCount = ShapeFlags.DEFAULT_POINTS,
            // The store lands it at MAX(order) + 1 among the page's shapes; nothing reads this 0.
            order = 0,
        )
    }

    /** Line and arrow — the two whose outline is a centre line, not a box. */
    fun isOpen(type: ShapeType): Boolean = type == ShapeType.LINE || type == ShapeType.ARROW

    /** Whether [type] arrives with the aspect lock on (see the class KDoc for why these three). */
    fun locked(type: ShapeType): Boolean = when (type) {
        ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.STAR -> true
        ShapeType.TRIANGLE, ShapeType.LINE, ShapeType.ARROW -> false
    }
}
