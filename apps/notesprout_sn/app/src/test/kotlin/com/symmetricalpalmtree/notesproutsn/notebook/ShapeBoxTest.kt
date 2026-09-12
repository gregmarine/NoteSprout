package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import org.junit.Assert.assertEquals
import org.junit.Test

/** The row ↔ engine translation (arc 28 / H4): [ShapeBox] is the only place the two models meet. */
class ShapeBoxTest {

    private fun shape(
        rotation: Float = 0f,
        locked: Boolean = true,
        type: ShapeType = ShapeType.RECTANGLE,
    ) = PageShape(
        id = "s1", type = type, cx = 100f, cy = 200f, width = 60f, height = 40f,
        strokeWidth = 3f, rotationDeg = rotation, aspectLocked = locked,
        pointCount = ShapeFlags.DEFAULT_POINTS, order = 7,
    )

    @Test
    fun `a shape's geometry is the box's, field for field`() {
        val box = ShapeBox.toBox(shape(rotation = 37f))
        assertEquals(100f, box.cx, 0f)
        assertEquals(200f, box.cy, 0f)
        assertEquals(60f, box.w, 0f)
        assertEquals(40f, box.h, 0f)
        assertEquals(37f, box.rotationDeg, 0f)
    }

    @Test
    fun `a box round-trips through a shape unchanged`() {
        val s = shape(rotation = 90f)
        assertEquals(ShapeBox.toBox(s), ShapeBox.toBox(ShapeBox.applied(s, ShapeBox.toBox(s))))
    }

    @Test
    fun `applying a box writes the geometry and nothing else`() {
        val s = shape(rotation = 0f, locked = true, type = ShapeType.STAR)
        val after = ShapeBox.applied(s, OrientedBox(10f, 20f, 300f, 400f, 45f))
        assertEquals(10f, after.cx, 0f)
        assertEquals(20f, after.cy, 0f)
        assertEquals(300f, after.width, 0f)
        assertEquals(400f, after.height, 0f)
        assertEquals(45f, after.rotationDeg, 0f)
        // The shape's own identity is untouched — the engine knows none of it.
        assertEquals("s1", after.id)
        assertEquals(ShapeType.STAR, after.type)
        assertEquals(3f, after.strokeWidth, 0f)
        assertEquals(ShapeFlags.DEFAULT_POINTS, after.pointCount)
        assertEquals(7, after.order)
        assertEquals(true, after.aspectLocked)
    }

    @Test
    fun `an angle is normalised to the tenth the row can hold`() {
        // 359.96° packs to 0 tenths. Without the normalisation, the shape written back would
        // compare unequal to the one that came in and every exit would record an undo entry for a
        // rotation nobody changed.
        val s = shape(rotation = 0f)
        val after = ShapeBox.applied(s, OrientedBox(s.cx, s.cy, s.width, s.height, 359.96f))
        assertEquals(0f, after.rotationDeg, 0f)
        assertEquals(s, after)
    }

    @Test
    fun `a rotation that survives the tenth is kept`() {
        val s = shape(rotation = 0f)
        val after = ShapeBox.applied(s, OrientedBox(s.cx, s.cy, s.width, s.height, 37.04f))
        assertEquals(37f, after.rotationDeg, 0f)
        // …and it packs and unpacks to the same number, which is what makes the comparison honest.
        assertEquals(
            after.rotationDeg,
            ShapeFlags.rotationDeg(ShapeFlags.pack(after.aspectLocked, after.pointCount, after.rotationDeg)),
            0f,
        )
    }
}
