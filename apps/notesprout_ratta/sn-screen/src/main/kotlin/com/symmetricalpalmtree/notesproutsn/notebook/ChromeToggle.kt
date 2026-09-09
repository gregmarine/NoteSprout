package com.symmetricalpalmtree.notesproutsn.notebook

import android.view.View
import androidx.core.view.doOnNextLayout
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * Hides / shows all of a paper screen's chrome bars — the arc-33 double-tap toggle, one copy for
 * the four screens (notebook, sticky editor, scratch pad, calendar) so the flip order never drifts.
 *
 * **The flip order, once:**
 * 1. `paper.releaseRender()` — unless [apply]'s `initial`, when nothing is on the glass yet;
 * 2. hiding → [beforeHide]: the consumer takes down its button-anchored popups (lasso, tags,
 *    insert, eraser) whose button is about to go;
 * 3. every bar `GONE` / `VISIBLE` — **never `INVISIBLE`**: an attached Ratta paper view keeps the
 *    pen claimed whatever a sibling's visibility, and an `INVISIBLE` bar keeps its rect;
 * 4. `root.doOnNextLayout { afterLayout() }` — the consumer's `pushExclusions()`, which re-reads the
 *    band ([ChromeBand]), the rects (`PaperToolbar.rectOf`, visibility-aware) and, on the notebook,
 *    the snap margin. One binder call per flip.
 *
 * Deliberately **not** pen-idle-gated: the act that asks for a flip already passed
 * `PageGestures.gateOpen()` and its escrow, and `isPenActive` counts hover — the bars would arrive
 * long after the taps. It is a chrome frame at a deliberate act (`docs/notebook.md` § frame-silence,
 * riding exception 6). Nothing here touches `setPageSize`: the paper view never resizes on a flip.
 */
class ChromeToggle(
    private val paper: PaperView,
    private val root: View,
    private val bars: List<View>,
    private val beforeHide: () -> Unit,
    private val afterLayout: () -> Unit,
) {
    /** The current state; `false` (shown) until the first [apply]. */
    var hidden: Boolean = false
        private set

    /**
     * Put the chrome into [hidden]. A no-op when already there — so an `onResume` re-sync against
     * the persisted flag costs nothing when nothing changed. [initial] skips the render release
     * (an `onCreate` call: nothing is on the glass).
     */
    fun apply(hidden: Boolean, initial: Boolean = false) {
        if (this.hidden == hidden && !initial) return
        this.hidden = hidden
        if (!initial) paper.releaseRender()
        if (hidden) beforeHide()
        val visibility = if (hidden) View.GONE else View.VISIBLE
        bars.forEach { it.visibility = visibility }
        Slog.d(TAG) { "chrome hidden=$hidden" }
        root.doOnNextLayout { afterLayout() }
        root.requestLayout()
    }

    fun toggle() = apply(!hidden)

    private companion object { const val TAG = "ChromeToggle" }
}
