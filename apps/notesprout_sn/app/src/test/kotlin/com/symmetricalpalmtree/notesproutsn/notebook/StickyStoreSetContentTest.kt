package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StickyStore.setContent] as the sticky editor's undo replay uses it (arc 28 / H5): a whole set,
 * written in either direction. [StickyStoreTest] covers the forward pass; what is pinned here is the
 * pass **back** — the one an undo of an erase takes, where a stroke that was soft-deleted by an
 * earlier write has to come back **in place**, keeping its `createdAt`, rather than being written as
 * a second row or staying dead under the new one.
 */
class StickyStoreSetContentTest {

    private fun sticky(id: String) = PageSticky(
        id = id, x = 10f, y = 20f, width = 72f, height = 72f, contentW = 800, contentH = 600, order = 0,
    )

    private fun childStroke(id: String, x: Float = 1f) =
        Stroke(id = id, points = listOf(StrokePoint(x, x), StrokePoint(x + 5, x + 5)))

    private fun make(dao: FakeSoilDao): Pair<StickyStore, SoilWriter> {
        val writer = SoilWriter {}
        return StickyStore(dao, writer) { block -> block() } to writer
    }

    @Test
    fun `a soft-deleted stroke comes back alive, in place, with its original createdAt`() =
        runBlocking {
            val dao = FakeSoilDao()
            val (store, writer) = make(dao)
            store.create("page", sticky("a"))
            writer.drain()

            store.setContent("a", listOf(childStroke("c1"), childStroke("c2")))
            writer.drain()
            val createdAt = dao.rows["c1"]!!.createdAt

            // The erase: c1 leaves the set and its row is soft-deleted.
            store.setContent("a", listOf(childStroke("c2")))
            writer.drain()
            assertNotNull(dao.rows["c1"]!!.deletedAt)
            assertEquals(0, dao.rows["c2"]!!.order)

            // The undo: the whole earlier set is written back.
            store.setContent("a", listOf(childStroke("c1"), childStroke("c2")))
            writer.drain()
            assertNull("c1 is live again", dao.rows["c1"]!!.deletedAt)
            assertEquals("and back at its own index", 0, dao.rows["c1"]!!.order)
            assertEquals(1, dao.rows["c2"]!!.order)
            assertEquals("a", dao.rows["c1"]!!.parentId)
            assertEquals(createdAt, dao.rows["c1"]!!.createdAt)
            // No second row was minted for it.
            assertEquals(listOf("c1", "c2"), store.content("a").map { it.id })
            writer.close()
        }

    @Test
    fun `writing order is the note's order — a re-sequenced set rewrites both rows`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()

        store.setContent("a", listOf(childStroke("c1"), childStroke("c2")))
        writer.drain()
        store.setContent("a", listOf(childStroke("c2"), childStroke("c1")))
        writer.drain()

        assertEquals(0, dao.rows["c2"]!!.order)
        assertEquals(1, dao.rows["c1"]!!.order)
        assertEquals(listOf("c2", "c1"), store.content("a").map { it.id })
        writer.close()
    }

    @Test
    fun `an empty set clears the note without touching the icon`() = runBlocking {
        val dao = FakeSoilDao()
        val (store, writer) = make(dao)
        store.create("page", sticky("a"))
        writer.drain()
        store.setContent("a", listOf(childStroke("c1")))
        writer.drain()

        store.setContent("a", emptyList())
        writer.drain()
        assertNotNull(dao.rows["c1"]!!.deletedAt)
        assertNull("the note itself survives an emptied note", dao.rows["a"]!!.deletedAt)
        assertEquals(emptyList<String>(), store.content("a").map { it.id })
        writer.close()
    }
}
