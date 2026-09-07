package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle

/**
 * **The endnote plan** (arc 28 / D7) — pure, so the numbering, the page sizes and the two links
 * per note are pinned by test rather than by opening a PDF on the Nomad. og's treatment: every
 * sticky note with content becomes one page after the notebook's pages — its strokes on white at
 * the note's own content size, then a caption strip `Note N — from page P` — and two links join
 * them: the icon on the source page jumps to the note, the caption strip jumps home.
 *
 * What this file decides and [ExportRender] merely draws:
 *
 *  - **Numbering is page order, then z-order on the page** — the order [sources] arrive in.
 *  - **Size:** the content size the sticky row carries, or — for a row carrying none (an old or
 *    foreign file) — the source page's own size, the editor's own substitution read from the
 *    export side. Content taller than one page is **not** split (og's deferred item, kept
 *    deferred); a page edge past the container's cap is clamped to it.
 *  - **An icon without area gets no icon link** ([PageBundle.Link] refuses an empty rect); the
 *    caption strip always links home.
 */
object Endnotes {

    /** The caption strip's height under the content, px. */
    const val CAPTION_PX = 60

    /** The caption's text size, px (a plain sans). */
    const val CAPTION_TEXT_PX = 32f

    /** Left inset of the caption text inside its strip, px. */
    const val CAPTION_INSET_PX = 16f

    /** One note as the bake found it: which page (1-based, in bundle order), where its icon sits
     *  on that page (page px), and the content size the row carries (0 = none carried). */
    class Source(
        val stickyId: String,
        val fromPage: Int,
        val iconL: Float,
        val iconT: Float,
        val iconR: Float,
        val iconB: Float,
        val contentW: Int,
        val contentH: Int,
        /** The source page's own size — the fallback content size. */
        val pageW: Int,
        val pageH: Int,
    )

    /** One endnote page to render: `number` is what the caption says, `page` its 1-based place
     *  in the bundle, `contentW × contentH` the ink area above the caption strip. */
    class Note(
        val stickyId: String,
        val number: Int,
        val fromPage: Int,
        val page: Int,
        val contentW: Int,
        val contentH: Int,
    ) {
        val widthPx: Int get() = contentW
        val heightPx: Int get() = contentH + CAPTION_PX
    }

    class Plan(val notes: List<Note>, val links: List<PageBundle.Link>)

    /** og's wording verbatim (the H6 phase-start answer). */
    fun caption(number: Int, fromPage: Int): String = "Note $number — from page $fromPage"

    /**
     * Lay out [sources] after [pageCount] notebook pages. Every source must name a page in
     * `1..pageCount`; the plan's pages run `pageCount + 1 .. pageCount + sources.size`.
     */
    fun plan(sources: List<Source>, pageCount: Int): Plan {
        require(pageCount >= 1) { "no pages" }
        val notes = ArrayList<Note>(sources.size)
        val links = ArrayList<PageBundle.Link>(sources.size * 2)
        sources.forEachIndexed { index, s ->
            require(s.fromPage in 1..pageCount) { "source page ${s.fromPage} outside 1..$pageCount" }
            val number = index + 1
            val page = pageCount + number
            val (w, h) = contentSize(s)
            notes += Note(s.stickyId, number, s.fromPage, page, w, h)
            if (s.iconR > s.iconL && s.iconB > s.iconT &&
                s.iconL.isFinite() && s.iconT.isFinite() && s.iconR.isFinite() && s.iconB.isFinite()
            ) {
                links += PageBundle.Link(s.fromPage, s.iconL, s.iconT, s.iconR, s.iconB, page)
            }
            links += PageBundle.Link(
                page, 0f, h.toFloat(), w.toFloat(), (h + CAPTION_PX).toFloat(), s.fromPage,
            )
        }
        return Plan(notes, links)
    }

    /** The ink area of a note's page: the row's content size, else the source page's; each edge
     *  at least 1 px and the whole page (caption included) inside the container's cap. */
    fun contentSize(s: Source): Pair<Int, Int> {
        val w = (if (s.contentW > 0) s.contentW else s.pageW).coerceIn(1, PageBundle.MAX_DIMENSION_PX)
        val h = (if (s.contentH > 0) s.contentH else s.pageH)
            .coerceIn(1, PageBundle.MAX_DIMENSION_PX - CAPTION_PX)
        return w to h
    }
}
