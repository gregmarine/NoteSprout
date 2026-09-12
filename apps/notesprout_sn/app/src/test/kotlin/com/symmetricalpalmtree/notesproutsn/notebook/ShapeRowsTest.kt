package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape row mapping — the arc-28 additive family row type's column contract (H1). */
class ShapeRowsTest {

    private fun shape(type: ShapeType) = PageShape(
        id = "s1", type = type, cx = 100f, cy = 200f, width = 120f, height = 80f,
        strokeWidth = 6f, rotationDeg = 37f, aspectLocked = true, pointCount = 7, order = 3,
    )

    @Test
    fun `every shape type round-trips, style holds the type name, x-y is the centre`() {
        for (type in ShapeType.entries) {
            val s = shape(type)
            val row = ShapeRows.toRow(s, "page1", now = 5L)
            assertEquals(type.name, row.style)
            assertEquals(100f, row.x)
            assertEquals(200f, row.y)
            assertEquals(s, ShapeRows.toShape(row))
        }
    }

    @Test
    fun `toRow writes the locked column contract`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", now = 123L)
        assertEquals("s1", row.id)
        assertEquals("page1", row.parentId)
        assertEquals(SoilSchema.TYPE_SHAPE, row.type)
        assertEquals(3, row.order)
        assertEquals(123L, row.createdAt)
        assertEquals(123L, row.updatedAt)
        assertEquals(120f, row.width)
        assertEquals(80f, row.height)
        assertEquals(6f, row.strokeWidth)
        assertNull(row.text)
        assertNull(row.color)
        assertNull(row.blob)
        assertNull(row.refId)
        assertNull(row.deletedAt)
    }

    @Test
    fun `wrong type is dropped`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(type = SoilSchema.TYPE_TEXT)
        assertNull(ShapeRows.toShape(row))
    }

    @Test
    fun `an unknown style is dropped`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(style = "PENTAGON")
        assertNull(ShapeRows.toShape(row))
        val missing = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(style = null)
        assertNull(ShapeRows.toShape(missing))
    }

    @Test
    fun `missing centre or extent is dropped`() {
        val base = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L)
        assertNull(ShapeRows.toShape(base.copy(x = null)))
        assertNull(ShapeRows.toShape(base.copy(y = null)))
        assertNull(ShapeRows.toShape(base.copy(width = null)))
        assertNull(ShapeRows.toShape(base.copy(height = null)))
    }

    @Test
    fun `non-finite or negative geometry is dropped`() {
        val base = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L)
        assertNull(ShapeRows.toShape(base.copy(x = Float.NaN)))
        assertNull(ShapeRows.toShape(base.copy(y = Float.POSITIVE_INFINITY)))
        assertNull(ShapeRows.toShape(base.copy(width = -1f)))
        assertNull(ShapeRows.toShape(base.copy(height = -1f)))
    }

    @Test
    fun `a missing strokeWidth reads as the default`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(strokeWidth = null)
        assertEquals(ShapeRows.DEFAULT_STROKE_WIDTH_PX, ShapeRows.toShape(row)!!.strokeWidth, 0f)
    }

    @Test
    fun `a zero strokeWidth reads as the default too`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(strokeWidth = 0f)
        assertEquals(ShapeRows.DEFAULT_STROKE_WIDTH_PX, ShapeRows.toShape(row)!!.strokeWidth, 0f)
    }

    @Test
    fun `a negative strokeWidth reads as the default too`() {
        val row = ShapeRows.toRow(shape(ShapeType.RECTANGLE), "page1", 1L).copy(strokeWidth = -3f)
        assertEquals(ShapeRows.DEFAULT_STROKE_WIDTH_PX, ShapeRows.toShape(row)!!.strokeWidth, 0f)
    }

    @Test
    fun `flags decode rotation, lock and point count together`() {
        val s = shape(ShapeType.STAR).copy(rotationDeg = 123.456f, aspectLocked = false, pointCount = 9)
        val row = ShapeRows.toRow(s, "page1", 1L)
        val decoded = ShapeRows.toShape(row)!!
        assertEquals(123.5f, decoded.rotationDeg, 0.001f)
        assertEquals(false, decoded.aspectLocked)
        assertEquals(9, decoded.pointCount)
    }

    @Test
    fun `typeOf resolves a known style and rejects a foreign one`() {
        assertEquals(ShapeType.STAR, ShapeRows.typeOf("STAR"))
        assertNull(ShapeRows.typeOf("PENTAGON"))
        assertNull(ShapeRows.typeOf(null))
    }

    @Test
    fun `translated shifts the centre only`() {
        val s = shape(ShapeType.ELLIPSE)
        val t = s.translated(5f, -5f)
        assertEquals(105f, t.cx, 0f)
        assertEquals(195f, t.cy, 0f)
        assertEquals(s.width, t.width, 0f)
        assertTrue(t.type == s.type)
    }

    @Test
    fun `a row with no geometry columns at all is dropped, not crashed on`() {
        val row = SoilObjectEntity(
            id = "s2", parentId = "page1", type = SoilSchema.TYPE_SHAPE, order = 0,
            createdAt = 1L, updatedAt = 1L, style = "RECTANGLE",
        )
        assertNull(ShapeRows.toShape(row))
    }
}
