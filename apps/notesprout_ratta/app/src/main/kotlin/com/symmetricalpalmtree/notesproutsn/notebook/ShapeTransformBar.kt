package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The floating bar that stands beside a shape for as long as g-paper's transform mode has it (arc
 * 28 / H4) — two controls and no more:
 *
 *  - the **aspect latch**, a word rather than a glyph ([ShapeTransformLabels]) because "Circle" and
 *    "Oval" are the two things the shape can be and no icon says that; its state is the selected
 *    border every armed control in this app wears;
 *  - **Done**, which is the host's `endTransform` and nothing else — every other way out of the
 *    mode belongs to the engine (a tap outside, a tool change, an erase, a data-in call), and all
 *    of them tear this bar down through the same `onTransformEnded`.
 *
 * There is deliberately no Delete, no Copy and no Cancel. The mode is not a selection: Delete
 * belongs to the lasso bar the shape comes back under, and a Cancel would need an undo of its own
 * when the history already has one entry per finished transform.
 *
 * **Placement** is [SelectionToolbar]'s, with one difference that matters on the glass: the box the
 * bar is placed off is the shape's AABB grown by [OVERLAY_REACH_DP] — the engine's rotate knob
 * sits on a 36 dp stem above the top edge, is 14 dp across and has a 22 dp touch pad, so a bar
 * placed off the tight box would sit under the one control the hand reaches for most.
 *
 * **It moves as little as possible.** [coveredBy] is what the flow asks before re-placing during a
 * drag: a bar that chased every sample would be a second thing moving under the hand, and on e-ink
 * every move is a refresh. It re-places only when the growing shape has actually reached it.
 *
 * The screen owns *when* it appears and disappears — both are [ShapeFlow]'s calls, which are the
 * `beginTransform` and the single `onTransformEnded` — and unions [rects] into the exclusion rects
 * and the `overChrome` test, because a pen landing on a floating bar must never ink.
 */
class ShapeTransformBar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom strip's top. */
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onToggleLock: () -> Unit,
    private val onDone: () -> Unit,
) {

    private val density = root.resources.displayMetrics.density

    private val lockButton: AppCompatButton

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val padH = (LABEL_PAD_DP * density).toInt()
        lockButton = AppCompatButton(ctx).apply {
            // A style cannot be applied to a view built in code (ExportPanel's note), so
            // `Widget.Notesprout.TextButton`'s look is set field by field, over the toolbar
            // button's own background so the latch can show a selected border.
            setBackgroundResource(R.drawable.bg_toolbar_button)
            setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
            textSize = LABEL_SP
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding(padH, 0, padH, 0)
            val hint = ctx.getString(R.string.shape_lock_hint)
            contentDescription = hint
            TooltipCompat.setTooltipText(this, hint)
            // Ungated, like every other bar handler in this screen: the pen that tapped it is
            // still hovering, and an idle-gated release would hold the frame until it left.
            setOnClickListener { releaseRender(); onToggleLock() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
        }
        bar.addView(lockButton)
        bar.addView(
            iconButton(R.drawable.ic_check, ctx.getString(R.string.shape_transform_done)) {
                releaseRender()
                onDone()
            }
        )
    }

    /**
     * Put the bar up beside [shape] (or move it there). A no-op before the root has been laid out,
     * which is what makes the flow's calls safe at every moment the geometry is not yet knowable.
     */
    fun show(shape: PageShape) {
        val band = band() ?: return
        relabel(shape)
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val dx = paperLoc[0] - rootLoc[0]
        val dy = paperLoc[1] - rootLoc[1]
        val box = overlayBox(shape)
        // Measure before placing: the anchor centres and flips on the bar's real size, and a bar
        // that has never been visible has none (the SelectionToolbar lesson).
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.place(
            selLeft = (box.left + dx).toInt(),
            selTop = (box.top + dy).toInt(),
            selRight = (box.right + dx).toInt(),
            selBottom = (box.bottom + dy).toInt(),
            toolbarW = bar.measuredWidth,
            toolbarH = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(),
            rootWidth = root.width,
            bandTop = band.first,
            bandBottom = band.last,
        )
        val lp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = p.x
        lp.topMargin = p.y
        bar.layoutParams = lp
    }

    /** Re-word the latch for [shape]'s current lock state, without moving the bar. */
    fun relabel(shape: PageShape) {
        val locked = shape.aspectLocked
        lockButton.setText(ShapeTransformLabels.res(shape.type, locked))
        lockButton.isSelected = locked
    }

    /**
     * Whether the engine's overlay for [shape] now overlaps the bar where it stands — the flow's
     * gate for re-placing during a drag. False while the bar is down, so a caller need not check.
     */
    fun coveredBy(shape: PageShape): Boolean {
        val barRect = PaperToolbar.rectOf(bar) ?: return false
        val loc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val b = overlayBox(shape)
        val overlay = Rect(
            (b.left + loc[0]).toInt(), (b.top + loc[1]).toInt(),
            (b.right + loc[0]).toInt(), (b.bottom + loc[1]).toInt(),
        )
        return Rect.intersects(barRect, overlay)
    }

    /** Idempotent — every teardown path calls it without checking. */
    fun hide() {
        bar.visibility = View.GONE
    }

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(bar))

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    /** The shape's hit box grown by everything the engine draws around it (see the class KDoc). */
    private fun overlayBox(shape: PageShape): Bounds =
        ShapeGeometry.aabb(shape, density).inflated(SELECTION_BOX_INFLATE_PX + OVERLAY_REACH_DP * density)

    /** [AnchoredBar]'s one button recipe — the same one every floating bar in this screen uses. */
    private fun iconButton(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        AnchoredBar.button(bar.context, iconRes, hint, onClick)

    private companion object {

        /** The gap every floating bar in this screen keeps off the thing it belongs to. */
        const val GAP_DP = 8f

        /**
         * How far outside the shape's own box the engine's overlay reaches: the rotate knob's
         * 36 dp stem, the 14 dp knob on the end of it, and the 22 dp pad the finger may grab it
         * by. Grown on every side rather than only above — the knob rides the rotation.
         */
        const val OVERLAY_REACH_DP = 36f + 14f + 22f

        /** g-paper's own box inflation, mirrored for the reason [SelectionToolbar] mirrors it:
         *  `CanvasPaperView.SELECTION_BOX_INFLATE_PX` has a private companion. Keep the three in
         *  step across engine bumps. */
        const val SELECTION_BOX_INFLATE_PX = 12f

        const val LABEL_SP = 14f
        const val LABEL_PAD_DP = 12f
    }
}
