package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownDraw
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the visible page's text objects into g-paper's committed layer (arc 28 / H1, D1) —
 * [HeadingRenderer]'s twin, multi-line: the raw Markdown source goes through the shared engine
 * ([MarkdownDraw]) at [BASE_SP] regular, black, wrapped at the stored box width, **no `maxLines`**,
 * transparent background. The box was measured by [measure] with the same pipeline, so pixels and
 * stored bounds cannot disagree.
 *
 * [ContentLayer.BELOW_STROKES], registered after the headings and before the shapes (D8). [texts]
 * is the screen's working copy, set on Main at the page-load sites and after every mutation; the
 * engine re-records on `notifyContentChanged()`, never per frame. Implements the live-drag pair.
 */
class TextRenderer(
    private val density: Float,
    scaledDensity: Float,
) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    /** The visible page's texts — read on Main and on the engine's re-record path only. */
    var texts: List<PageText> = emptyList()

    private val paint = basePaint(scaledDensity)

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (t in texts) {
            if (t.id in excludedContentIds) continue
            drawText(canvas, t, density, paint)
        }
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val t = texts.firstOrNull { it.id == contentId } ?: return false
        drawText(canvas, t, density, paint)
        return true
    }

    override fun hitTargets(): List<HitTarget> = texts.map { HitTarget(it.id, it.bounds) }

    companion object {

        /** Body size of an on-page text object, sp (planner call: 24 sp regular). */
        const val BASE_SP = 24f

        /**
         * Draw one text object at its stored box — the single draw recipe, shared with
         * [PagePreview] and [LinkComposite]. Thread-safe off a live view (StaticLayout only).
         */
        fun drawText(canvas: Canvas, t: PageText, density: Float, paint: TextPaint) {
            val w = t.width.roundToInt()
            if (w <= 0) return
            MarkdownDraw.draw(canvas, t.text, x = t.x, y = t.y, widthPx = w, paint = paint, density = density)
        }

        /**
         * The **one** sizing function (D1): the natural multi-line size of [text] wrapped at
         * [availableWidthPx] — og's `pageWidth − x`, never the page width unconditionally — as
         * `(width, height)` in page px. Creation, edit and the per-load `remeasureForDevice` all
         * call this, so a box is always what this device would lay out. Off-Main safe.
         */
        fun measure(text: String, availableWidthPx: Int, density: Float, scaledDensity: Float): Pair<Float, Float> {
            val avail = max(availableWidthPx, MIN_WIDTH_PX)
            val (w, h) = MarkdownDraw.measure(text, avail, basePaint(scaledDensity), density)
            return w.toFloat() to h.toFloat()
        }

        fun basePaint(scaledDensity: Float) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BASE_SP * scaledDensity
        }

        /** A text object pushed against the right edge still gets a column to wrap in. */
        private const val MIN_WIDTH_PX = 48
    }
}
