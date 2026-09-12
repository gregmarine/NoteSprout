package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The notebook ⇄ sticky-editor hand-off (arc 28 / H5, D2). It is a process-local singleton, so the
 * discipline under test is *who owns which field when*: a showing is taken **once** (a stale one
 * must never be picked up by a later editor), the output belongs to the editor's exit, and every
 * staging starts a clean showing rather than inheriting the last one's parting word.
 */
class StickyEditorTransferTest {

    private class FakeSink : StickyEditorTransfer.Sink {
        val written = mutableListOf<List<Stroke>>()
        var drains = 0
        override fun setContent(strokes: List<Stroke>) { written += strokes }
        override suspend fun drain() { drains++ }
    }

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(0f, 0f), StrokePoint(5f, 5f)))

    private fun showing(stickyId: String = "k1", initial: List<Stroke> = emptyList()) =
        StickyEditorTransfer.Showing(
            notebookId = "nb", pageId = "p", stickyId = stickyId,
            contentW = 1404, contentH = 1700, initial = initial, sink = FakeSink(),
        )

    /** A global object outlives a test method — every one of these starts and ends empty. */
    @Before fun reset() = StickyEditorTransfer.clear()

    @After fun tidy() = StickyEditorTransfer.clear()

    @Test
    fun `nothing is staged until the notebook stages it`() {
        assertNull(StickyEditorTransfer.take())
        assertNull(StickyEditorTransfer.current)
        assertNull(StickyEditorTransfer.output)
    }

    @Test
    fun `the editor takes the showing exactly once`() {
        val s = showing()
        StickyEditorTransfer.stage(s)

        assertSame(s, StickyEditorTransfer.take())
        assertNull("a second editor must not pick up a spent showing", StickyEditorTransfer.take())
    }

    @Test
    fun `current survives the take — the result callback still needs it`() {
        val s = showing(initial = listOf(stroke("a")))
        StickyEditorTransfer.stage(s)
        StickyEditorTransfer.take()

        assertSame(s, StickyEditorTransfer.current)
        assertEquals(listOf("a"), StickyEditorTransfer.current!!.initial.map { it.id })
    }

    @Test
    fun `leave is the editor's parting word and the undo entry's after side`() {
        StickyEditorTransfer.stage(showing(initial = listOf(stroke("a"))))
        StickyEditorTransfer.take()
        assertNull(StickyEditorTransfer.output)

        StickyEditorTransfer.leave(listOf(stroke("a"), stroke("b")))
        assertEquals(listOf("a", "b"), StickyEditorTransfer.output!!.map { it.id })
    }

    @Test
    fun `an empty note is a real answer, not a missing one`() {
        StickyEditorTransfer.stage(showing(initial = listOf(stroke("a"))))
        StickyEditorTransfer.leave(emptyList())
        assertEquals(emptyList<Stroke>(), StickyEditorTransfer.output)
    }

    @Test
    fun `clear empties all three — the notebook's last act in the result callback`() {
        StickyEditorTransfer.stage(showing())
        StickyEditorTransfer.leave(listOf(stroke("a")))

        StickyEditorTransfer.clear()
        assertNull(StickyEditorTransfer.take())
        assertNull(StickyEditorTransfer.current)
        assertNull(StickyEditorTransfer.output)
    }

    @Test
    fun `staging again starts clean — the second note never inherits the first's output`() {
        StickyEditorTransfer.stage(showing("k1"))
        StickyEditorTransfer.leave(listOf(stroke("a")))

        val second = showing("k2")
        StickyEditorTransfer.stage(second)
        assertNull(StickyEditorTransfer.output)
        assertSame(second, StickyEditorTransfer.current)
        assertSame(second, StickyEditorTransfer.take())
    }

    @Test
    fun `the sink is what the editor writes through — the notebook's one writer, never its own`() {
        val sink = FakeSink()
        val s = StickyEditorTransfer.Showing("nb", "p", "k1", 800, 600, emptyList(), sink)
        StickyEditorTransfer.stage(s)

        StickyEditorTransfer.take()!!.sink.setContent(listOf(stroke("a")))
        assertEquals(1, sink.written.size)
        assertEquals(listOf("a"), sink.written.single().map { it.id })
    }
}
