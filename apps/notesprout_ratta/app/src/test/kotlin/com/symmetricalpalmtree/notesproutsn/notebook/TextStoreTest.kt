package com.symmetricalpalmtree.notesproutsn.notebook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The text store on the shared serial [SoilWriter] — [HeadingStoreTest]'s shape (arc 28 / H1). */
class TextStoreTest {

    private fun text(id: String, order: Int = 0) = PageText(
        id = id, text = "text-$id", x = 1f, y = 2f, width = 100f, height = 40f, order = order,
    )

    private fun make(dao: FakeSoilDao): Pair<TextStore, SoilWriter> {
        val writer = SoilWriter {}
        return TextStore(dao, writer) to writer
    }

    @Test
    fun `create assigns tail order among the page's own texts — a heading's order does not count`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            val headings = HeadingStore(dao, writer)
            headings.create("page", Heading(id = "h1", text = "## T", level = 1, x = 0f, y = 0f, width = 1f, height = 1f, order = 0))
            writer.drain()
            // Bump the heading's own order far ahead — must not influence the text's tail order.
            dao.rows["h1"] = dao.rows["h1"]!!.copy(order = 50)

            store.create("page", text("a"))
            store.create("page", text("b"))
            writer.drain()
            assertEquals(0, dao.rows["a"]!!.order)
            assertEquals(1, dao.rows["b"]!!.order)
            writer.close()
        }

    @Test
    fun `erase then restore is in place — geometry and order survive`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", text("a", order = 0))
        store.create("page", text("b", order = 0))
        writer.drain()
        assertEquals(1, dao.rows["b"]!!.order)

        store.erase(listOf("a"))
        writer.drain()
        assertNotNull(dao.rows["a"]!!.deletedAt)

        store.restore(listOf("a"))
        writer.drain()
        val row = dao.rows["a"]!!
        assertNull(row.deletedAt)
        assertEquals(0, row.order)
        assertEquals(1f, row.x)
        assertEquals(100f, row.width)
        writer.close()
    }

    @Test
    fun `move shifts stored x-y by the delta`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", text("a"))
        writer.drain()

        store.move(listOf("a"), 10f, -3f)
        writer.drain()
        assertEquals(11f, dao.rows["a"]!!.x)
        assertEquals(-1f, dao.rows["a"]!!.y)
        writer.close()
    }

    @Test
    fun `updateContent rewrites source and box, keeping top-left`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", text("a"))
        writer.drain()

        store.updateContent(text("a").copy(text = "grown", width = 250f, height = 90f))
        writer.drain()
        val row = dao.rows["a"]!!
        assertEquals("grown", row.text)
        assertEquals(250f, row.width)
        assertEquals(90f, row.height)
        assertEquals(1f, row.x)
        assertEquals(2f, row.y)
        writer.close()
    }

    @Test
    fun `loadPage returns live texts in order and drops a malformed row`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", text("a"))
        store.create("page", text("b"))
        writer.drain()
        dao.rows["b"] = dao.rows["b"]!!.copy(text = null)   // foreign malformed row

        val loaded = store.loadPage("page")
        assertEquals(listOf("a"), loaded.map { it.id })
        writer.close()
    }
}
