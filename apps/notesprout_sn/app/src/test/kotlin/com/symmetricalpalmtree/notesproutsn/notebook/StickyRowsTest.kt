package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sticky-note icon row mapping — the arc-28 additive family row type's column contract (H1).
 *  The content children ride separately (see [StickyStoreTest]); this suite covers the icon row
 *  and the pass-through of already-decoded children through [StickyRows.toSticky]. */
class StickyRowsTest {

    private val childStroke = Stroke(id = "c1", points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)))

    private val sticky = PageSticky(
        id = "sn1", x = 10f, y = 20f, width = 72f, height = 72f,
        contentW = 800, contentH = 600, order = 2,
    )

    @Test
    fun `toRow writes the locked column contract`() {
        val row = StickyRows.toRow(sticky, "page1", now = 123L)
        assertEquals("sn1", row.id)
        assertEquals("page1", row.parentId)
        assertEquals(SoilSchema.TYPE_STICKY, row.type)
        assertEquals(2, row.order)
        assertEquals(123L, row.createdAt)
        assertEquals(123L, row.updatedAt)
        assertEquals(10f, row.x)
        assertEquals(20f, row.y)
        assertEquals(72f, row.width)
        assertEquals(72f, row.height)
        assertNull(row.text)
        assertNull(row.color)
        assertNull(row.strokeWidth)
        assertNull(row.style)
        assertNull(row.blob)
        assertNull(row.refId)
        assertNull(row.deletedAt)
    }

    @Test
    fun `round trip is lossless (icon only, no children)`() {
        val row = StickyRows.toRow(sticky, "page1", now = 5L)
        assertEquals(sticky, StickyRows.toSticky(row))
    }

    @Test
    fun `children are passed through untouched, never encoded into the row`() {
        val row = StickyRows.toRow(sticky, "page1", now = 5L)
        val decoded = StickyRows.toSticky(row, listOf(childStroke))!!
        assertEquals(listOf(childStroke), decoded.strokes)
        // The row itself carries no trace of the children.
        assertNull(row.blob)
        assertNull(row.text)
    }

    @Test
    fun `childIds is the strokes' ids`() {
        val withChildren = sticky.copy(strokes = listOf(childStroke, childStroke.copy(id = "c2")))
        assertEquals(listOf("c1", "c2"), withChildren.childIds)
    }

    @Test
    fun `translated shifts the icon only — the children (local space) are untouched`() {
        val withChildren = sticky.copy(strokes = listOf(childStroke))
        val t = withChildren.translated(5f, -5f)
        assertEquals(15f, t.x, 0f)
        assertEquals(15f, t.y, 0f)
        assertEquals(sticky.width, t.width, 0f)
        assertEquals(sticky.height, t.height, 0f)
        assertEquals(withChildren.strokes, t.strokes)
        assertEquals(childStroke.points, t.strokes[0].points)
    }

    @Test
    fun `wrong type is dropped`() {
        val row = StickyRows.toRow(sticky, "page1", 1L).copy(type = SoilSchema.TYPE_SHAPE)
        assertNull(StickyRows.toSticky(row))
    }

    @Test
    fun `missing icon bounds are dropped`() {
        val base = StickyRows.toRow(sticky, "page1", 1L)
        assertNull(StickyRows.toSticky(base.copy(x = null)))
        assertNull(StickyRows.toSticky(base.copy(y = null)))
        assertNull(StickyRows.toSticky(base.copy(width = null)))
        assertNull(StickyRows.toSticky(base.copy(height = null)))
    }

    @Test
    fun `non-finite or negative icon bounds are dropped`() {
        val base = StickyRows.toRow(sticky, "page1", 1L)
        assertNull(StickyRows.toSticky(base.copy(x = Float.NaN)))
        assertNull(StickyRows.toSticky(base.copy(y = Float.POSITIVE_INFINITY)))
        assertNull(StickyRows.toSticky(base.copy(width = -1f)))
        assertNull(StickyRows.toSticky(base.copy(height = -1f)))
    }

    @Test
    fun `a zero content size is tolerated, not dropped`() {
        val row = StickyRows.toRow(sticky.copy(contentW = 0, contentH = 0), "page1", 1L)
        val decoded = StickyRows.toSticky(row)!!
        assertEquals(0, decoded.contentW)
        assertEquals(0, decoded.contentH)
    }

    @Test
    fun `a row missing entirely is not the same as a row with no geometry`() {
        val row = SoilObjectEntity(
            id = "sn2", parentId = "page1", type = SoilSchema.TYPE_STICKY, order = 0,
            createdAt = 1L, updatedAt = 1L,
        )
        assertNull(StickyRows.toSticky(row))
        assertTrue(ICON_DP_SANITY == StickyRows.ICON_DP)
    }

    private companion object {
        const val ICON_DP_SANITY = 72f
    }
}
