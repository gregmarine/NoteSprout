package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Insert bar's routing table (arc 28 / H4): which of the eight kinds is a shape, and which
 * one. It is the screen's whole `onInsert` decision, so it is worth a test rather than a `when`
 * nobody can read — and it is what keeps the offer loop honest, since a kind is offered in release
 * exactly when something takes it somewhere.
 */
class InsertBarKindsTest {

    @Test
    fun `the six shape kinds map to their types, in the bar's order`() {
        assertEquals(ShapeType.RECTANGLE, InsertBar.shapeType(InsertBar.Kind.RECTANGLE))
        assertEquals(ShapeType.ELLIPSE, InsertBar.shapeType(InsertBar.Kind.ELLIPSE))
        assertEquals(ShapeType.TRIANGLE, InsertBar.shapeType(InsertBar.Kind.TRIANGLE))
        assertEquals(ShapeType.LINE, InsertBar.shapeType(InsertBar.Kind.LINE))
        assertEquals(ShapeType.ARROW, InsertBar.shapeType(InsertBar.Kind.ARROW))
        assertEquals(ShapeType.STAR, InsertBar.shapeType(InsertBar.Kind.STAR))
    }

    @Test
    fun `text and sticky are not shapes`() {
        assertNull(InsertBar.shapeType(InsertBar.Kind.TEXT))
        assertNull(InsertBar.shapeType(InsertBar.Kind.STICKY))
    }

    @Test
    fun `every shape type is reachable from the bar`() {
        val offered = InsertBar.Kind.entries.mapNotNull { InsertBar.shapeType(it) }.toSet()
        assertEquals(ShapeType.entries.toSet(), offered)
    }
}
