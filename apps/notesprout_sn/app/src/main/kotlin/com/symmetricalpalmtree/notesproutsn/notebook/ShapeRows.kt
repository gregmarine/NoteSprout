package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/** The six hand-placed shapes (arc 28, decision 7). Square and circle are a rectangle / ellipse
 *  with the aspect lock — not types. The `name` is what the `style` column holds. */
enum class ShapeType { RECTANGLE, ELLIPSE, TRIANGLE, ARROW, LINE, STAR }

/**
 * A shape object (arc 28 / H1) as the notebook screen holds it. **[cx]/[cy] is the centre** —
 * the one row kind whose `x`/`y` is not a top-left — and [width]/[height] are the un-rotated local
 * extents; [rotationDeg] is applied about the centre, clockwise, last. The on-page outline and its
 * axis-aligned box come from [ShapeGeometry]; nothing here draws.
 */
data class PageShape(
    val id: String,
    val type: ShapeType,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    /** Outline width in **px** (SN strokes are px — one unit in the file). */
    val strokeWidth: Float,
    /** Clockwise, 0 ≤ deg < 360, stored to a tenth of a degree. */
    val rotationDeg: Float,
    val aspectLocked: Boolean,
    /** STAR only — 5..12 points; [ShapeFlags.DEFAULT_POINTS] for every other type. */
    val pointCount: Int,
    /** Z-order among the page's shape rows (`"order"` column). */
    val order: Int,
) {
    fun translated(dx: Float, dy: Float): PageShape = copy(cx = cx + dx, cy = cy + dy)
}

/**
 * The `flags` word of a shape row — three fields packed into one 64-bit integer because SN adds no
 * column to the family table (the format lock):
 *
 *  - bit 0 — aspect lock;
 *  - bits 8–15 — point count (STAR; 0 reads as [DEFAULT_POINTS]);
 *  - bits 16–31 — rotation in **tenths of a degree**, 0–3599 clockwise.
 *
 * Pure — JVM-tested for the round trip, including 359.9°.
 */
object ShapeFlags {
    const val DEFAULT_POINTS = 5
    const val MIN_POINTS = 5
    const val MAX_POINTS = 12

    private const val ASPECT_BIT = 1L
    private const val POINTS_SHIFT = 8
    private const val POINTS_MASK = 0xFFL
    private const val ROTATION_SHIFT = 16
    private const val ROTATION_MASK = 0xFFFFL

    fun pack(aspectLocked: Boolean, pointCount: Int, rotationDeg: Float): Long {
        val tenths = rotationTenths(rotationDeg).toLong()
        val points = pointCount.coerceIn(MIN_POINTS, MAX_POINTS).toLong()
        return (if (aspectLocked) ASPECT_BIT else 0L) or
            (points shl POINTS_SHIFT) or
            (tenths shl ROTATION_SHIFT)
    }

    fun aspectLocked(flags: Long?): Boolean = flags != null && (flags and ASPECT_BIT) != 0L

    fun pointCount(flags: Long?): Int {
        val raw = ((flags ?: 0L) shr POINTS_SHIFT and POINTS_MASK).toInt()
        return if (raw == 0) DEFAULT_POINTS else raw.coerceIn(MIN_POINTS, MAX_POINTS)
    }

    /** Degrees, 0 ≤ value < 360. */
    fun rotationDeg(flags: Long?): Float {
        val tenths = ((flags ?: 0L) shr ROTATION_SHIFT and ROTATION_MASK).toInt()
        return (tenths % 3600) / 10f
    }

    /** Normalise any angle into 0..3599 tenths, rounding to the nearest tenth. */
    fun rotationTenths(deg: Float): Int {
        val d = if (deg.isFinite()) deg else 0f
        val tenths = Math.round(d * 10f)
        return ((tenths % 3600) + 3600) % 3600
    }

    /** The stored form of a rotation — what a transform writes back, so two angles that pack
     *  alike compare alike. */
    fun normalizeDeg(deg: Float): Float = rotationTenths(deg) / 10f
}

/**
 * The one place a [PageShape] becomes a `shape` row and back — the arc-28 additive family row type
 * (`SoilSchema.TYPE_SHAPE` documents the column contract). Pure Kotlin — JVM-tested.
 */
object ShapeRows {

    fun toRow(shape: PageShape, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = shape.id, parentId = pageId, type = SoilSchema.TYPE_SHAPE, order = shape.order,
        createdAt = now, updatedAt = now,
        style = shape.type.name,
        x = shape.cx, y = shape.cy, width = shape.width, height = shape.height,
        strokeWidth = shape.strokeWidth,
        flags = ShapeFlags.pack(shape.aspectLocked, shape.pointCount, shape.rotationDeg),
    )

    /**
     * Decode one row; null when it is not a usable shape — wrong type, an unknown `style` (a
     * future type this build cannot draw is dropped, never crashed on), or missing / non-finite /
     * negative geometry. A missing `strokeWidth` reads as [DEFAULT_STROKE_WIDTH_PX].
     */
    fun toShape(row: SoilObjectEntity): PageShape? {
        if (row.type != SoilSchema.TYPE_SHAPE) return null
        val type = typeOf(row.style) ?: return null
        val cx = row.x ?: return null
        val cy = row.y ?: return null
        val w = row.width ?: return null
        val h = row.height ?: return null
        if (!(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        val sw = row.strokeWidth?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_STROKE_WIDTH_PX
        return PageShape(
            id = row.id, type = type, cx = cx, cy = cy, width = w, height = h,
            strokeWidth = sw,
            rotationDeg = ShapeFlags.rotationDeg(row.flags),
            aspectLocked = ShapeFlags.aspectLocked(row.flags),
            pointCount = ShapeFlags.pointCount(row.flags),
            order = row.order,
        )
    }

    fun typeOf(style: String?): ShapeType? =
        style?.let { s -> ShapeType.entries.firstOrNull { it.name == s } }

    /** The pen's width (`NotebookToolbar.PEN_WIDTH_PX`) — fixed at creation this arc. */
    const val DEFAULT_STROKE_WIDTH_PX = 3f
}
