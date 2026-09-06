package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where an inserted text object lands (arc 28 / H2). The measuring needs a `StaticLayout` and so a
 * device; the placing is arithmetic, and this is it.
 */
class TextPlacementTest {

    @Test
    fun `a box that fits is centred`() {
        val (x, y) = TextPlacement.centred(1000f, 800f, 400f, 200f)
        assertEquals(300f, x, 0f)
        assertEquals(300f, y, 0f)
    }

    @Test
    fun `a box exactly the page's size starts at the origin`() {
        val (x, y) = TextPlacement.centred(1000f, 800f, 1000f, 800f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun `an over-wide or over-tall box is clamped to zero, not centred into overflow`() {
        // The top-left of something too big is the part worth having on screen (the paste rule).
        val (x, y) = TextPlacement.centred(1000f, 800f, 1400f, 1200f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun `the two axes are independent`() {
        val (x, y) = TextPlacement.centred(1000f, 800f, 1400f, 200f)
        assertEquals(0f, x, 0f)
        assertEquals(300f, y, 0f)
    }

    @Test
    fun `a zero-size box lands at the page's middle`() {
        val (x, y) = TextPlacement.centred(1000f, 800f, 0f, 0f)
        assertEquals(500f, x, 0f)
        assertEquals(400f, y, 0f)
    }

    @Test
    fun `a non-finite measurement places that axis at the origin, and only that axis`() {
        // A page row with no size, or a measure that came back NaN: inventing a position from it
        // would put the object somewhere the user cannot reach.
        val (x1, y1) = TextPlacement.centred(Float.NaN, 800f, 100f, 100f)
        assertEquals(0f, x1, 0f)
        assertEquals(350f, y1, 0f)
        val (x2, y2) = TextPlacement.centred(1000f, 800f, Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(0f, x2, 0f)
        assertEquals(0f, y2, 0f)
    }
}
