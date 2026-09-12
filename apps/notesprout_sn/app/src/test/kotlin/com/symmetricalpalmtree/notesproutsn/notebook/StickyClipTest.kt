package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky editor's two clipboard rules (arc 28 / H5): the rows a note **copies**, and which of
 * the clipboard's rows a note is allowed to **paste**. Both are pure, and both can corrupt a note
 * silently if they get the coordinate space wrong — a page-space stroke mixed in with a note's
 * local-space content lands hundreds of px from where the user pointed.
 */
class StickyClipTest {

    private val notebookId = "nb-src"
    private val srcPage = "page-1"
    private val stickyId = "note-1"
    private val now = 7_000L

    private fun stroke(id: String, x: Float, y: Float, width: Float = 4f) = Stroke(
        id = id,
        points = listOf(
            StrokePoint(x, y, pressure = 0.5f, tilt = 0.1f),
            StrokePoint(x + 10f, y + 20f, pressure = 1f, tilt = 0f),
        ),
        color = 0xFF000000.toInt(), width = width, style = StrokeStyle.PEN,
    )

    private fun headingRow(id: String, parentId: String, order: Int) = HeadingRows.toRow(
        Heading(id = id, text = "## Title", level = 2, x = 10f, y = 10f, width = 120f, height = 40f, order = order),
        parentId, now,
    )

    private fun stickyRow(id: String, parentId: String, order: Int) = StickyRows.toRow(
        PageSticky(id = id, x = 500f, y = 100f, width = 72f, height = 72f, contentW = 1404, contentH = 1800, order = order),
        parentId, now,
    )

    private fun envelope(top: List<SoilObjectEntity>, children: List<SoilObjectEntity> = emptyList()) =
        ObjectClip.capture(top, children, notebookId, now)!!

    private fun ids(): () -> String {
        var n = 0
        return { "fresh-${n++}" }
    }

    // ── rowsFor — what a note's copy carries ─────────────────────────────────

    @Test
    fun `rowsFor writes the note's content rows, parented to the note, order = index`() {
        val strokes = listOf(stroke("s1", 10f, 10f), stroke("s2", 40f, 60f))
        val rows = StickyClip.rowsFor(strokes, stickyId, now)

        assertEquals(2, rows.size)
        for ((i, row) in rows.withIndex()) {
            assertEquals(SoilSchema.TYPE_STROKE, row.type)
            assertEquals(stickyId, row.parentId)
            assertEquals(i, row.order)
        }
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
    }

    @Test
    fun `a note's copy round-trips through the envelope with its points intact`() {
        val strokes = listOf(stroke("s1", 10f, 10f), stroke("s2", 40f, 60f))
        val env = envelope(StickyClip.rowsFor(strokes, stickyId, now))

        val back = StickyClip.extract(env, ids())!!
        assertEquals(strokes.map { it.points }, back.strokes.map { it.points })
        assertFalse("nothing but ink was on the clipboard", back.leftOut)
    }

    @Test
    fun `rowsFor decodes straight back with StrokeRows`() {
        val one = stroke("s1", 10f, 10f, width = 2.5f)
        val row = StickyClip.rowsFor(listOf(one), stickyId, now).single()
        val decoded = StrokeRows.toStroke(row)!!
        assertEquals(one.points, decoded.points)
        assertEquals(one.width, decoded.width, 0f)
        assertEquals(one.style, decoded.style)
    }

    // ── extract — what a note is allowed to paste ────────────────────────────

    @Test
    fun `a page selection pastes its ink only, with fresh ids, and says what was left out`() {
        val env = envelope(
            listOf(
                StrokeRows.toRow(stroke("s-a", 100f, 100f), srcPage, 0, now),
                StrokeRows.toRow(stroke("s-b", 140f, 100f), srcPage, 1, now),
                headingRow("h-1", srcPage, 2),
            ),
        )

        val extracted = StickyClip.extract(env, ids())!!
        assertEquals(2, extracted.strokes.size)
        assertTrue("the heading could not come in", extracted.leftOut)
        for (s in extracted.strokes) assertNotEquals("s-a", s.id)
        for (s in extracted.strokes) assertNotEquals("s-b", s.id)
        assertEquals(2, extracted.strokes.map { it.id }.toSet().size)
    }

    @Test
    fun `ink alone leaves nothing out`() {
        val env = envelope(
            listOf(
                StrokeRows.toRow(stroke("s-a", 100f, 100f), srcPage, 0, now),
                StrokeRows.toRow(stroke("s-b", 140f, 100f), srcPage, 1, now),
            ),
        )
        val extracted = StickyClip.extract(env, ids())!!
        assertEquals(2, extracted.strokes.size)
        assertFalse(extracted.leftOut)
    }

