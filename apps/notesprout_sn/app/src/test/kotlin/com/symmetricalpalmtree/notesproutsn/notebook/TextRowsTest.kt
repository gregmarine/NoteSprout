package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text-object row mapping — the arc-28 additive family row type's column contract (H1). */
class TextRowsTest {

    private val text = PageText(
        id = "t1", text = "Some *markdown*", x = 10f, y = 20f, width = 300f, height = 60f, order = 4,
    )

    @Test
    fun `toRow writes the locked column contract`() {
        val row = TextRows.toRow(text, "page1", now = 123L)
        assertEquals("t1", row.id)
        assertEquals("page1", row.parentId)
        assertEquals(SoilSchema.TYPE_TEXT, row.type)
        assertEquals(4, row.order)
        assertEquals(123L, row.createdAt)
        assertEquals(123L, row.updatedAt)
        assertEquals("Some *markdown*", row.text)
        assertEquals(10f, row.x)
        assertEquals(20f, row.y)
        assertEquals(300f, row.width)
        assertEquals(60f, row.height)
        assertNull(row.color)
        assertNull(row.strokeWidth)
        assertNull(row.style)
        assertNull(row.blob)
        assertNull(row.refId)
        assertNull(row.flags)
        assertNull(row.deletedAt)
    }

    @Test
    fun `round trip is lossless`() {
        val row = TextRows.toRow(text, "page1", now = 5L)
        assertEquals(text, TextRows.toText(row))
    }

    @Test
    fun `order is preserved through the round trip`() {
        val row = TextRows.toRow(text.copy(order = 9), "page1", now = 1L)
        assertEquals(9, TextRows.toText(row)!!.order)
    }

    @Test
    fun `translated shifts the box only`() {
        val t = text.translated(5f, -5f)
        assertEquals(15f, t.x, 0f)
        assertEquals(15f, t.y, 0f)
        assertEquals(text.width, t.width, 0f)
        assertEquals(text.height, t.height, 0f)
        assertEquals(text.text, t.text)
    }

    @Test
    fun `wrong type is dropped`() {
        val row = TextRows.toRow(text, "page1", 1L).copy(type = SoilSchema.TYPE_HEADING)
        assertNull(TextRows.toText(row))
    }

    @Test
    fun `a missing text is dropped`() {
        val row = TextRows.toRow(text, "page1", 1L).copy(text = null)
        assertNull(TextRows.toText(row))
    }

    @Test
    fun `a blank text is dropped`() {
        val row = TextRows.toRow(text, "page1", 1L).copy(text = "   \n\t")
        assertNull(TextRows.toText(row))
    }

    @Test
    fun `non-finite geometry is dropped`() {
        val base = TextRows.toRow(text, "page1", 1L)
        assertNull(TextRows.toText(base.copy(x = Float.NaN)))
        assertNull(TextRows.toText(base.copy(y = Float.POSITIVE_INFINITY)))
        assertNull(TextRows.toText(base.copy(width = Float.NaN)))
        assertNull(TextRows.toText(base.copy(height = Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `negative geometry is dropped`() {
        val base = TextRows.toRow(text, "page1", 1L)
        assertNull(TextRows.toText(base.copy(width = -1f)))
        assertNull(TextRows.toText(base.copy(height = -1f)))
    }

    @Test
    fun `missing x, y, width or height default to zero rather than being dropped`() {
        val row = SoilObjectEntity(
            id = "t2", parentId = "page1", type = SoilSchema.TYPE_TEXT, order = 0,
            createdAt = 1L, updatedAt = 1L, text = "hi",
        )
        val decoded = TextRows.toText(row)!!
        assertEquals(0f, decoded.x, 0f)
        assertEquals(0f, decoded.y, 0f)
        assertEquals(0f, decoded.width, 0f)
        assertEquals(0f, decoded.height, 0f)
    }
}
