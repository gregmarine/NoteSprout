package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky editor's in-memory note (arc 28 / H5): writing order, the four acts, and the replay of
 * each one in both directions. Order is the thing under test — a stroke's index becomes its
 * `"order"` column in [StickyStore.setContent], so an undone erase that put its strokes back at the
 * *end* would silently re-sequence the note on the next debounced write.
 */
class StickyInkTest {

    private fun stroke(id: String, x: Float = 0f) = Stroke(
        id = id,
        points = listOf(StrokePoint(x, x), StrokePoint(x + 5f, x + 5f)),
    )

    private fun ids(ink: StickyInk) = ink.strokes.map { it.id }

    private fun inkOf(vararg s: Stroke) = StickyInk().apply { for (one in s) add(one) }

    // ── Writing order ────────────────────────────────────────────────────────

    @Test
    fun `strokes come back in writing order`() {
        val ink = inkOf(stroke("a"), stroke("b"), stroke("c"))
        assertEquals(listOf("a", "b", "c"), ids(ink))
        assertFalse(ink.isEmpty)
    }

    @Test
    fun `a fresh note is empty`() {
        assertTrue(StickyInk().isEmpty)
        assertEquals(emptyList<String>(), ids(StickyInk()))
    }

    @Test
    fun `strokes is a defensive copy — the caller cannot reach the note's list`() {
        val ink = inkOf(stroke("a"))
        val first = ink.strokes
        val second = ink.strokes
        assertNotSame(first, second)
        // A snapshot taken before a later act does not grow with the note — which is what makes it
        // safe to hand [StickyInk.strokes] to the host's sink and to the undo entry.
        ink.add(stroke("b"))
        assertEquals(listOf("a"), first.map { it.id })
        assertEquals(listOf("a", "b"), ids(ink))
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Test
    fun `a draw reverts by id and reapplies at the end`() {
        val ink = inkOf(stroke("a"))
        val drew = ink.add(stroke("b"))
        assertEquals(listOf("a", "b"), ids(ink))

        ink.revert(drew)
        assertEquals(listOf("a"), ids(ink))
        ink.reapply(drew)
        assertEquals(listOf("a", "b"), ids(ink))
    }

    // ── Erase ────────────────────────────────────────────────────────────────

    @Test
    fun `an erase reports the index every stroke sat at`() {
        val ink = inkOf(stroke("a"), stroke("b"), stroke("c"), stroke("d"))
        val erased = ink.erase(listOf("b", "d"))!!
        assertEquals(listOf(1, 3), erased.strokes.map { it.index })
        assertEquals(listOf("b", "d"), erased.strokes.map { it.value.id })
        assertEquals(listOf("a", "c"), ids(ink))
    }

    @Test
    fun `an undone erase puts the strokes back where they were, not at the end`() {
        val ink = inkOf(stroke("a"), stroke("b"), stroke("c"), stroke("d"))
        val erased = ink.erase(listOf("b", "d"))!!

        ink.revert(erased)
        assertEquals(listOf("a", "b", "c", "d"), ids(ink))

        ink.reapply(erased)
        assertEquals(listOf("a", "c"), ids(ink))
    }

    @Test
    fun `the first stroke comes back first, too`() {
        val ink = inkOf(stroke("a"), stroke("b"))
        val erased = ink.erase(listOf("a"))!!
        assertEquals(listOf("b"), ids(ink))
        ink.revert(erased)
        assertEquals(listOf("a", "b"), ids(ink))
    }

    @Test
    fun `an erase that hits nothing is not an action`() {
        val ink = inkOf(stroke("a"))
        assertNull(ink.erase(listOf("nobody")))
        assertNull(ink.erase(emptyList()))
        assertEquals(listOf("a"), ids(ink))
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    @Test
    fun `a move shifts only the named strokes`() {
        val ink = inkOf(stroke("a", x = 0f), stroke("b", x = 100f))
        val moved = ink.move(listOf("a"), 10f, -5f)!!
        assertEquals(setOf("a"), moved.ids)

        val after = ink.strokes.associateBy { it.id }
        assertEquals(10f, after["a"]!!.points[0].x, 0f)
        assertEquals(-5f, after["a"]!!.points[0].y, 0f)
        assertEquals(100f, after["b"]!!.points[0].x, 0f)
    }

    @Test
    fun `a move reverts exactly and reapplies exactly`() {
        val ink = inkOf(stroke("a", x = 7f), stroke("b", x = 100f))
        val before = ink.strokes
        val moved = ink.move(listOf("a", "b"), 12.5f, -3.25f)!!

        ink.revert(moved)
        assertEquals(before.map { it.points }, ink.strokes.map { it.points })

        ink.reapply(moved)
        assertEquals(19.5f, ink.strokes.first { it.id == "a" }.points[0].x, 0f)
    }

    @Test
    fun `a move keeps the note's order — it is not a rewrite`() {
        val ink = inkOf(stroke("a"), stroke("b"), stroke("c"))
        ink.move(listOf("b"), 5f, 5f)
        assertEquals(listOf("a", "b", "c"), ids(ink))
    }

    @Test
    fun `a no-op move is not an action`() {
        val ink = inkOf(stroke("a"))
        assertNull("zero delta", ink.move(listOf("a"), 0f, 0f))
        assertNull("no ids", ink.move(emptyList(), 5f, 5f))
        assertNull("ids nothing in the note answers to", ink.move(listOf("ghost"), 5f, 5f))
    }

    // ── Paste ────────────────────────────────────────────────────────────────

    @Test
    fun `a paste appends and reverts by exactly the ids it added`() {
        val ink = inkOf(stroke("a"))
        val pasted = ink.paste(listOf(stroke("p1"), stroke("p2")))!!
        assertEquals(listOf("a", "p1", "p2"), ids(ink))

        ink.revert(pasted)
        assertEquals(listOf("a"), ids(ink))

        ink.reapply(pasted)
        assertEquals(listOf("a", "p1", "p2"), ids(ink))
    }

    @Test
    fun `an empty paste is not an action`() {
        val ink = inkOf(stroke("a"))
        assertNull(ink.paste(emptyList()))
        assertEquals(listOf("a"), ids(ink))
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    @Test
    fun `reset replaces the whole note`() {
        val ink = inkOf(stroke("a"), stroke("b"))
        ink.reset(listOf(stroke("x"), stroke("y"), stroke("z")))
        assertEquals(listOf("x", "y", "z"), ids(ink))

        ink.reset(emptyList())
        assertTrue(ink.isEmpty)
        assertEquals(emptyList<String>(), ids(ink))
    }

    @Test
    fun `reset copies the list it is handed — a later add cannot reach the caller's`() {
        val ink = StickyInk()
        val initial = mutableListOf(stroke("a"))
        ink.reset(initial)
        ink.add(stroke("b"))
        assertEquals(listOf("a"), initial.map { it.id })
        assertEquals(listOf("a", "b"), ids(ink))
    }
}
