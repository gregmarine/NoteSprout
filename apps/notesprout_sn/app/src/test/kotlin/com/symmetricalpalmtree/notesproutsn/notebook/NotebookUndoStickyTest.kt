package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sticky-only entries (arc 28 / H5) in **both** directions.
 *
 * [Action.StickyInserted] is the one the existing suite pins only by its page: its undo is
 * `StickyStore.remove` and its redo `StickyStore.restore`, and a restore of a row the undo may have
 * left *absent* re-inserts from this snapshot — so the snapshot has to be the whole icon row,
 * content size and all. A note that came back 0 × 0 would open at the editor's own paper size and
 * silently stop being the note that was authored.
 *
 * [Action.StickyContentEdited] replays as `setContent(stickyId, side)` in either direction, which
 * makes each side the note's *whole* content — so an empty side is a real answer (the user cleared
 * the note), not a missing one.
 */
class NotebookUndoStickyTest {

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)))

    private fun sticky(id: String, strokes: List<Stroke> = emptyList()) = PageSticky(
        id = id, x = 30f, y = 40f, width = 72f, height = 72f,
        contentW = 1404, contentH = 1700, order = 3, strokes = strokes,
    )

    // ── StickyInserted ───────────────────────────────────────────────────────

    @Test
    fun `an insert carries the whole icon row, so a redo can re-create it`() {
        val note = sticky("k1")
        val entry = Action.StickyInserted("p", note)
        assertEquals("k1", entry.sticky.id)
        assertEquals(30f, entry.sticky.x, 0f)
        assertEquals(40f, entry.sticky.y, 0f)
        assertEquals(72f, entry.sticky.width, 0f)
        assertEquals(1404, entry.sticky.contentW)
        assertEquals(1700, entry.sticky.contentH)
        assertEquals(3, entry.sticky.order)
    }

    @Test
    fun `an insert's note is empty — the editor has not run yet`() {
        val entry = Action.StickyInserted("p", sticky("k1"))
        assertTrue(entry.sticky.strokes.isEmpty())
        assertEquals(emptyList<String>(), entry.sticky.childIds)
    }

    @Test
    fun `an insert rides the stack in both directions and stays itself`() {
        val stack = UndoRedoStack<Action>()
        val inserted: Action = Action.StickyInserted("p", sticky("k1"))
        stack.record(inserted)

        val undone = stack.popUndo()!!
        assertSame(inserted, undone)
        stack.pushRedo(undone)
        assertSame(inserted, stack.popRedo())
    }

    @Test
    fun `an insert is tellable apart from the content edit that follows it`() {
        val inserted: Action = Action.StickyInserted("p", sticky("k1"))
        val edited: Action = Action.StickyContentEdited("p", "k1", emptyList(), listOf(stroke("a")))
        assertNotEquals(inserted::class, edited::class)
        assertTrue(inserted is Action.StickyInserted)
        assertTrue(edited is Action.StickyContentEdited)
    }

    // ── StickyContentEdited ──────────────────────────────────────────────────

    @Test
    fun `an edit's two sides are whole notes, and either can be empty`() {
        val cleared = Action.StickyContentEdited("p", "k1", listOf(stroke("a"), stroke("b")), emptyList())
        assertEquals(listOf("a", "b"), cleared.before.map { it.id })
        assertEquals(emptyList<Stroke>(), cleared.after)

        val firstInk = Action.StickyContentEdited("p", "k1", emptyList(), listOf(stroke("a")))
        assertEquals(emptyList<Stroke>(), firstInk.before)
        assertEquals(listOf("a"), firstInk.after.map { it.id })
    }

    @Test
    fun `an edit is symmetric — undo and redo are the same call with the other side`() {
        val before = listOf(stroke("a"))
        val after = listOf(stroke("a"), stroke("b"))
        val entry = Action.StickyContentEdited("p", "k1", before, after)

        // What the two replay directions hand StickyStore.setContent, and nothing else.
        assertEquals(before, entry.before)
        assertEquals(after, entry.after)
        assertEquals("k1", entry.stickyId)
        assertEquals("p", entry.pageId)
    }

    @Test
    fun `an edit rides the stack in both directions`() {
        val stack = UndoRedoStack<Action>()
        val edited: Action = Action.StickyContentEdited("p", "k1", emptyList(), listOf(stroke("a")))
        stack.record(edited)

        val undone = stack.popUndo()!!
        assertSame(edited, undone)
        stack.pushRedo(undone)
        assertSame(edited, stack.popRedo())
    }
}
