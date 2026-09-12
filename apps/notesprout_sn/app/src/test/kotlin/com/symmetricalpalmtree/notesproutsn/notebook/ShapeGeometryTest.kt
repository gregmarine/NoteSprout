package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure geometry behind every shape (arc 28 / H1, D3): [ShapeGeometry.outline],
 * [ShapeGeometry.tightBounds] and [ShapeGeometry.aabb], for every [ShapeType] at 0° and 37° —
 * `pathFor` is untested here (it needs a real `android.graphics.Path`, unavailable under
 * `returnDefaultValues`). Tolerance throughout: [TOL].
 */
class ShapeGeometryTest {

    private val cx = 100f
    private val cy = 200f
    private val width = 120f   // hw = 60
    private val height = 80f   // hh = 40
    private val hw = width / 2f
    private val hh = height / 2f

    private fun shape(
        type: ShapeType,
        rot: Float = 0f,
        sw: Float = 6f,
        points: Int = 6,
    ) = PageShape(
        id = "s", type = type, cx = cx, cy = cy, width = width, height = height,
        strokeWidth = sw, rotationDeg = rot, aspectLocked = false, pointCount = points, order = 0,
    )

    // ── outline() point counts — every type, both angles ────────────────────

    @Test
    fun `rectangle outline is one closed polygon of 4 points`() {
        for (rot in listOf(0f, 37f)) {
            val polys = ShapeGeometry.outline(shape(ShapeType.RECTANGLE, rot))
            assertEquals(1, polys.size)
            assertEquals(4, polys[0].points.size)
            assertTrue(polys[0].closed)
        }
    }

    @Test
    fun `ellipse outline is the box's 4 rotated corners, closed`() {
        for (rot in listOf(0f, 37f)) {
            val polys = ShapeGeometry.outline(shape(ShapeType.ELLIPSE, rot))
            assertEquals(1, polys.size)
            assertEquals(4, polys[0].points.size)
            assertTrue(polys[0].closed)
        }
    }

    @Test
    fun `triangle outline is one closed polygon of 3 points`() {
        for (rot in listOf(0f, 37f)) {
            val polys = ShapeGeometry.outline(shape(ShapeType.TRIANGLE, rot))
            assertEquals(1, polys.size)
            assertEquals(3, polys[0].points.size)
            assertTrue(polys[0].closed)
        }
    }

    @Test
    fun `star outline is one closed polygon of 2n points`() {
        for (rot in listOf(0f, 37f)) {
            for (n in ShapeFlags.MIN_POINTS..ShapeFlags.MAX_POINTS) {
                val polys = ShapeGeometry.outline(shape(ShapeType.STAR, rot, points = n))
                assertEquals(1, polys.size)
                assertEquals(2 * n, polys[0].points.size)
                assertTrue(polys[0].closed)
            }
        }
    }

    @Test
    fun `line outline is one open segment of 2 points`() {
        for (rot in listOf(0f, 37f)) {
            val polys = ShapeGeometry.outline(shape(ShapeType.LINE, rot))
            assertEquals(1, polys.size)
            assertEquals(2, polys[0].points.size)
            assertTrue(!polys[0].closed)
        }
    }

    @Test
    fun `arrow outline is a shaft plus two arms, both open`() {
        for (rot in listOf(0f, 37f)) {
            val polys = ShapeGeometry.outline(shape(ShapeType.ARROW, rot))
            assertEquals(2, polys.size)
            assertEquals(2, polys[0].points.size)   // shaft
            assertTrue(!polys[0].closed)
            assertEquals(3, polys[1].points.size)   // both arms, one polyline
            assertTrue(!polys[1].closed)
        }
    }

    // ── tightBounds() at 0° ──────────────────────────────────────────────────

    @Test
    fun `rectangle tight bounds equal the box at 0 degrees`() {
        val b = ShapeGeometry.tightBounds(shape(ShapeType.RECTANGLE, 0f))
        assertEquals(cx - hw, b.left, TOL)
        assertEquals(cy - hh, b.top, TOL)
        assertEquals(cx + hw, b.right, TOL)
        assertEquals(cy + hh, b.bottom, TOL)
    }

    @Test
    fun `ellipse tight bounds equal the box at 0 degrees`() {
        val b = ShapeGeometry.tightBounds(shape(ShapeType.ELLIPSE, 0f))
        assertEquals(cx - hw, b.left, TOL)
        assertEquals(cy - hh, b.top, TOL)
        assertEquals(cx + hw, b.right, TOL)
        assertEquals(cy + hh, b.bottom, TOL)
    }

    @Test
    fun `triangle tight bounds equal the box at 0 degrees — apex and base both touch it`() {
        val b = ShapeGeometry.tightBounds(shape(ShapeType.TRIANGLE, 0f))
        assertEquals(cx - hw, b.left, TOL)
        assertEquals(cy - hh, b.top, TOL)
        assertEquals(cx + hw, b.right, TOL)
        assertEquals(cy + hh, b.bottom, TOL)
    }

