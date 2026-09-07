package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.SnClipboard
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipStore
import com.symmetricalpalmtree.notesproutsn.data.prefs.SnapPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityStickyEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The sticky note editor (arc 28 / H5, decisions 1–3, D2): a **core** screen with its own g-paper
 * surface — the second second-paper-surface in one process, after the calendar's event note (Z3)
 * — sharing the notebook's open `.soil` through [StickyEditorTransfer]. It opens no database, holds
 * no session, and is meaningless launched from anywhere but [NotebookActivity]'s result launcher.
 *
 * **What is on the glass.** One top bar (`[←] [pen] [eraser] [lasso]` + a centred "Sticky Note"
 * title — Back saves-and-closes; there is no cancel because every stroke is already a row, and no
 * ✓ because Back already does the one thing it would) over a paper the size
 * of the note's content ([StickyEditorTransfer.Showing.contentW] × `contentH`, laid top-left 1:1
 * — a foreign size is the notebook's foreign-page rule). The tools are the notebook's, fixed
 * (3 px pen, 15 px eraser, black); 2/3-finger undo/redo replay an in-memory [StickyInk]; the
 * lasso's bar is Snap · Copy · Cut · Delete; a pen tap on bare paper pastes the clipboard's ink.
 *
 * **Writes are whole sets, debounced.** Every act updates [ink] and schedules
 * [StickyEditorTransfer.Sink.setContent] with the whole list [DEBOUNCE_MS] later; a leave
 * ([onStop], every exit) flushes at once. The sink is an `enqueue` on the notebook's serial
 * writer, so a flush never suspends and the order of writes is the order of acts. The host records
 * **one** undo entry per showing from `initial → output` in its result callback; this screen's
 * own stack lives inside the showing only.
 *
 * **The EPD handoff chain** (Z3's rule): the notebook released the pipeline immediately before the
 * launch; this surface reclaims in [onResume], releases before **every** `finish()` ([exit]), and
 * the notebook reclaims at the top of its result callback (result callbacks run before its
 * `onResume`). A failure there is fixed in g-paper, never worked around here.
 *
 * **Process death** leaves nothing staged: [onCreate] finds no showing and finishes at once — the
 * notebook beneath is recreated behind `IndexGuard` and loses at most the debounce window.
 *
 * Frame silence: the selection bar's show at lasso completion / re-anchor after a move / over a
 * paste landing are the notebook's ledgered exceptions, applied here unchanged.
 */
class StickyEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickyEditorBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: PaperToolbar
    private lateinit var selectionBar: FloatingSelectionBar
    private lateinit var gestures: PageGestures
    private lateinit var snapPrefs: SnapPrefs
    private lateinit var showing: StickyEditorTransfer.Showing
    private val clipStore by lazy { ClipStore() }

    private val ink = StickyInk()
    private val undo = UndoRedoStack<StickyInk.Action>()

    /** True once the note is on the paper; before it, the whole surface is blocked. */
    private var shown = false
    private var closing = false
    private var selection: Selection? = null

    /** The tool a paste's landing took away — put back pen-idle at that selection's dismissal. */
    private var toolBeforeLanding: Tool? = null

    private var saveJob: Job? = null
    private var dirty = false

    // ── g-paper → the note ───────────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!shown || closing) return
            undo.record(ink.add(stroke))
            scheduleSave()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!shown || closing) return
            ink.erase(strokeIds)?.let { undo.record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!shown || closing) return
            ink.move(move.strokeIds, move.dx, move.dy)?.let { undo.record(it); scheduleSave() }
            selection = selection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            selection?.let { selectionBar.show(it.bounds) }
            pushExclusions()
        }

        override fun onSelectionCreated(selection: Selection) {
            this@StickyEditorActivity.selection = selection
            // Not pen-idle-gated: the lasso ends with the pen still hovering and the engine has
            // already presented the box — this frame is part of that presentation.
            selectionBar.show(selection.bounds)
            pushExclusions()
        }

        override fun onSelectionDragStarted() {
            selectionBar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selection = null
            selectionBar.hide()
            pushExclusions()
            restoreToolAfterLanding()
        }

        /** A pen tap on bare paper with objects on the clipboard: paste their ink here. */
        override fun onPaperTapped(x: Float, y: Float) {
            if (!shown || closing) return
            if (!SnClipboard.hasObjects) return
            doPaste(x, y)
        }

        override fun onToolChanged(tool: Tool) {
            if (::toolbar.isInitialized) toolbar.sync(tool)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        // Nothing staged means the process died under us: the notebook beneath is being recreated
        // and there is nothing here to edit with. Leave at once, empty-handed.
        val staged = StickyEditorTransfer.take()
        if (staged == null) {
            Log.w(TAG, "no showing staged — finishing")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        showing = staged
        binding = ActivityStickyEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // The notebook's fixed tools (P1), armed before the listener is attached — the engine reads
        // the two recognisers as it wires itself up.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.tool = Tool.PEN
        paper.penColor = Stroke.BLACK
        paper.penWidth = NotebookToolbar.PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = NotebookToolbar.ERASER_RADIUS_PX
        snapPrefs = SnapPrefs(this)
        paper.snapMarginPx = resources.getDimensionPixelSize(R.dimen.toolbar_bar_thickness).toFloat()
        paper.snapToGuides = snapPrefs.enabled
        paper.setPaperListener(listener)

        toolbar = PaperToolbar(
            bar = binding.topBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            paper = paper,
            onBack = { exit() },
        )
        // The lasso wears the clipboard mark exactly as the notebook's does (arc 8): the one
        // standing hint that a pen tap on bare paper will paste. Re-read after every copy/cut.
        syncClipboardMark()

        selectionBar = FloatingSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionBar,
            band = { binding.topBar.height.takeIf { it > 0 }?.let { it..binding.root.height } },
            buttons = listOf(
                FloatingSelectionBar.Button(R.drawable.ic_snap, getString(R.string.snap_action_off)) {
                    paper.releaseRender(); toggleSnap()
                },
                FloatingSelectionBar.Button(R.drawable.ic_copy, getString(R.string.copy_objects_action)) {
                    paper.releaseRender(); doCopy(cut = false)
                },
                FloatingSelectionBar.Button(R.drawable.ic_cut, getString(R.string.cut_objects_action)) {
                    paper.releaseRender(); doCopy(cut = true)
                },
                FloatingSelectionBar.Button(R.drawable.ic_trash, getString(R.string.delete_selection_action)) {
                    paper.releaseRender(); deleteSelection()
                },
            ),
        )
        syncSnapButton()

        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selection != null },
            overChrome = { overChrome(it) },
            listener = object : PageGestures.Listener {
                override fun onUndo() = doUndo()
                override fun onRedo() = doRedo()
                // No flips, no inserts, no sheet: a note is one page and has nothing else to hear.
            },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit()
        })

        // The note goes on the paper at the container's first layout — the page size needs the
        // real area when the row carries none, and a stroke written before the load would be
        // thrown away by it, so the surface is blocked until then.
        blockAll()
        binding.paperContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width == 0 || v.height == 0) return@addOnLayoutChangeListener
            if (!shown) showNote(v.width, v.height) else pushExclusions()
        }
        Slog.d(TAG) {
            "open: sticky ${showing.stickyId} ${showing.initial.size} stroke(s) " +
                "${showing.contentW}x${showing.contentH} engine=${paper.engineId}"
        }
    }

    private fun showNote(areaW: Int, areaH: Int) {
        val (w, h) = pageSize(areaW, areaH)
        paper.setPageSize(w, h)
        ink.reset(showing.initial)
        undo.clear()
        paper.loadStrokes(showing.initial)
        shown = true
        pushExclusions()
        Slog.d(TAG) { "note shown ${w}x$h (area ${areaW}x$areaH)" }
    }

    /** The note's own size when the row carries one; this device's paper area otherwise. */
    private fun pageSize(areaW: Int, areaH: Int): Pair<Int, Int> =
        if (showing.contentW > 0 && showing.contentH > 0) showing.contentW to showing.contentH
        else areaW to areaH

    override fun onResume() {
        super.onResume()
        // Reclaim the pipeline (focus events are unreliable on e-ink) — the notebook released it
        // immediately before launching us.
        if (::paper.isInitialized) paper.resumeDrawing()
    }

    override fun onStop() {
        super.onStop()
        // A leave flush: unbounded, because there may be no next debounce.
        flushNow()
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        saveJob?.cancel()
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::paper.isInitialized) {
            gestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val tool = ev.getToolType(0)
                val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                // A finger landing on chrome: the bar's buttons would consume the tap before the
                // overlay let go, so release first — pen-gated, as every chrome release is.
                if (!stylus && !paper.isPenActive && overChrome(ev)) paper.releaseRender()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Every way out — Back (both): flush, hand the final set to the host, release the
     * pipeline while this view still owns it, and only then finish. Idempotent.
     */
    private fun exit() {
        if (closing) return
        closing = true
        flushNow()
        StickyEditorTransfer.leave(ink.strokes)
        setResult(Activity.RESULT_OK)
        paper.releaseForHandoff()
        finish()
    }

    // ── The debounced write ──────────────────────────────────────────────────

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            flushNow()
        }
    }

    /** Enqueue the whole set now, if anything changed since the last write. Never suspends. */
    private fun flushNow() {
        saveJob?.cancel()
        saveJob = null
        if (!dirty) return
        dirty = false
        val strokes = ink.strokes
        showing.sink.setContent(strokes)
        Slog.d(TAG) { "flushed ${strokes.size} stroke(s)" }
    }

    // ── Undo / redo (in memory, per showing) ─────────────────────────────────

    private fun doUndo() {
        if (!shown || closing) return
        val a = undo.popUndo() ?: return
        ink.revert(a)
        undo.pushRedo(a)
        reload()
    }

    private fun doRedo() {
        if (!shown || closing) return
        val a = undo.popRedo() ?: return
        ink.reapply(a)
        undo.pushUndo(a)
        reload()
    }

    /** The page-swap order: selection first, pixels hold, then one refresh with the new content. */
    private fun reload() {
        paper.clearSelection()
        selection = null
        selectionBar.hide()
        paper.clearForContentSwap()
        paper.loadStrokes(ink.strokes)
        pushExclusions()
        scheduleSave()
    }

    // ── The lasso bar ────────────────────────────────────────────────────────

    private fun toggleSnap() {
        val next = !paper.snapToGuides
        paper.snapToGuides = next
        snapPrefs.enabled = next
        syncSnapButton()
    }

    private fun syncSnapButton() {
        val b = selectionBar.buttonAt(0)
        val on = paper.snapToGuides
        b.isSelected = on
        val hint = getString(if (on) R.string.snap_action_on else R.string.snap_action_off)
        b.contentDescription = hint
        TooltipCompat.setTooltipText(b, hint)
    }

    private fun deleteSelection() {
        val ids = selection?.strokeIds?.toList() ?: return
        if (ids.isEmpty()) { paper.clearSelection(); return }
        ink.erase(ids)?.let { undo.record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    /**
     * Copy — or cut, a copy and then the bar's own Delete. The notebook's three orderings, with the
     * drain made unnecessary: the selection is read from [ink], which every act has already
     * updated. **Write, then delete**; then re-arm the lasso so the placement tap that follows
     * places instead of inking (dismissing a selection restores PEN).
     */
    private fun doCopy(cut: Boolean) {
        if (!shown || closing) return
        val sel = selection ?: return
        val strokes = ink.strokes.filter { it.id in sel.strokeIds }
        if (strokes.isEmpty()) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val env = ObjectClip.capture(
                top = StickyClip.rowsFor(strokes, showing.stickyId, now),
                children = emptyList(),
                sourceNotebookId = showing.notebookId,
                now = now,
            )
            if (env == null) {
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_capture_failed)
                return@launch
            }
            val write = runCatching { withContext(Dispatchers.IO) { clipStore.write(env) } }
                .onFailure { Log.w(TAG, "clipboard write failed", it) }
            val header = write.getOrNull()
            if (header == null) {
                val message =
                    if (write.isSuccess) R.string.clip_objects_too_large else R.string.clip_objects_write_failed
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, message)
                return@launch
            }
            if (closing) return@launch
            SnClipboard.set(header)
            syncClipboardMark()
            if (cut) deleteSelection() else paper.clearSelection()
            paper.tool = Tool.LASSO
            toolbar.sync(Tool.LASSO)
            toast(getString(if (cut) R.string.objects_cut_toast else R.string.objects_copied_toast))
            Slog.d(TAG) { "${if (cut) "cut" else "copied"} ${strokes.size} stroke(s)" }
        }
    }

    /**
     * Paste the clipboard's **ink** centred on the tap ([StickyClip]), landing selected under the
     * lasso so the pen drags it into place — the notebook's landing, with its arm-the-lasso rule:
     * a selection under a pen tool is a picture of one.
     */
    private fun doPaste(x: Float, y: Float) {
        lifecycleScope.launch {
            val env = withContext(Dispatchers.IO) { runCatching { clipStore.readEnvelope() }.getOrNull() }
            if (!shown || closing) return@launch
            if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS || env.rows.isEmpty()) {
                // Gone or unreadable. The notebook retires such a clipboard; here the honest answer
                // is the same dialog, and the notebook does the retiring at its next paste.
                Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@launch
            }
            val extracted = StickyClip.extract(env) { UUID.randomUUID().toString() }
            if (extracted == null || extracted.strokes.isEmpty()) {
                if (extracted != null && extracted.leftOut) {
                    Dialogs.problem(this@StickyEditorActivity, R.string.sticky_paste_no_ink_title, R.string.sticky_paste_no_ink_body)
                } else {
                    Dialogs.problem(this@StickyEditorActivity, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                }
                return@launch
            }
            val v = paper.asView()
            val (pageW, pageH) = pageSize(v.width, v.height)
            val placed = StickyClip.placed(extracted, x, y, pageW.toFloat(), pageH.toFloat())
            val action = ink.paste(placed) ?: return@launch
            undo.record(action)
            paper.addStrokes(placed)
            scheduleSave()
            // Land it selected, bar up — the lasso armed first (a selection drawn under PEN can be
            // neither dragged nor tapped); the prior tool returns at this selection's dismissal.
            var box = placed.first().bounds
            for (s in placed) box = box.union(s.bounds)
            val ids = placed.mapTo(HashSet()) { it.id }
            armLassoForLanding()
            paper.setSelection(ids, emptySet(), box)
            selection = Selection(ids, emptySet(), box)
            selectionBar.show(box)
            pushExclusions()
            toast(getString(if (extracted.leftOut) R.string.sticky_paste_ink_only_toast else R.string.objects_pasted_toast))
            Slog.d(TAG) { "pasted ${placed.size} stroke(s)${if (extracted.leftOut) " (ink only)" else ""}" }
        }
    }

    /** The notebook's `showClipboardLoaded`, for this screen's lasso button. */
    private fun syncClipboardMark() {
        binding.btnLasso.setImageResource(
            if (SnClipboard.hasObjects) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso,
        )
    }

    private fun armLassoForLanding() {
        val prior = paper.tool
        if (prior == Tool.LASSO) return
        paper.tool = Tool.LASSO
        toolbar.sync(Tool.LASSO)
        toolBeforeLanding = prior
    }

    /** Put back the tool a paste's landing took away — only while the lasso is still armed (a tool
     *  the user picked meanwhile wins), and pen-idle, because it is a chrome frame. */
    private fun restoreToolAfterLanding() {
        val prior = toolBeforeLanding ?: return
        toolBeforeLanding = null
        if (paper.tool != Tool.LASSO) return
        PenIdle.whenIdle(paper, binding.root) {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenIdle
            paper.tool = prior
            toolbar.sync(prior)
        }
    }

    // ── Chrome geometry ──────────────────────────────────────────────────────

    /** Before the note is on the paper: the whole surface is chrome (nothing may be written). */
    private fun blockAll() {
        val v = paper.asView()
        paper.setExclusionRects(listOf(Rect(0, 0, maxOf(v.width, 1), maxOf(v.height, 1))))
    }

    /** The top bar sits outside the paper; only the floating bar needs excluding, in paper px. */
    private fun pushExclusions() {
        if (!shown) { blockAll(); return }
        val v = paper.asView()
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        paper.setExclusionRects(
            selectionBar.rects().map { Rect(it.left - loc[0], it.top - loc[1], it.right - loc[0], it.bottom - loc[1]) },
        )
    }

    private fun overChrome(ev: MotionEvent): Boolean {
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return PaperToolbar.rectOf(binding.topBar)?.contains(x, y) == true || selectionBar.contains(x, y)
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "StickyEditor"

        /** The trailing debounce between an act and its whole-set write (D2). */
        const val DEBOUNCE_MS = 600L

        private const val EXTRA_NOTEBOOK_ID = "notebookId"
        private const val EXTRA_PAGE_ID = "pageId"
        private const val EXTRA_STICKY_ID = "stickyId"

        /** Ids only, for the log — everything the editor works with rides [StickyEditorTransfer]. */
        fun intent(context: Context, notebookId: String, pageId: String, stickyId: String): Intent =
            Intent(context, StickyEditorActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_STICKY_ID, stickyId)
    }
}
