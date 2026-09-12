package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's read-only page gather (K2): loose content stays loose, wrapped content arrives
 * inside its [PageLink], soft-deleted rows never show, and page rows map to [PickerPage].
 * Seeded through the real stores over [FakeSoilDao] — the rows are exactly the screen's.
 */
class PageReadsTest {

    private val payload = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "target")

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))

    private fun heading(id: String) = Heading(
        id = id, text = "## T-$id", level = 2, x = 1f, y = 2f, width = 100f, height = 40f, order = 0,
    )

    @Test
    fun `content splits loose rows from wrapped ones`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        val headings = HeadingStore(dao, writer)
        val links = LinkStore(dao, writer) { block -> block() }
        strokes.commit("page", stroke("s1"))
        strokes.commit("page", stroke("s2"))
        headings.create("page", heading("h1"))
        writer.drain()
        links.create(
            "page",
            PageLink(
                id = "l1", payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
                x = 0f, y = 0f, width = 120f, height = 60f, order = 0,
                strokes = listOf(stroke("s2")), headings = listOf(heading("h1")),
            ),
        )
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(listOf("s1"), content.strokes.map { it.id })
        assertTrue(content.headings.isEmpty())
        assertEquals(1, content.links.size)
        assertEquals(listOf("s2"), content.links[0].strokes.map { it.id })
        assertEquals(listOf("h1"), content.links[0].headings.map { it.id })
        writer.close()
    }

    @Test
    fun `soft-deleted content never previews`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        strokes.commit("page", stroke("s1"))
        strokes.commit("page", stroke("s2"))
        strokes.erase(listOf("s2"))
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(listOf("s1"), content.strokes.map { it.id })
        writer.close()
    }

    // ── Arc 28 (H1): text objects, shapes and sticky notes ──────────────────

    private fun text(id: String) =
        PageText(id = id, text = "on-page **text**", x = 5f, y = 6f, width = 90f, height = 40f, order = 0)

    private fun shape(id: String) = PageShape(
        id = id, type = ShapeType.ELLIPSE, cx = 300f, cy = 200f, width = 60f, height = 40f,
        strokeWidth = ShapeRows.DEFAULT_STROKE_WIDTH_PX, rotationDeg = 30f, aspectLocked = true,
        pointCount = ShapeFlags.DEFAULT_POINTS, order = 0,
    )

    private fun note(id: String) = PageSticky(
        id = id, x = 400f, y = 40f, width = 72f, height = 72f, contentW = 1404, contentH = 1800, order = 0,
    )

    /**
     * The loose/wrapped split holds for all three new kinds, and a sticky comes back **icon-only**
     * on both levels — the page's whole knowledge of a note is its icon (D2), and this read is the
     * one that feeds every page raster (preview, cover, PDF bake).
     */
    @Test
    fun `content splits the three new kinds loose from wrapped, stickies icon-only`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val links = LinkStore(dao, writer) { block -> block() }
        val texts = TextStore(dao, writer)
        val shapes = ShapeStore(dao, writer)
        val stickies = StickyStore(dao, writer) { block -> block() }

        texts.create("page", text("t-loose"))
        texts.create("page", text("t-wrapped"))
        shapes.create("page", shape("sh-loose"))
        shapes.create("page", shape("sh-wrapped"))
        stickies.create("page", note("n-loose"))
        stickies.create("page", note("n-wrapped"))
        writer.drain()
        stickies.setContent("n-wrapped", listOf(stroke("c1")))
        writer.drain()
        links.create(
            "page",
            PageLink(
                id = "l1", payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
                x = 0f, y = 0f, width = 120f, height = 60f, order = 0,
                strokes = emptyList(), headings = emptyList(),
                texts = listOf(text("t-wrapped")),
                shapes = listOf(shape("sh-wrapped")),
                stickies = listOf(note("n-wrapped")),
            ),
        )
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(listOf("t-loose"), content.texts.map { it.id })
        assertEquals(listOf("sh-loose"), content.shapes.map { it.id })
        assertEquals(listOf("n-loose"), content.stickies.map { it.id })
        val l = content.links.single()
        assertEquals(listOf("t-wrapped"), l.texts.map { it.id })
        assertEquals(listOf("sh-wrapped"), l.shapes.map { it.id })
        assertEquals(listOf("n-wrapped"), l.stickies.map { it.id })
        // A note's content is never in a drawing read, loose or wrapped.
        assertTrue(l.stickies.single().strokes.isEmpty())
        assertTrue(content.strokes.isEmpty())
        // The shape round-tripped through its packed flags, rotation and all.
        assertEquals(30f, l.shapes.single().rotationDeg, 0.01f)
        assertEquals(ShapeType.ELLIPSE, l.shapes.single().type)
        writer.close()
    }

    @Test
    fun `a soft-deleted object of any new kind never previews`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val texts = TextStore(dao, writer)
        val shapes = ShapeStore(dao, writer)
        val stickies = StickyStore(dao, writer) { block -> block() }
        texts.create("page", text("t1"))
        shapes.create("page", shape("sh1"))
        stickies.create("page", note("n1"))
        writer.drain()
        texts.erase(listOf("t1"))
        shapes.erase(listOf("sh1"))
        stickies.remove(listOf("n1"))
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertTrue(content.texts.isEmpty())
        assertTrue(content.shapes.isEmpty())
        assertTrue(content.stickies.isEmpty())
        writer.close()
    }

    /**
     * Arc 34 / L16: the read went from six queries per level to one, split by type in Kotlin. What
     * it answers must be exactly what the six per-type reads answer, in exactly their order — a
     * filter never reorders what it keeps.
     */
    @Test
    fun `the one-query read answers what the six per-type reads answer`() = runBlocking {
        val dao = FakeSoilDao()
        val writer = SoilWriter {}
        val strokes = StrokeStore(dao, writer)
        val headings = HeadingStore(dao, writer)
        val texts = TextStore(dao, writer)
        val shapes = ShapeStore(dao, writer)
        val stickies = StickyStore(dao, writer) { block -> block() }
        val links = LinkStore(dao, writer) { block -> block() }

        // Interleaved on purpose: the kinds share one `order` sequence on the page.
        strokes.commit("page", stroke("s1"))
        texts.create("page", text("t1"))
        strokes.commit("page", stroke("s2"))
        headings.create("page", heading("h1"))
        shapes.create("page", shape("sh1"))
        stickies.create("page", note("n1"))
        writer.drain()
        links.create(
            "page",
            PageLink(
                id = "l1", payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
                x = 0f, y = 0f, width = 120f, height = 60f, order = 9,
                strokes = listOf(stroke("s3")), headings = listOf(heading("h2")),
                texts = listOf(text("t2")), shapes = listOf(shape("sh2")), stickies = listOf(note("n2")),
            ),
        )
        writer.drain()

        val content = PageReads.content(dao, "page")
        assertEquals(dao.childrenOfType("page", SoilSchema.TYPE_STROKE).map { it.id }, content.strokes.map { it.id })
        assertEquals(dao.childrenOfType("page", SoilSchema.TYPE_HEADING).map { it.id }, content.headings.map { it.id })
        assertEquals(dao.childrenOfType("page", SoilSchema.TYPE_TEXT).map { it.id }, content.texts.map { it.id })
        assertEquals(dao.childrenOfType("page", SoilSchema.TYPE_SHAPE).map { it.id }, content.shapes.map { it.id })
        assertEquals(dao.stickiesOf("page").map { it.id }, content.stickies.map { it.id })
        assertEquals(dao.linksOf("page").map { it.id }, content.links.map { it.id })

        val link = content.links.single()
        assertEquals(dao.childrenOfType("l1", SoilSchema.TYPE_STROKE).map { it.id }, link.strokes.map { it.id })
        assertEquals(dao.childrenOfType("l1", SoilSchema.TYPE_HEADING).map { it.id }, link.headings.map { it.id })
        assertEquals(dao.childrenOfType("l1", SoilSchema.TYPE_TEXT).map { it.id }, link.texts.map { it.id })
        assertEquals(dao.childrenOfType("l1", SoilSchema.TYPE_SHAPE).map { it.id }, link.shapes.map { it.id })
        assertEquals(dao.childrenOfType("l1", SoilSchema.TYPE_STICKY).map { it.id }, link.stickies.map { it.id })
        writer.close()
    }

    @Test
    fun `pages maps live page rows in order with their authored size`() = runBlocking {
        val dao = FakeSoilDao()
        val now = 1L
        dao.upsert(SoilObjectEntity(
            id = "p2", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 1,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f,
        ))
        dao.upsert(SoilObjectEntity(
            id = "p1", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 0,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f,
        ))
        dao.upsert(SoilObjectEntity(
            id = "p3", parentId = "nb", type = SoilSchema.TYPE_PAGE, order = 2,
            createdAt = now, updatedAt = now, width = 1404f, height = 1872f, deletedAt = now,
        ))

        val pages = PageReads.pages(dao, "nb")
        assertEquals(listOf("p1", "p2"), pages.map { it.id })
        assertEquals(1404, pages[0].width)
        assertEquals(1872, pages[0].height)
    }
}
