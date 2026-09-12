package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The sticky editor's in-memory page (arc 28 / H5): the note's content strokes in the note's
 * **local** space, in writing order, with the four things a showing can do to them and the undo
 * replay for each. Pure — JVM-tested; the Activity holds one and mirrors every g-paper callback
 * into it, then hands [strokes] to the host's sink on the debounce.
 *
 * Why not the calendar's `InkDocument`: that one lives in `:ext-ink` (which `:app` does not and
 * must not depend on) and speaks a store's op log; this note's writes go through the host's
 * [StickyStore.setContent] as **whole sets**, so the document needs no statements — only the
 * current list and enough history to replay it. Order is load-bearing: a stroke's index is what
 * `setContent` writes as its `"order"`, and a revived stroke goes back **where it was**, not at
 * the end (an undone erase must not shuffle the note).
 */
class StickyInk {

    /** One reversible act inside a showing. */
    sealed interface Action {
        data class Drew(val stroke: Stroke) : Action
        /** [strokes] with the index each one sat at, so a revert puts them back in place. */
        data class Erased(val strokes: List<IndexedValue<Stroke>>) : Action
        data class Moved(val ids: Set<String>, val dx: Float, val dy: Float) : Action
        /** A clipboard paste — new strokes, appended. */
        data class Pasted(val strokes: List<Stroke>) : Action
    }

    private val list = ArrayList<Stroke>()

    /** The content as it stands, in writing order. A fresh list every call. */
    val strokes: List<Stroke> get() = ArrayList(list)

    val isEmpty: Boolean get() = list.isEmpty()

    /** Start over with [initial] — the row set the editor opened on. */
    fun reset(initial: List<Stroke>) {
        list.clear()
        list.addAll(initial)
    }

    // ── The four acts (each answers the action to record, or null for a no-op) ──

    fun add(stroke: Stroke): Action.Drew {
        list.add(stroke)
        return Action.Drew(stroke)
    }

    fun erase(ids: Collection<String>): Action.Erased? {
        val gone = ArrayList<IndexedValue<Stroke>>()
        val idSet = ids.toHashSet()
        for ((i, s) in list.withIndex()) if (s.id in idSet) gone += IndexedValue(i, s)
        if (gone.isEmpty()) return null
        list.removeAll { it.id in idSet }
        return Action.Erased(gone)
    }

    fun move(ids: Collection<String>, dx: Float, dy: Float): Action.Moved? {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return null
        val idSet = ids.toHashSet()
        var any = false
        for (i in list.indices) {
            if (list[i].id !in idSet) continue
            list[i] = list[i].translated(dx, dy)
            any = true
        }
        return if (any) Action.Moved(idSet, dx, dy) else null
    }

    fun paste(strokes: List<Stroke>): Action.Pasted? {
        if (strokes.isEmpty()) return null
        list.addAll(strokes)
        return Action.Pasted(strokes)
    }

    // ── Replay ───────────────────────────────────────────────────────────────

    fun revert(a: Action) {
        when (a) {
            is Action.Drew -> list.removeAll { it.id == a.stroke.id }
            is Action.Erased -> for (iv in a.strokes) list.add(iv.index.coerceIn(0, list.size), iv.value)
            is Action.Moved -> move(a.ids, -a.dx, -a.dy)
            is Action.Pasted -> { val ids = a.strokes.mapTo(HashSet()) { it.id }; list.removeAll { it.id in ids } }
        }
    }

    fun reapply(a: Action) {
        when (a) {
            is Action.Drew -> list.add(a.stroke)
            is Action.Erased -> { val ids = a.strokes.mapTo(HashSet()) { it.value.id }; list.removeAll { it.id in ids } }
            is Action.Moved -> move(a.ids, a.dx, a.dy)
            is Action.Pasted -> list.addAll(a.strokes)
        }
    }
}
