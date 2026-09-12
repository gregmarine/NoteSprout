package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.R

/**
 * What the transform bar's aspect-lock button **says** (arc 28 / H4) — og's wording, as a pure
 * lookup so the table is a table a test can read rather than a `when` inside a view.
 *
 * The button is a latch: its border says armed, and its word says what the shape *is right now*,
 * not what tapping would make it. That reads the way the top bar's armed tool reads, and it is the
 * only honest label for a control whose two states are both legitimate shapes:
 *
 * | Type | locked | free |
 * |---|---|---|
 * | [ShapeType.ELLIPSE] | Circle | Oval |
 * | [ShapeType.RECTANGLE] | Square | Rect |
 * | everything else | 1:1 | Free |
 *
 * A triangle, a star, a line and an arrow get the ratio in figures because they have no pair of
 * everyday names the way an ellipse and a rectangle do — "equilateral" is not a word a toolbar
 * button can carry, and inventing one for the arrow would be worse than saying what the lock does.
 */
object ShapeTransformLabels {

    /** The string resource naming [type]'s **current** aspect state. */
    fun res(type: ShapeType, locked: Boolean): Int = when (type) {
        ShapeType.ELLIPSE -> if (locked) R.string.shape_lock_circle else R.string.shape_lock_oval
        ShapeType.RECTANGLE -> if (locked) R.string.shape_lock_square else R.string.shape_lock_rect
        ShapeType.TRIANGLE, ShapeType.STAR, ShapeType.LINE, ShapeType.ARROW ->
            if (locked) R.string.shape_lock_1_1 else R.string.shape_lock_free
    }
}
