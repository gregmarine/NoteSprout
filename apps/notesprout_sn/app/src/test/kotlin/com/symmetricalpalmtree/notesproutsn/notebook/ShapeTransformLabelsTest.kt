package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The aspect latch's wording (arc 28 / H4). Resource **ids** are all a JVM test can see, which is
 * exactly the table worth pinning: which of the six string slots each type and state picks.
 */
class ShapeTransformLabelsTest {

    @Test
    fun `an ellipse is a circle or an oval`() {
        assertEquals(R.string.shape_lock_circle, ShapeTransformLabels.res(ShapeType.ELLIPSE, locked = true))
        assertEquals(R.string.shape_lock_oval, ShapeTransformLabels.res(ShapeType.ELLIPSE, locked = false))
    }

    @Test
    fun `a rectangle is a square or a rect`() {
        assertEquals(R.string.shape_lock_square, ShapeTransformLabels.res(ShapeType.RECTANGLE, locked = true))
        assertEquals(R.string.shape_lock_rect, ShapeTransformLabels.res(ShapeType.RECTANGLE, locked = false))
    }

    @Test
    fun `everything else says the ratio in figures`() {
        for (type in listOf(ShapeType.TRIANGLE, ShapeType.STAR, ShapeType.LINE, ShapeType.ARROW)) {
            assertEquals(type.name, R.string.shape_lock_1_1, ShapeTransformLabels.res(type, locked = true))
            assertEquals(type.name, R.string.shape_lock_free, ShapeTransformLabels.res(type, locked = false))
        }
    }

    @Test
    fun `the two states never share a label`() {
        // The button is a latch: its word has to change with its border, or the border is the only
        // thing saying anything and a border alone is a thing you have to have been told about.
        for (type in ShapeType.entries) {
            assertNotEquals(
                type.name,
                ShapeTransformLabels.res(type, locked = true),
                ShapeTransformLabels.res(type, locked = false),
            )
        }
    }
}
