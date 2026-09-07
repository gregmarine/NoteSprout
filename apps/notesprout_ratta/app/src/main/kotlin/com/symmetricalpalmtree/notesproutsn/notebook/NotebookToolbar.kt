package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding

/**
 * The notebook's chrome: back, and the three tool buttons — the eraser one standing for **two**
 * erasers since arc 29 / LE2 (Point and Lasso, picked from the sub-bar its own re-tap opens; the
 * button's icon says which is armed). It owns every tool decision — the activity hands it the
 * binding and the surface and never touches `paper.penWidth` itself.
 *
 * **The tools are fixed (P1).** Pen is PEN · black · [PEN_WIDTH_PX]; the eraser is
 * [ERASER_RADIUS_PX]; there are no panels and nothing is remembered between sessions. Handwriting
 * is the app, and a bar that only ever arms a tool is one less thing between the pen and the paper —
 * the R3 panels bought five widths, five styles and sixteen greys at the cost of a two-tap gesture
 * on every button and a chrome surface that could sit open over the page. Existing strokes still
 * render exactly as they were authored (width, style and grey travel in the row), so nothing had to
 * migrate.
 *
 * Two rules shape what is left:
 *  - **Release the render first — but pen-gated.** Every handler calls [releaseRenderIfIdle] before
 *    it does anything else: while the EPD writing overlay is armed the bar will not show a new
 *    pressed state and the tap reads as broken. The gate is the [PaperView.releaseRender] API
 *    contract — an ungated release inside the pen-active window can cost a live stroke.
 *  - **[sync] is the truth, not our taps.** g-paper changes tools by itself (smart lasso arms
 *    LASSO and restores PEN when the selection goes), so button state is driven from
 *    `PaperListener.onToolChanged` — never assumed from the tap that started it.
 *
 * Selected = the bordered `state_selected` look of `bg_toolbar_button`. No colour anywhere.
 */
