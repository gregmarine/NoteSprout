package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The hand-off between the notebook and the sticky editor (arc 28 / H5, D2) — og's
 * `persistToHost` as a process-local singleton, because the editor **opens no `.soil` of its own**:
 * the notebook's [NotebookSession] stays alive behind it, and every row the editor writes goes
 * through that session's one serial `SoilWriter` via the [Sink] the notebook binds here.
 *
 * Three fields, each owned by one side at one moment:
 *  - [Showing] is **staged by the notebook** immediately before the launch and **taken by the
 *    editor** in its `onCreate` — the sticky's ids, its content size, the child strokes as read
 *    (drained first — the arc's standing trap), and the sink. Nothing rides the Intent but the
 *    three ids, for the log.
 *  - [output] is **written by the editor** on its way out — the final child set — and **read by
 *    the notebook** in its result callback, which records the one
 *    [NotebookUndo.Action.StickyContentEdited] per showing from `showing.initial → output`.
 *  - [take] clears the staging so a stale showing can never be picked up by a later editor.
 *
 * **Process death** is the one failure: the editor comes back with nothing staged (the singleton
 * died with the process) and finishes at once; the notebook beneath is recreated behind
 * `IndexGuard`. What is lost is at most the debounce window — every earlier write is already a row.
 */
object StickyEditorTransfer {

    /** The host-side write door, bound to the notebook's [StickyStore]. */
    interface Sink {
        /** Make [strokes] the note's whole content (the debounced write; also the leave flush). */
        fun setContent(strokes: List<Stroke>)
        /** Wait for every write queued so far — the editor's exit flush awaits this. */
        suspend fun drain()
    }

    class Showing(
        val notebookId: String,
        val pageId: String,
        val stickyId: String,
        /** The note's page size in px, or 0 × 0 for an old / foreign row (the editor uses its own). */
        val contentW: Int,
        val contentH: Int,
        /** The content as read at launch, local space, writing order — the undo entry's `before`. */
        val initial: List<Stroke>,
        val sink: Sink,
    )

    @Volatile
    private var staged: Showing? = null

    /** The content the editor left — the undo entry's `after`. Null until the editor writes it. */
    @Volatile
    var output: List<Stroke>? = null
        private set

    /** The showing currently up, for the result callback. Null when none is. */
    @Volatile
    var current: Showing? = null
        private set

    /** The notebook, immediately before `launch`. */
    fun stage(showing: Showing) {
        staged = showing
        current = showing
        output = null
    }

    /** The editor's `onCreate`: the staged showing, exactly once. Null after a process death. */
    fun take(): Showing? {
        val s = staged
        staged = null
        return s
    }

    /** The editor, on every exit path, before `finish()`. */
    fun leave(strokes: List<Stroke>) {
        output = strokes
    }

    /** The notebook's result callback, once it has read what it needs. */
    fun clear() {
        staged = null
        current = null
        output = null
    }
}
