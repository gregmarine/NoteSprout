package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle

/**
 * **The link table in PDF terms** (arc 28 / D7) — pure, so the one piece of arithmetic between a
 * bundle link and a PDF annotation is pinned by a JVM test rather than by opening the file on a
 * device. The bundle speaks in the host's terms (a top-left-origin rectangle in the from-page's
 * pixels, 1-based page numbers); a PDF annotation wants a bottom-left-origin rectangle on a
 * 0-based page. The PDF page is the notebook page's own pixel size 1:1 ([PdfAssembly.addPage]),
 * so the only conversion is the vertical flip: `lly = pageH − b`, `ury = pageH − t`.
 *
 * **No links, no annotations, nothing touched** — the assembly walks this list and only this
 * list, which is what keeps a sticky-free PDF byte-identical to the arc-18 one.
 */
internal object PdfLinks {

    /** One annotation: on 0-based [pageIndex], the rect `llx lly urx ury` jumps to 0-based
     *  [targetIndex]. Coordinates are PDF user units (= the page's pixels here). */
    data class Annotation(
        val pageIndex: Int,
        val llx: Float,
        val lly: Float,
        val urx: Float,
        val ury: Float,
        val targetIndex: Int,
    )

    /**
     * [links] as the assembly annotates them, given every page's height in stream order
     * ([pageHeights] — the bundle's own declarations, which the decode has been checked
     * against). A link naming a page the heights do not cover is the bundle contradicting itself
     * and is refused, never silently dropped.
     */
    fun annotations(links: List<PageBundle.Link>, pageHeights: List<Int>): List<Annotation> =
        links.map { link ->
            val pageIndex = link.fromPage - 1
            val targetIndex = link.toPage - 1
            require(pageIndex in pageHeights.indices && targetIndex in pageHeights.indices) {
                "link ${link.fromPage}→${link.toPage} outside the ${pageHeights.size} pages"
            }
            val pageH = pageHeights[pageIndex].toFloat()
            Annotation(
                pageIndex = pageIndex,
                llx = link.l,
                lly = pageH - link.b,
                urx = link.r,
                ury = pageH - link.t,
                targetIndex = targetIndex,
            )
        }
}
