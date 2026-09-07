package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Insert bar places (arc 28 / H4) — og's defaults, pinned per type. */
class ShapeDefaultsTest {

    private val pageW = 1404f
    private val pageH = 1872f
    private val density = 2f

    private fun at(type: ShapeType) = ShapeDefaults.at("id", type, pageW, pageH, density)

    // ── The closed shapes ────────────────────────────────────────────────────

    @Test
    fun `a closed shape is a 72 dp square at the page centre`() {
        for (type in listOf(ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.TRIANGLE, ShapeType.STAR)) {
            val s = at(type)
            assertEquals(type.name, 144f, s.width, 0f)     // 72 dp × density 2
            assertEquals(type.name, 144f, s.height, 0f)
            assertEquals(type.name, 702f, s.cx, 0f)
            assertEquals(type.name, 936f, s.cy, 0f)
        }
    }

    @Test
    fun `rectangle, ellipse and star arrive locked — the triangle does not`() {
        assertTrue(at(ShapeType.RECTANGLE).aspectLocked)
        assertTrue(at(ShapeType.ELLIPSE).aspectLocked)
        assertTrue(at(ShapeType.STAR).aspectLocked)
        assertFalse(at(ShapeType.TRIANGLE).aspectLocked)
    }

    // ── The open shapes ──────────────────────────────────────────────────────

    @Test
    fun `a line or arrow is half the page wide, one px tall and never locked`() {
        for (type in listOf(ShapeType.LINE, ShapeType.ARROW)) {
            val s = at(type)
            assertEquals(type.name, 702f, s.width, 0f)
            assertEquals(type.name, ShapeDefaults.OPEN_HEIGHT_PX, s.height, 0f)
            assertFalse(type.name, s.aspectLocked)
            // Still centred: only the extents differ between the two families.
            assertEquals(type.name, 702f, s.cx, 0f)
            assertEquals(type.name, 936f, s.cy, 0f)
        }
    }

    @Test
    fun `only the line and the arrow are open`() {
        assertTrue(ShapeDefaults.isOpen(ShapeType.LINE))
        assertTrue(ShapeDefaults.isOpen(ShapeType.ARROW))
        for (type in listOf(ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.TRIANGLE, ShapeType.STAR)) {
            assertFalse(type.name, ShapeDefaults.isOpen(type))
        }
    }

    // ── What every fresh shape shares ────────────────────────────────────────

    @Test
    fun `every fresh shape takes the pen's width, no rotation and the default point count`() {
        for (type in ShapeType.entries) {
            val s = at(type)
            assertEquals(type.name, ShapeRows.DEFAULT_STROKE_WIDTH_PX, s.strokeWidth, 0f)
            assertEquals(type.name, 0f, s.rotationDeg, 0f)
            assertEquals(type.name, ShapeFlags.DEFAULT_POINTS, s.pointCount)
            // The store rebases it to MAX(order) + 1 among the page's shapes; nothing reads this.
            assertEquals(type.name, 0, s.order)
            assertEquals(type.name, "id", s.id)
            assertEquals(type.name, type, s.type)
        }
    }

    @Test
    fun `the defaults scale with the device, not with the page`() {
        // A closed shape is a physical 72 dp on any Supernote; a line is a fraction of the page,
        // which is why one takes the density and the other does not.
        val dense = ShapeDefaults.at("id", ShapeType.RECTANGLE, pageW, pageH, density = 3f)
        assertEquals(216f, dense.width, 0f)
        val line = ShapeDefaults.at("id", ShapeType.LINE, 800f, pageH, density = 3f)
        assertEquals(400f, line.width, 0f)
    }
}