class NotebookToolbar(
    private val binding: ActivityNotebookBinding,
    private val paper: PaperView,
    private val onBack: () -> Unit,
    /** A tap on the **already-armed** lasso (arc 8) — P1's no-op grew a meaning: the screen opens
     *  the clipboard popup, or keeps the no-op when there is nothing on the clipboard. */
    private val onLassoReTap: () -> Unit = {},
    /** A tap on the **already-armed** eraser (arc 29 / LE2) — the same grown no-op the lasso got:
     *  the screen opens the eraser sub-bar (Point · Lasso). The eraser button is armed under
     *  **both** erasers, so a tap while `LASSO_ERASER` is armed is a re-tap too. */
    private val onEraserReTap: () -> Unit = {},
    /** Any tool tap at all — the screen closes floating chrome that belonged to the old tool. */
    private val onToolTapped: () -> Unit = {},
) {

    init {
        // Arm the surface before anything is drawn or shown. These are the tools, for good.
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX

        with(binding) {
            listOf(btnBack, btnPen, btnEraser, btnLasso).forEach {
                TooltipCompat.setTooltipText(it, it.contentDescription)
            }
            btnBack.setOnClickListener {
                releaseRenderIfIdle()
                onBack()
            }
            btnPen.setOnClickListener { onToolTap(Tool.PEN) }
            btnEraser.setOnClickListener { onToolTap(Tool.ERASER) }
            btnLasso.setOnClickListener { onToolTap(Tool.LASSO) }
        }

        sync(paper.tool)
    }

    /**
     * Tapping a tool arms it. A second tap on the armed one still changes nothing about the tool —
     * a button that disarmed itself would leave the pen doing something the bar isn't showing — but
     * on the **lasso** it opens the clipboard popup ([onLassoReTap], arc 8) and on the **eraser**
     * it opens the eraser sub-bar ([onEraserReTap], arc 29 / LE2). Only the pen keeps the P1 no-op.
     *
     * The eraser is "armed" under [Tool.ERASER] **and** [Tool.LASSO_ERASER] — the button is
     * selected under both — so a tap on it while the lasso eraser is armed is a re-tap as well,
     * never a silent drop back to the point eraser.
     *
     * [onToolTapped] fires **only on an actual tool change**, and that ordering is load-bearing: it
     * is what takes the popup down when another tool is armed, so firing it on the re-tap too would
     * hide the popup a moment before [onLassoReTap] asked whether it was showing — and the toggle
     * would reopen what it was meant to close, every time (O2 review). The eraser sub-bar's toggle
     * is the same shape and depends on the same ordering.
     */
    private fun onToolTap(tool: Tool) {
        releaseRenderIfIdle()
        if (tool == Tool.ERASER && (paper.tool == Tool.ERASER || paper.tool == Tool.LASSO_ERASER)) {
            onEraserReTap()
            return
        }
        if (paper.tool == tool) {
            if (tool == Tool.LASSO) onLassoReTap()
            return
        }
        onToolTapped()
        paper.tool = tool
        sync(tool)
        Slog.d(TAG) { "armed $tool" }
    }

    /**
     * Arm [tool] from the **host** side and make the buttons say so (arc 29 / LE2) — what the
     * eraser sub-bar's pick lands on. It exists because a tool assignment the host makes is never
     * echoed back as `PaperListener.onToolChanged` (it is not component-initiated), so [sync] has
     * to be called by hand or the bar would keep showing the tool that is no longer armed.
     */
    fun arm(tool: Tool) {
        releaseRenderIfIdle()
        if (paper.tool != tool) paper.tool = tool
        sync(tool)
        Slog.d(TAG) { "armed $tool" }
    }

    /**
     * Swap the lasso button's icon to the lasso-with-a-plus while [loaded] objects are on the
     * clipboard (arc 8 — og's own icon). This is the **only** standing hint that a pen tap on bare paper will paste
     * — tap-to-place changes nothing else about the surface — so it is a state of the button, not a
     * transient toast. Idempotent; the screen calls it whenever the clipboard's kind can have moved.
     */
    fun showClipboardLoaded(loaded: Boolean) {
        binding.btnLasso.setImageResource(
            if (loaded) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
        )
    }

    /** Which eraser glyph the button currently wears — the layout's `ic_eraser` at construction. */
    private var eraserShowsLasso = false

    /**
     * Make the buttons honest about [tool]. Called from `PaperListener.onToolChanged` — the
     * component arms and restores tools on its own (smart lasso), so this runs for changes we
     * never initiated.
     */
    fun sync(tool: Tool) = with(binding) {
        btnPen.isSelected = tool == Tool.PEN
        // The eraser button stands for both erasers, and its icon says which one is armed (arc 29 /
        // LE2 — [showClipboardLoaded]'s precedent: a standing state of the surface belongs on the
        // button that owns it).
        btnEraser.isSelected = tool == Tool.ERASER || tool == Tool.LASSO_ERASER
        // Swapped only on a change of kind: every `onToolChanged` lands here, and re-setting the
        // same drawable would invalidate the button for nothing (frame silence).
        val lassoKind = tool == Tool.LASSO_ERASER
        if (lassoKind != eraserShowsLasso) {
            eraserShowsLasso = lassoKind
            btnEraser.setImageResource(if (lassoKind) R.drawable.ic_lasso_eraser else R.drawable.ic_eraser)
        }
        btnLasso.isSelected = tool == Tool.LASSO
    }

    /**
     * The API contract for [PaperView.releaseRender]: guard with [PaperView.isPenActive] so a
     * resting palm (or a tap landing inside the pen-up tail) can never cost a live stroke. While
     * the pen is active the user is not looking at chrome pressed-states anyway — skipping the
     * release costs nothing.
     */
    private fun releaseRenderIfIdle() {
        if (!paper.isPenActive) paper.releaseRender()
    }

    companion object {
        private const val TAG = "NotebookToolbar"

        /** The one pen width, in px (Paper-v0 parity, and og Notesprout's stored default). */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's own default, and Paper v0's. */
        const val ERASER_RADIUS_PX = 15f
    }
}