    @Test
    fun `page ink wins over a copied note's content — two spaces are never mixed`() {
        // A lasso that caught a sticky and a loose stroke. The loose one is page space; the note's
        // child is the note's own local space, and there is no shift that could reconcile them.
        val env = envelope(
            listOf(
                stickyRow("n-1", srcPage, 0),
                StrokeRows.toRow(stroke("s-loose", 200f, 200f), srcPage, 1, now),
            ),
            listOf(StrokeRows.toRow(stroke("n-ink", 5f, 5f), "n-1", 0, now)),
        )

        val extracted = StickyClip.extract(env, ids())!!
        assertEquals(1, extracted.strokes.size)
        assertEquals(200f, extracted.strokes.single().points[0].x, 0f)
        assertTrue(extracted.leftOut)
    }

    @Test
    fun `a copied note alone pastes its own content`() {
        val env = envelope(
            listOf(stickyRow("n-1", srcPage, 0)),
            listOf(
                StrokeRows.toRow(stroke("n-ink-1", 5f, 5f), "n-1", 0, now),
                StrokeRows.toRow(stroke("n-ink-2", 30f, 5f), "n-1", 1, now),
            ),
        )

        val extracted = StickyClip.extract(env, ids())!!
        assertEquals(2, extracted.strokes.size)
        assertEquals(5f, extracted.strokes[0].points[0].x, 0f)
        assertEquals(30f, extracted.strokes[1].points[0].x, 0f)
        assertFalse("every stroke came in — the icon alone is not a loss", extracted.leftOut)
    }

    @Test
    fun `a whole-page payload is not something a note can paste at all`() {
        val objects = envelope(listOf(StrokeRows.toRow(stroke("s-a", 10f, 10f), srcPage, 0, now)))
        val page = objects.copy(kind = ClipEnvelope.KIND_PAGE)
        assertNull(StickyClip.extract(page, ids()))
    }

    // ── bounds + placement ──────────────────────────────────────────────────

    @Test
    fun `bounds is the ink extent — point bounds grown by half a stroke width`() {
        val one = stroke("s1", 100f, 200f, width = 8f)
        val extracted = StickyClip.Extracted(listOf(one), leftOut = false)
        val b = extracted.bounds!!
        assertEquals(100f - 4f, b.left, 0.001f)
        assertEquals(200f - 4f, b.top, 0.001f)
        assertEquals(110f + 4f, b.right, 0.001f)
        assertEquals(220f + 4f, b.bottom, 0.001f)
    }

    @Test
    fun `bounds unions every stroke and is null with no ink`() {
        val extracted = StickyClip.Extracted(
            listOf(stroke("s1", 0f, 0f, width = 2f), stroke("s2", 100f, 100f, width = 2f)),
            leftOut = false,
        )
        assertEquals(Bounds(-1f, -1f, 111f, 121f), extracted.bounds)
        assertNull(StickyClip.Extracted(emptyList(), leftOut = true).bounds)
    }

    @Test
    fun `placed centres the ink extent on the tap`() {
        val extracted = StickyClip.Extracted(listOf(stroke("s1", 0f, 0f, width = 4f)), leftOut = false)
        val expected = ObjectPlacement.centredOn(extracted.bounds!!, 400f, 500f, 1000f, 1200f)

        val placed = StickyClip.placed(extracted, 400f, 500f, 1000f, 1200f)
        assertEquals(1, placed.size)
        assertEquals(expected.dx, placed.single().points[0].x, 0.001f)
        assertEquals(expected.dy, placed.single().points[0].y, 0.001f)
    }

    @Test
    fun `placed clamps the ink inside the note`() {
        val extracted = StickyClip.Extracted(listOf(stroke("s1", 0f, 0f, width = 4f)), leftOut = false)
        // A tap in the far corner of a small note: the box has to be pulled back inside.
        val expected = ObjectPlacement.centredOn(extracted.bounds!!, 199f, 199f, 200f, 200f)

        val placed = StickyClip.placed(extracted, 199f, 199f, 200f, 200f)
        val b = placed.single().bounds.inflated(placed.single().width / 2f)
        assertEquals(expected.dx, placed.single().points[0].x, 0.001f)
        assertTrue("$b hangs off the note", b.right <= 200f && b.bottom <= 200f)
    }

    @Test
    fun `placing nothing places nothing`() {
        assertEquals(
            emptyList<Stroke>(),
            StickyClip.placed(StickyClip.Extracted(emptyList(), leftOut = true), 10f, 10f, 100f, 100f),
        )
    }
}
