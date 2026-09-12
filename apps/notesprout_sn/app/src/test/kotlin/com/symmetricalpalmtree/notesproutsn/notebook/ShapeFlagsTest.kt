package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape row's packed `flags` word (arc 28 / H1): aspect lock (bit 0), point count (bits
 *  8–15), rotation in tenths of a degree (bits 16–31) — three fields sharing one 64-bit column. */
class ShapeFlagsTest {

    // ── Aspect lock ──────────────────────────────────────────────────────────

    @Test
    fun `aspect lock round-trips both ways`() {
        assertTrue(ShapeFlags.aspectLocked(ShapeFlags.pack(true, 5, 0f)))
        assertFalse(ShapeFlags.aspectLocked(ShapeFlags.pack(false, 5, 0f)))
    }

    // ── Point count ──────────────────────────────────────────────────────────

    @Test
    fun `every legal point count 5 through 12 round-trips`() {
        for (n in ShapeFlags.MIN_POINTS..ShapeFlags.MAX_POINTS) {
            assertEquals("n=$n", n, ShapeFlags.pointCount(ShapeFlags.pack(false, n, 0f)))
        }
    }

    @Test
    fun `zero point count reads as the default`() {
        assertEquals(ShapeFlags.DEFAULT_POINTS, ShapeFlags.pointCount(0L))
        assertEquals(ShapeFlags.DEFAULT_POINTS, ShapeFlags.pointCount(null))
    }

    @Test
    fun `a point count below the minimum is coerced up at pack time`() {
        assertEquals(5, ShapeFlags.pointCount(ShapeFlags.pack(false, 4, 0f)))
    }

    @Test
    fun `a point count above the maximum is coerced down at pack time`() {
        assertEquals(12, ShapeFlags.pointCount(ShapeFlags.pack(false, 13, 0f)))
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Test
    fun `rotation round-trips at zero`() {
        assertEquals(0f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 0f)), 0f)
    }

    @Test
    fun `rotation round-trips at 37 degrees`() {
        assertEquals(37f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 37f)), 0f)
    }

    @Test
    fun `rotation round-trips at 90 degrees`() {
        assertEquals(90f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 90f)), 0f)
    }

    @Test
    fun `rotation round-trips at 359point9 degrees`() {
        assertEquals(359.9f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 359.9f)), 0.001f)
    }

    @Test
    fun `360 degrees normalizes to zero`() {
        assertEquals(0f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 360f)), 0f)
    }

    @Test
    fun `a negative angle normalizes into range`() {
        assertEquals(350f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, -10f)), 0.001f)
    }

    @Test
    fun `a sub-tenth angle rounds to the nearest tenth`() {
        assertEquals(123.5f, ShapeFlags.rotationDeg(ShapeFlags.pack(false, 5, 123.456f)), 0.001f)
    }

    @Test
    fun `rotationTenths normalizes into 0 until 3599`() {
        assertEquals(0, ShapeFlags.rotationTenths(0f))
        assertEquals(0, ShapeFlags.rotationTenths(360f))
        assertEquals(3500, ShapeFlags.rotationTenths(-10f))
        assertEquals(3599, ShapeFlags.rotationTenths(359.9f))
        assertEquals(1235, ShapeFlags.rotationTenths(123.456f))
    }

    @Test
    fun `a non-finite angle normalizes as zero rather than crashing`() {
        assertEquals(0, ShapeFlags.rotationTenths(Float.NaN))
        assertEquals(0, ShapeFlags.rotationTenths(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `normalizeDeg is the stored form of a rotation`() {
        assertEquals(359.9f, ShapeFlags.normalizeDeg(359.9f), 0.001f)
        assertEquals(0f, ShapeFlags.normalizeDeg(360f), 0f)
        assertEquals(123.5f, ShapeFlags.normalizeDeg(123.456f), 0.001f)
        assertEquals(350f, ShapeFlags.normalizeDeg(-10f), 0.001f)
    }

    @Test
    fun `two angles that normalize alike pack alike`() {
        assertEquals(ShapeFlags.pack(true, 7, 0f), ShapeFlags.pack(true, 7, 360f))
    }

    // ── No bleed between fields ──────────────────────────────────────────────

    @Test
    fun `the three fields do not bleed into each other at their extremes`() {
        val flags = ShapeFlags.pack(true, 12, 359.9f)
        assertTrue(ShapeFlags.aspectLocked(flags))
        assertEquals(12, ShapeFlags.pointCount(flags))
        assertEquals(359.9f, ShapeFlags.rotationDeg(flags), 0.001f)

        val other = ShapeFlags.pack(false, 5, 0f)
        assertFalse(ShapeFlags.aspectLocked(other))
        assertEquals(5, ShapeFlags.pointCount(other))
        assertEquals(0f, ShapeFlags.rotationDeg(other), 0f)
    }

    @Test
    fun `a high rotation does not leak into the point count or aspect bit`() {
        // A rotation near the top of its 16-bit field with the smallest legal point count and
        // the aspect bit clear — any shift-width mistake would show up as a nonzero point count
        // or a set aspect bit here.
        val flags = ShapeFlags.pack(false, 5, 359.9f)
        assertFalse(ShapeFlags.aspectLocked(flags))
        assertEquals(5, ShapeFlags.pointCount(flags))
    }

    @Test
    fun `a maxed-out point count does not leak into the rotation`() {
        val flags = ShapeFlags.pack(true, 12, 0f)
        assertEquals(0f, ShapeFlags.rotationDeg(flags), 0f)
    }
}
