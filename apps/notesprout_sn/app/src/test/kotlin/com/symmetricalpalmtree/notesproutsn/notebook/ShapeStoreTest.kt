package com.symmetricalpalmtree.notesproutsn.notebook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The shape store on the shared serial [SoilWriter] — [HeadingStoreTest]'s shape (arc 28 / H1). */
class ShapeStoreTest {

    private fun shape(id: String, order: Int = 0) = PageShape(
        id = id, type = ShapeType.RECTANGLE, cx = 10f, cy = 20f, width = 100f, height = 50f,
        strokeWidth = 3f, rotationDeg = 0f, aspectLocked = false, pointCount = 5, order = order,
    )

    private fun make(dao: FakeSoilDao): Pair<ShapeStore, SoilWriter> {
        val writer = SoilWriter {}
        return ShapeStore(dao, writer) to writer
    }

    @Test
    fun `create assigns tail order among the page's own shapes — a heading's order does not count`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            val headings = HeadingStore(dao, writer)
            headings.create("page", Heading(id = "h1", text = "## T", level = 1, x = 0f, y = 0f, width = 1f, height = 1f, order = 0))
            writer.drain()
            dao.rows["h1"] = dao.rows["h1"]!!.copy(order = 50)

            store.create("page", shape("a"))
            store.create("page", shape("b"))
            writer.drain()
            assertEquals(0, dao.rows["a"]!!.order)
            assertEquals(1, dao.rows["b"]!!.order)
            writer.close()
        }

    @Test
    fun `erase then restore is in place — geometry, rotation and order survive`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", shape("a").copy(rotationDeg = 45f))
        writer.drain()

        store.erase(listOf("a"))
        writer.drain()
        assertNotNull(dao.rows["a"]!!.deletedAt)

        store.restore(listOf("a"))
        writer.drain()
        val row = dao.rows["a"]!!
        assertNull(row.deletedAt)
        assertEquals(0, row.order)
        assertEquals(10f, row.x)
        assertEquals(20f, row.y)
        assertEquals(45f, ShapeFlags.rotationDeg(row.flags), 0.001f)
        writer.close()
    }

    @Test
    fun `move shifts stored centre by the delta`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", shape("a"))
        writer.drain()

        store.move(listOf("a"), 5f, -2f)
        writer.drain()
        assertEquals(15f, dao.rows["a"]!!.x)
        assertEquals(18f, dao.rows["a"]!!.y)
        writer.close()
    }

    @Test
    fun `transform rewrites all geometry and flags but leaves style and strokeWidth untouched`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            store.create("page", shape("a"))
            writer.drain()
            val styleBefore = dao.rows["a"]!!.style
            val strokeWidthBefore = dao.rows["a"]!!.strokeWidth

            val transformed = shape("a", order = 0).copy(
                cx = 99f, cy = 88f, width = 200f, height = 150f,
                rotationDeg = 90f, aspectLocked = true, pointCount = 8,
            )
            store.transform(transformed)
            writer.drain()
            val row = dao.rows["a"]!!
            assertEquals(99f, row.x)
            assertEquals(88f, row.y)
            assertEquals(200f, row.width)
            assertEquals(150f, row.height)
            assertEquals(90f, ShapeFlags.rotationDeg(row.flags), 0.001f)
            assertEquals(true, ShapeFlags.aspectLocked(row.flags))
            assertEquals(8, ShapeFlags.pointCount(row.flags))
            assertEquals(styleBefore, row.style)
            assertEquals(strokeWidthBefore, row.strokeWidth)
            writer.close()
        }

    @Test
    fun `loadPage returns live shapes in order and drops a malformed row`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", shape("a"))
        store.create("page", shape("b"))
        writer.drain()
        dao.rows["b"] = dao.rows["b"]!!.copy(style = "PENTAGON")   // foreign unknown style

        val loaded = store.loadPage("page")
        assertEquals(listOf("a"), loaded.map { it.id })
        assertFalse(loaded.map { it.id }.contains("b"))
        writer.close()
    }
}
