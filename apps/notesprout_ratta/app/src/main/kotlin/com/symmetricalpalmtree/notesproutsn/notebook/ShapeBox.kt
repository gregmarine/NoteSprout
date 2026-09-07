package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.OrientedBox

/**
 * The two-line translation between a [PageShape] and the [OrientedBox] g-paper's transform mode
 * edits (arc 28 / H4, D9) — pure, so the one place the row's five geometry numbers meet the
 * engine's five is a place a test can read.
 *
 * The two models line up exactly (centre, un-rotated extents, clockwise degrees about the centre),
 * which is why the mode could stay shape-agnostic at all. The single thing that is *not* a straight
 * copy is the angle: a row stores rotation in tenths of a degree ([ShapeFlags]), so a box coming
 * back from the engine is normalised through [ShapeFlags.normalizeDeg] on the way in. Without
 * that, two shapes that pack to the same `flags` word would compare unequal and every exit would
 * record an undo entry for a rotation nobody changed.
 */
object ShapeBox {

    /** [shape]'s geometry as the engine wants it. */
    fun toBox(shape: PageShape): OrientedBox =
        OrientedBox(shape.cx, shape.cy, shape.width, shape.height, shape.rotationDeg)

    /**
     * [shape] with [box]'s geometry written over it — everything else (type, stroke width, point
     * count, aspect lock, order, id) is the shape's own and never comes from the engine.
     */
    fun applied(shape: PageShape, box: OrientedBox): PageShape = shape.copy(
        cx = box.cx,
        cy = box.cy,
        width = box.w,
        height = box.h,
        rotationDeg = ShapeFlags.normalizeDeg(box.rotationDeg),
    )
}
