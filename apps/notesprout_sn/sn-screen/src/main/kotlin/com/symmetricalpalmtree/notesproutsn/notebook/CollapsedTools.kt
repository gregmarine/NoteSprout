package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * The collapsed chrome's rules (arc 36 / C1), kept apart from the views so they can be tested:
 * which glyph the corner button wears for a tool, which mini-toolbar buttons read as armed, and
 * when a contact outside the rows takes them down.
 *
 * There is no dependency on Android views here on purpose — the decisions are the part worth
 * testing, and the view work ([CollapsedChrome]) is the part that cannot be. The drawable ids are
 * plain generated constants, so a JVM test can pin them.
 */
object CollapsedTools {

    /** The fixed order of the mini toolbar's tool buttons (decision 2 / 3): the two erasers are
     *  two buttons, so the lasso eraser is one tap away while collapsed. */
    val ORDER: List<Tool> = listOf(Tool.PEN, Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO)

    /**
     * The corner button's glyph for [tool]. The lasso wears the clipboard mark exactly as the
     * bar's button does while [clipboardLoaded] (arc 8's one standing hint that a pen tap on bare
     * paper will paste). [Tool.NONE] — a surface that captures nothing — wears the pen: the
     * button names what a tap will bring back, and the pen is what every screen arms first.
     */
    fun iconFor(tool: Tool, clipboardLoaded: Boolean = false): Int = when (tool) {
        Tool.PEN, Tool.NONE -> R.drawable.ic_pen
        Tool.ERASER -> R.drawable.ic_eraser
        Tool.LASSO_ERASER -> R.drawable.ic_lasso_eraser
        Tool.LASSO -> if (clipboardLoaded) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
    }

    /**
     * A small overflow is not an overflow (the user's calls on the sticky editor and the pad,
     * 2026-09-11): a `…` that opens a row of one or two buttons is two taps for one, so up to
     * [INLINE_MAX] entries sit on the mini toolbar itself and there is no `…` — the sticky editor's
     * Back, the pad's Back · Send. Three or more (the notebook's and the calendar's doors) go
     * behind it: the mini toolbar stays six buttons at most, which is what fits beside the corner
     * button on the Nomad's width.
     */
    fun overflowInline(count: Int): Boolean = count in 1..INLINE_MAX

    const val INLINE_MAX = 2

    /** The one mini-toolbar button that reads as armed under [tool]; none under [Tool.NONE]. */
    fun selectedFor(tool: Tool): Tool? = tool.takeIf { it in ORDER }

    /**
     * Whether a contact at some point takes the rows down. Nothing showing → nothing to do; on
     * the collapsed chrome itself ([onChrome] — the corner button, whose own click toggles and
     * whose dismissal here would close-then-reopen, the lasso popup's trap, or the rows) → no;
     * inside a sub-bar the screen hung off the rows ([keep]) → no; anywhere else — a bare pen tap,
     * a stroke, a finger gesture, a bar button — yes.
     */
    fun outsideTapDismisses(showing: Boolean, onChrome: Boolean, keep: Boolean): Boolean =
        showing && !onChrome && !keep
}
