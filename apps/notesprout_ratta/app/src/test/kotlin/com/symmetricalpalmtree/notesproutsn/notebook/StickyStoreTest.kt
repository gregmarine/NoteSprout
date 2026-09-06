package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky store on the shared serial [SoilWriter] — [LinkStoreTest]'s shape (a row with
 * children), transacted with a pass-through lambda since the ordering under test is the store's,
 * not Room's (arc 28 / H1).
 */
class StickyStoreTest {

    private fun sticky(id: String, order: Int = 0) = PageSticky(
        id = id, x = 10f, y = 20f, width = 72f, height = 72f, contentW = 800, contentH = 600, order = order,
    )

    private fun childStroke(id: String, x: Float = 1f) =
        Stroke(id = id, points = listOf(StrokePoint(x, x), StrokePoint(x + 5, x + 5)))

    private fun make(dao: FakeSoilDao): Pair<StickyStore, SoilWriter> {
        val writer = SoilWriter {}
        return StickyStore(dao, writer) { block -> block() } to writer
    }

    /** Seed a live content stroke directly under [stickyId], bypassing [StickyStore.setContent]
     *  so tests of the other operations don't depend on it. */
    private suspend fun FakeSoilDao.seedChild(stickyId: String, stroke: Stroke, order: Int, now: Long = 1L) {
        upsert(StrokeRows.toRow(stroke, stickyId, order, now))
    }

    @Test
    fun `create assigns tail order among the page's own stickies`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        store.create("page", sticky("b"))
        writer.drain()
        assertEquals(0, dao.rows["a"]!!.order)
        assertEquals(1, dao.rows["b"]!!.order)
        writer.close()
    }

    @Test
    fun `loadPage returns icons only — content strokes are never read on the page load path`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            store.create("page", sticky("a"))
            writer.drain()
            dao.seedChild("a", childStroke("c1"), order = 0)

            val loaded = store.loadPage("page")
            assertEquals(1, loaded.size)
            assertTrue(loaded[0].strokes.isEmpty())
            writer.close()
        }

    @Test
    fun `content reads the note's local children in writing order`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        dao.seedChild("a", childStroke("c2"), order = 1)
        dao.seedChild("a", childStroke("c1"), order = 0)

        val content = store.content("a")
        assertEquals(listOf("c1", "c2"), content.map { it.id })
        writer.close()
    }

    @Test
    fun `withContent attaches the note's live children to the icon`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        dao.seedChild("a", childStroke("c1"), order = 0)

        val icon = store.loadPage("page")[0]
        assertTrue(icon.strokes.isEmpty())
        val withContent = store.withContent(icon)
        assertEquals(listOf("c1"), withContent.strokes.map { it.id })
        writer.close()
    }

    @Test
    fun `remove soft-deletes the icon and its children together`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        dao.seedChild("a", childStroke("c1"), order = 0)

        store.remove(listOf("a"))
        writer.drain()
        assertNotNull(dao.rows["a"]!!.deletedAt)
        assertNotNull(dao.rows["c1"]!!.deletedAt)
        writer.close()
    }

    @Test
    fun `restore revives the icon and the snapshot's children`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        dao.seedChild("a", childStroke("c1"), order = 0)
        val snapshot = store.withContent(store.loadPage("page")[0])

        store.remove(listOf("a"))
        writer.drain()
        assertNotNull(dao.rows["a"]!!.deletedAt)
        assertNotNull(dao.rows["c1"]!!.deletedAt)

        store.restore("page", listOf(snapshot))
        writer.drain()
        assertNull(dao.rows["a"]!!.deletedAt)
        assertNull(dao.rows["c1"]!!.deletedAt)
        writer.close()
    }

    @Test
    fun `restore inserts a row that never existed (a paste undo-redo)`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        val ghost = sticky("ghost")
        store.restore("page", listOf(ghost))
        writer.drain()
        assertNotNull(dao.rows["ghost"])
        writer.close()
    }

    @Test
    fun `move shifts the icon only — the children are local and stay put`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        dao.seedChild("a", childStroke("c1", x = 1f), order = 0)
        val childBefore = StrokeRows.toStroke(dao.rows["c1"]!!)!!

        store.move(listOf("a"), 10f, -5f)
        writer.drain()
        assertEquals(20f, dao.rows["a"]!!.x)
        assertEquals(15f, dao.rows["a"]!!.y)
        val childAfter = StrokeRows.toStroke(dao.rows["c1"]!!)!!
        assertEquals(childBefore.points, childAfter.points)
        writer.close()
    }

    @Test
    fun `setContent drops strokes no longer in the set and upserts the rest with order = index`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            store.create("page", sticky("a"))
            writer.drain()

            store.setContent("a", listOf(childStroke("c1"), childStroke("c2")))
            writer.drain()
            assertNull(dao.rows["c1"]!!.deletedAt)
            assertEquals(0, dao.rows["c1"]!!.order)
            assertEquals(1, dao.rows["c2"]!!.order)

            // c1 dropped, c2 kept (now at index 0), c3 new (index 1).
            store.setContent("a", listOf(childStroke("c2"), childStroke("c3")))
            writer.drain()
            assertNotNull(dao.rows["c1"]!!.deletedAt)
            assertEquals(0, dao.rows["c2"]!!.order)
            assertEquals(1, dao.rows["c3"]!!.order)
            writer.close()
        }

    @Test
    fun `setContent keeps createdAt of an existing row and re-parents everything under the sticky`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            store.create("page", sticky("a"))
            writer.drain()

            store.setContent("a", listOf(childStroke("c1")))
            writer.drain()
            val createdAt = dao.rows["c1"]!!.createdAt

            store.setContent("a", listOf(childStroke("c1", x = 99f)))
            writer.drain()
            assertEquals(createdAt, dao.rows["c1"]!!.createdAt)
            assertEquals("a", dao.rows["c1"]!!.parentId)
            writer.close()
        }
}
