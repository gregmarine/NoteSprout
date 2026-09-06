package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook's own action set (arc 11 / J1 — the ordering rules moved to `:sn-screen` with the
 * now-generic `UndoRedoStack<A>`; what stays pinned here is the *shape* of the kinds the
 * notebook records, since the replay in [NotebookActivity] switches on exactly these).
 */
class NotebookUndoTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)),
    )

    private fun text(id: String, source: String = "hello") =
        PageText(id = id, text = source, x = 10f, y = 20f, width = 100f, height = 40f, order = 0)

    private fun shape(id: String, rotation: Float = 0f) = PageShape(
        id = id, type = ShapeType.STAR, cx = 50f, cy = 60f, width = 72f, height = 72f,
        strokeWidth = 3f, rotationDeg = rotation, aspectLocked = true, pointCount = 5, order = 0,
    )

    private fun sticky(id: String, strokes: List<Stroke> = emptyList()) = PageSticky(
        id = id, x = 10f, y = 10f, width = 72f, height = 72f,
        contentW = 600, contentH = 800, order = 0, strokes = strokes,
    )

    @Test
    fun `a page action's pageId is where the op landed`() {
        val snap = NotebookSession.Structural(
            before = listOf("A", "B"),
            after = listOf("A", "N", "B"),
            objectIds = emptyList(),
            beforeCurrentId = "A",
            afterCurrentId = "N",
        )
        assertEquals("N", Action.Page(snap).pageId)
        // A paste replays through the same snapshot but runs the opposite direction — same rule
        // for where it landed.
        assertEquals("N", Action.PagePasted(snap).pageId)
    }

    @Test
    fun `every action kind reports its own page`() {
        val h = Heading("h", "# T", 1, 0f, 0f, 10f, 10f, 0)
        assertEquals("p1", Action.Drew("p1", stroke("a")).pageId)
        assertEquals("p2", Action.Erased("p2", listOf(stroke("a"))).pageId)
        assertEquals("p3", Action.Moved("p3", listOf("a"), 5f, -5f).pageId)
        assertEquals("p4", Action.Deleted("p4", listOf(stroke("a"))).pageId)
        assertEquals("p5", Action.HeadingCreated("p5", h, listOf("a")).pageId)
        assertEquals("p6", Action.HeadingDeleted("p6", listOf("h")).pageId)
        assertEquals("p7", Action.HeadingTextEdited("p7", h, h.copy(text = "# U")).pageId)
        assertEquals("p8", Action.HeadingLevelChanged("p8", h, h.copy(level = 2)).pageId)
    }

    /**
     * A lasso delete has to queue and pop exactly like anything else — and it must stay
     * *distinguishable* from an erase, which is the whole reason it is its own kind rather than a
     * reused [Action.Erased].
     */
    @Test
    fun `a lasso delete rides the stack like any other action`() {
        val s = UndoRedoStack<Action>()
        val erased = Action.Erased("p", listOf(stroke("a")))
        val deleted = Action.Deleted("p", listOf(stroke("b"), stroke("c")))
        s.record(erased)
        s.record(deleted)

        val first = s.popUndo()!!
        assertSame(deleted, first)
        assertTrue(first is Action.Deleted)
        s.pushRedo(first)
        assertSame(erased, s.popUndo())

        assertSame(deleted, s.popRedo())
        // Two carried strokes, both still there: a delete undo is only as good as its geometry.
        assertEquals(listOf("b", "c"), (deleted as Action.Deleted).strokes.map { it.id })
    }

    /**
     * A re-papering (arc 12) carries both template ids, and blank is one of them — `""` is the
     * format's answer for blank paper, so an entry that dropped it could not undo a page back to
     * blank.
     */
    @Test
    fun `a template change carries both ids, blank included`() {
        val s = UndoRedoStack<Action>()
        val toGrid = Action.TemplateChanged("p1", from = "", to = "t-grid")
        s.record(toGrid)

        val popped = s.popUndo()!!
        assertSame(toGrid, popped)
        assertEquals("p1", popped.pageId)
        assertEquals("", (popped as Action.TemplateChanged).from)
        assertEquals("t-grid", popped.to)
    }

    /**
     * A scribble (arc 14) can take ink, a heading and a link in **one** gesture, and the entry has
     * to carry all three or an undo would put back only part of what vanished. Its own kind, not a
     * reused [Action.Deleted] — a scribble is a different act to the user than a Delete tap.
     */
    @Test
    fun `a scribble erase carries all three kinds in one entry`() {
        val h = Heading("h1", "# T", 1, 0f, 0f, 10f, 10f, 0)
        val link = PageLink(
            id = "l1", payload = "p", chrome = 0,
            x = 0f, y = 0f, width = 20f, height = 20f, order = 0,
            strokes = listOf(stroke("wrapped")), headings = emptyList(),
        )
        val s = UndoRedoStack<Action>()
        val scribbled = Action.ScribbleErased(
            "p1", listOf(stroke("a"), stroke("b")), listOf(h.id), listOf(link),
        )
        s.record(scribbled)

        val popped = s.popUndo()!!
        assertSame(scribbled, popped)
        assertEquals("p1", popped.pageId)
        popped as Action.ScribbleErased
        // The strokes ride whole: an undo restores geometry, not just ids.
        assertEquals(listOf("a", "b"), popped.strokes.map { it.id })
        assertEquals(listOf("h1"), popped.headingIds)
        // The link rides as a full snapshot — restoring it has to bring its wrapped children back.
        assertEquals(listOf("wrapped"), popped.links.single().strokes.map { it.id })
    }

    /**
     * The kind matters as much as the payload: a scribble and a Delete tap replay identically but
     * must stay tellable apart, the same rule that keeps [Action.Erased] and [Action.Deleted]
     * separate. A `when` arm that folded them would lose the label a future undo hint needs.
     */
    @Test
    fun `a scribble erase is distinguishable from an erase and a delete`() {
        val strokes = listOf(stroke("a"))
        val erased: Action = Action.Erased("p", strokes)
        val deleted: Action = Action.Deleted("p", strokes)
        val scribbled: Action = Action.ScribbleErased("p", strokes)

        assertTrue(scribbled is Action.ScribbleErased)
        assertTrue(scribbled !is Action.Deleted)
        assertTrue(scribbled !is Action.Erased)
        assertTrue(erased !is Action.ScribbleErased)
        assertTrue(deleted !is Action.ScribbleErased)
    }

    // ── Arc 28: texts, shapes and sticky notes ──────────────────────────────

    /** Every new kind names the page it happened on, like every old one — history survives a
     *  page turn only because the entry knows where to go back to. */
    @Test
    fun `every arc-28 action kind reports its own page`() {
        val t = text("t1")
        val sh = shape("s1")
        val st = sticky("k1")
        assertEquals("p1", Action.TextCreated("p1", t).pageId)
        assertEquals("p2", Action.TextEdited("p2", t, t.copy(text = "bye")).pageId)
        assertEquals("p3", Action.ShapeInserted("p3", sh).pageId)
        assertEquals("p4", Action.ShapeTransformed("p4", sh, sh.copy(width = 90f)).pageId)
        assertEquals("p5", Action.StickyInserted("p5", st).pageId)
        assertEquals("p6", Action.StickyContentEdited("p6", "k1", emptyList(), emptyList()).pageId)
    }

    /**
     * One kind covers both ways a text object is born: an **insert** consumed no ink, so its
     * [Action.TextCreated.strokeIds] is empty and the replay's revive is a no-op; a **conversion**
     * carries the ids of the ink it replaced, which the undo has to bring back in place.
     */
    @Test
    fun `a text creation carries the ink it consumed, and an insert carries none`() {
        val inserted = Action.TextCreated("p", text("t1"))
        assertEquals(emptyList<String>(), inserted.strokeIds)

        val converted = Action.TextCreated("p", text("t2"), listOf("a", "b"))
        assertEquals(listOf("a", "b"), converted.strokeIds)
        // The text rides whole: an undo of the *edit* that follows needs the source and the box.
        assertEquals("hello", converted.text.text)
    }

    /** An edit and a transform both replay by writing one side over the row, so both sides have to
     *  be whole objects — an id pair could not put a rotation back. */
    @Test
    fun `an edit and a transform carry both whole sides`() {
        val before = text("t1", "one")
        val edited = Action.TextEdited("p", before, before.copy(text = "two", height = 80f))
        assertEquals("one", edited.before.text)
        assertEquals(80f, edited.after.height, 0f)

        val was = shape("s1", rotation = 0f)
        val transformed = Action.ShapeTransformed("p", was, was.copy(width = 120f, rotationDeg = 37f))
        assertEquals(0f, transformed.before.rotationDeg, 0f)
        assertEquals(37f, transformed.after.rotationDeg, 0f)
        assertEquals(120f, transformed.after.width, 0f)
    }

    /**
     * A sticky's delete snapshot has to carry its **content**: `StickyStore.restore` revives the
     * snapshot's `childIds`, so an icon with an empty stroke list would come back as an empty
     * note. Texts and shapes ride as ids for the heading's reason — their rows survive
     * soft-deleted with every column on them.
     */
    @Test
    fun `a delete carries texts and shapes by id and stickies whole`() {
        val note = sticky("k1", listOf(stroke("child-1"), stroke("child-2")))
        val deleted = Action.Deleted(
            "p", listOf(stroke("a")), listOf("h1"), emptyList(),
            textIds = listOf("t1"), shapeIds = listOf("s1"), stickies = listOf(note),
        )
        assertEquals(listOf("t1"), deleted.textIds)
        assertEquals(listOf("s1"), deleted.shapeIds)
        assertEquals(listOf("child-1", "child-2"), deleted.stickies.single().childIds)
    }

    /** A scribble can take the new kinds in the same gesture, and one gesture is still one entry. */
    @Test
    fun `a scribble erase carries the arc-28 kinds too`() {
        val scribbled = Action.ScribbleErased(
            "p", listOf(stroke("a")), emptyList(), emptyList(),
            textIds = listOf("t1"), shapeIds = listOf("s1"), stickies = listOf(sticky("k1")),
        )
        assertEquals(listOf("t1"), scribbled.textIds)
        assertEquals(listOf("s1"), scribbled.shapeIds)
        assertEquals("k1", scribbled.stickies.single().id)
    }

    /**
     * A move is the one act where a sticky rides as an **id**: nothing is created or destroyed, the
     * write is a delta on two columns, and a note's content does not move at all (it is local to
     * the note). There is no row to rebuild, so there is nothing for a snapshot to carry.
     */
    @Test
    fun `a move carries sticky ids, not snapshots`() {
        val moved = Action.Moved(
            "p", listOf("a"), 5f, -5f, listOf("h1"), listOf("l1"),
            textIds = listOf("t1"), shapeIds = listOf("s1"), stickyIds = listOf("k1"),
        )
        assertEquals(listOf("t1"), moved.textIds)
        assertEquals(listOf("s1"), moved.shapeIds)
        assertEquals(listOf("k1"), moved.stickyIds)
    }

    /** A paste runs the opposite direction but needs the same snapshots: its redo revives the
     *  note's children by id, and only the snapshot names them. */
    @Test
    fun `a paste carries the arc-28 kinds, stickies with their pasted content`() {
        val note = sticky("k1", listOf(stroke("child-1")))
        val pasted = Action.ObjectsPasted(
            "p", listOf("a"), listOf("h1"), emptyList(),
            textIds = listOf("t1"), shapeIds = listOf("s1"), stickies = listOf(note),
        )
        assertEquals(listOf("t1"), pasted.textIds)
        assertEquals(listOf("s1"), pasted.shapeIds)
        assertEquals(listOf("child-1"), pasted.stickies.single().childIds)
    }

    /**
     * Every widened field is a trailing default, so the arc-6 and arc-14 entries the rest of the
     * screen still records are unchanged — an act with no text, shape or sticky in it says so by
     * carrying three empty lists, and no call site had to be touched to keep meaning that.
     */
    @Test
    fun `the widened kinds default their arc-28 lists to empty`() {
        val deleted = Action.Deleted("p", listOf(stroke("a")))
        assertEquals(emptyList<String>(), deleted.textIds)
        assertEquals(emptyList<String>(), deleted.shapeIds)
        assertEquals(emptyList<PageSticky>(), deleted.stickies)

        val scribbled = Action.ScribbleErased("p", listOf(stroke("a")))
        assertEquals(emptyList<String>(), scribbled.textIds)
        assertEquals(emptyList<PageSticky>(), scribbled.stickies)

        val moved = Action.Moved("p", listOf("a"), 1f, 1f)
        assertEquals(emptyList<String>(), moved.textIds)
        assertEquals(emptyList<String>(), moved.stickyIds)

        val pasted = Action.ObjectsPasted("p", listOf("a"), listOf("h"), emptyList())
        assertEquals(emptyList<String>(), pasted.shapeIds)
        assertEquals(emptyList<PageSticky>(), pasted.stickies)
    }

    /**
     * One showing of the sticky editor is **one** entry, and both sides are whole local-space
     * stroke lists: the replay is `setContent(id, side)`, which makes the list the note's entire
     * content, so undo and redo are the same call with the other list.
     */
    @Test
    fun `a sticky content edit carries both whole sides`() {
        val before = listOf(stroke("a"))
        val after = listOf(stroke("a"), stroke("b"))
        val edited = Action.StickyContentEdited("p", "k1", before, after)
        assertEquals("k1", edited.stickyId)
        assertEquals(listOf("a"), edited.before.map { it.id })
        assertEquals(listOf("a", "b"), edited.after.map { it.id })
    }

    /** The new kinds ride the stack like every other, and stay tellable apart — the rule that
     *  keeps [Action.Erased], [Action.Deleted] and [Action.ScribbleErased] three kinds. */
    @Test
    fun `the arc-28 kinds ride the stack and stay distinguishable`() {
        val s = UndoRedoStack<Action>()
        val inserted: Action = Action.ShapeInserted("p", shape("s1"))
        val created: Action = Action.TextCreated("p", text("t1"))
        s.record(inserted)
        s.record(created)

        val first = s.popUndo()!!
        assertSame(created, first)
        s.pushRedo(first)
        val second = s.popUndo()!!
        assertSame(inserted, second)
        s.pushRedo(second)
        assertTrue(inserted is Action.ShapeInserted)
        assertTrue(inserted !is Action.TextCreated)
        // Redo is a stack: the entry pushed last is the one that comes back first.
        assertSame(inserted, s.popRedo())
        assertSame(created, s.popRedo())
    }

    /** Ink-only and content-only scribbles are both legal; the engine never reports two empties. */
    @Test
    fun `a scribble erase defaults its content lists to empty`() {
        val inkOnly = Action.ScribbleErased("p", listOf(stroke("a")))
        assertEquals(emptyList<String>(), inkOnly.headingIds)
        assertEquals(emptyList<PageLink>(), inkOnly.links)

        val contentOnly = Action.ScribbleErased("p", emptyList(), listOf("h1"))
        assertEquals(emptyList<Stroke>(), contentOnly.strokes)
        assertEquals(listOf("h1"), contentOnly.headingIds)
    }
}
