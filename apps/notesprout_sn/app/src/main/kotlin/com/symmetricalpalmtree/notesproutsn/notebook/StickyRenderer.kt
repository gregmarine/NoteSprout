package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import kotlin.math.roundToInt

/**
 * Draws the visible page's sticky **icons** into g-paper's committed layer (arc 28 / H1, D2): the
 * Tabler `sticker-2` glyph ([icon] — `R.drawable.ic_sticker_2`, loaded by the host) scaled into
 * each note's icon box. The note's content never draws here, or anywhere on the page.
 *
 * The glyph's silhouette is **filled `paperWhite` in `ic_sticker_2` itself** (arc 28 / H5, the
 * user's call), so a template's rules and grid never show through the note that was dropped on top
 * of them — the icon reads as a piece of paper on the page rather than as an outline over it.
 *
 * [ContentLayer.BELOW_STROKES], registered **after** the links — the top of the object stack, so a
 * note dropped over anything stays reachable (D8). [stickies] is the screen's working copy, set on
 * Main at the page-load sites and after every mutation; the engine re-records on
 * `notifyContentChanged()`, never per frame. Implements the live-drag pair.
 *
 * A [Drawable] carries mutable bounds, so one instance is drawn from one thread only: this
 * renderer's is the engine's; an off-Main caller ([PagePreview], [LinkComposite]) loads and
 * `mutate()`s its own before calling [drawSticky].
 */
class StickyRenderer(
    private val icon: Drawable,
) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    /** The visible page's stickies (icons only) — read on Main and on the re-record path only. */
    var stickies: List<PageSticky> = emptyList()

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (s in stickies) {
            if (s.id in excludedContentIds) continue
            drawSticky(canvas, s, icon)
        }
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val s = stickies.firstOrNull { it.id == contentId } ?: return false
        drawSticky(canvas, s, icon)
        return true
    }

    override fun hitTargets(): List<HitTarget> = stickies.map { HitTarget(it.id, it.bounds) }

    companion object {

        /** Draw one icon at its stored box — the single draw recipe, shared with [PagePreview] and
         *  [LinkComposite]. [icon] must belong to the calling thread (see the class KDoc). */
        fun drawSticky(canvas: Canvas, s: PageSticky, icon: Drawable) {
            if (s.width <= 0f || s.height <= 0f) return
            icon.setBounds(
                s.x.roundToInt(), s.y.roundToInt(),
                (s.x + s.width).roundToInt(), (s.y + s.height).roundToInt(),
            )
            icon.draw(canvas)
        }
    }
}
