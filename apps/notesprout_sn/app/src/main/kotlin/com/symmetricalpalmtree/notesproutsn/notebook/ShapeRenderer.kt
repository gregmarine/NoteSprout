package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget

/**
 * Draws the visible page's shapes into g-paper's committed layer (arc 28 / H1, D3): each one is
 * [ShapeGeometry.pathFor] stroked with its own width, round joins and caps, black, **no fill** —
 * on screen and in the PDF alike. [ContentLayer.BELOW_STROKES], registered after the texts and
 * before the links (D8).
 *
 * [shapes] is the screen's working copy, set on Main at the page-load sites and after every
 * mutation; the engine re-records on `notifyContentChanged()`, never per frame. Implements the
 * live-drag pair, which H4's live transform also rides ([drawObject] at the working copy's box —
 * the host swaps the copy on each `onTransformChanged`).
 *
 * Hit targets are the padded AABB ([ShapeGeometry.aabb]) — a rotated shape is hit by its box,
 * og's accepted caveat.
 */
class ShapeRenderer(
    private val density: Float,
) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    /** The visible page's shapes — read on Main and on the engine's re-record path only. */
    var shapes: List<PageShape> = emptyList()

    private val paint = basePaint()

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (s in shapes) {
            if (s.id in excludedContentIds) continue
            drawShape(canvas, s, paint)
        }
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val s = shapes.firstOrNull { it.id == contentId } ?: return false
        drawShape(canvas, s, paint)
        return true
    }

    override fun hitTargets(): List<HitTarget> = shapes.map { HitTarget(it.id, ShapeGeometry.aabb(it, density)) }

    companion object {

        /** Draw one shape — the single draw recipe, shared with [PagePreview] and [LinkComposite].
         *  [paint] comes from [basePaint]; its width is set per shape here. Off-Main safe. */
        fun drawShape(canvas: Canvas, s: PageShape, paint: Paint) {
            if (s.width <= 0f && s.height <= 0f) return
            paint.strokeWidth = s.strokeWidth
            canvas.drawPath(ShapeGeometry.pathFor(s), paint)
        }

        fun basePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }
}