    @Test
    fun `line tight bounds has zero height at 0 degrees`() {
        val b = ShapeGeometry.tightBounds(shape(ShapeType.LINE, 0f))
        assertEquals(cx - hw, b.left, TOL)
        assertEquals(cx + hw, b.right, TOL)
        assertEquals(cy, b.top, TOL)
        assertEquals(cy, b.bottom, TOL)
        assertEquals(0f, b.height, TOL)
    }

    @Test
    fun `arrow tight bounds is the box grown vertically by the arms' reach`() {
        val arm = min(width * ShapeGeometry.ARROW_ARM_FRACTION, ShapeGeometry.ARROW_ARM_MAX_PX)
        val armRad = Math.toRadians(150.0)
        val ay = abs((arm * sin(armRad)).toFloat())
        val b = ShapeGeometry.tightBounds(shape(ShapeType.ARROW, 0f))
        assertEquals(cx - hw, b.left, TOL)
        assertEquals(cx + hw, b.right, TOL)
        assertEquals(cy - ay, b.top, TOL)
        assertEquals(cy + ay, b.bottom, TOL)
    }

    @Test
    fun `star's top outer vertex sits at the top of the box for 0 degrees`() {
        val polys = ShapeGeometry.outline(shape(ShapeType.STAR, 0f, points = 5))
        val top = polys[0].points[0]
        assertEquals(cx, top.x, TOL)
        assertEquals(cy - hh, top.y, TOL)
    }

    // ── rotation ─────────────────────────────────────────────────────────────

    @Test
    fun `a rectangle at 90 degrees swaps width and height`() {
        val b = ShapeGeometry.tightBounds(shape(ShapeType.RECTANGLE, 90f))
        assertEquals(cx - hh, b.left, TOL)
        assertEquals(cy - hw, b.top, TOL)
        assertEquals(cx + hh, b.right, TOL)
        assertEquals(cy + hw, b.bottom, TOL)
    }

    @Test
    fun `a rectangle at 37 degrees matches the analytic w cos + h sin formula`() {
        val rad = Math.toRadians(37.0)
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val expectedWidth = width * c + height * s
        val expectedHeight = width * s + height * c
        val b = ShapeGeometry.tightBounds(shape(ShapeType.RECTANGLE, 37f))
        assertEquals(expectedWidth, b.width, TOL)
        assertEquals(expectedHeight, b.height, TOL)
    }

    @Test
    fun `an ellipse at 37 degrees matches its analytic half-extents`() {
        val rad = Math.toRadians(37.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val a = hw
        val bHalf = hh
        val ex = sqrt(a * a * c * c + bHalf * bHalf * s * s)
        val ey = sqrt(a * a * s * s + bHalf * bHalf * c * c)
        val bounds = ShapeGeometry.tightBounds(shape(ShapeType.ELLIPSE, 37f))
        assertEquals(cx - ex, bounds.left, TOL)
        assertEquals(cy - ey, bounds.top, TOL)
        assertEquals(cx + ex, bounds.right, TOL)
        assertEquals(cy + ey, bounds.bottom, TOL)
    }

    @Test
    fun `rotation about the centre keeps the centre — mean of a rectangle's corners`() {
        for (rot in listOf(0f, 37f, 71f, 200f)) {
            val poly = ShapeGeometry.outline(shape(ShapeType.RECTANGLE, rot))[0]
            val meanX = poly.points.map { it.x }.average().toFloat()
            val meanY = poly.points.map { it.y }.average().toFloat()
            assertEquals("rot=$rot", cx, meanX, TOL)
            assertEquals("rot=$rot", cy, meanY, TOL)
        }
    }

    // ── aabb() = tightBounds inflated by max(sw/2, 4dp) ─────────────────────

    @Test
    fun `aabb inflates by the density floor when strokeWidth is thin`() {
        val density = 2f
        val s = shape(ShapeType.RECTANGLE, 0f, sw = 6f)   // sw/2 = 3, 4*density = 8
        val tight = ShapeGeometry.tightBounds(s)
        val aabb = ShapeGeometry.aabb(s, density)
        assertEquals(tight.left - 8f, aabb.left, TOL)
        assertEquals(tight.top - 8f, aabb.top, TOL)
        assertEquals(tight.right + 8f, aabb.right, TOL)
        assertEquals(tight.bottom + 8f, aabb.bottom, TOL)
    }

    @Test
    fun `aabb inflates by half the strokeWidth when it exceeds the density floor`() {
        val density = 2f
        val s = shape(ShapeType.RECTANGLE, 0f, sw = 40f)   // sw/2 = 20, 4*density = 8
        val tight = ShapeGeometry.tightBounds(s)
        val aabb = ShapeGeometry.aabb(s, density)
        assertEquals(tight.left - 20f, aabb.left, TOL)
        assertEquals(tight.top - 20f, aabb.top, TOL)
        assertEquals(tight.right + 20f, aabb.right, TOL)
        assertEquals(tight.bottom + 20f, aabb.bottom, TOL)
    }

    private companion object {
        const val TOL = 1e-3f
    }
}
