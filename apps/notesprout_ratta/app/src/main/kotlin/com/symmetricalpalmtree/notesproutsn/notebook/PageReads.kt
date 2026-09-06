package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/** One page as the link picker browses it: identity + authored size (page px). */
data class PickerPage(val id: String, val width: Int, val height: Int)

/**
 * Everything a page preview draws and a page label reads: the page's loose strokes (writing
 * order), its loose headings (z-order), and its links with their wrapped children ([PageLink] —
 * the wrapped content is *not* in the loose lists, exactly as the rows have it). The page label
 * reads across both ([PageLabels.titleOf]): a wrapped heading still names its page.
 */
class PageContent(
    val strokes: List<Stroke>,
    val headings: List<Heading>,
    val links: List<PageLink>,
    /** Arc 28 (H1): the page's loose texts, shapes and sticky icons, each in z-order. A wrapped one
     *  lives inside its [PageLink], exactly as a wrapped heading does. */
    val texts: List<PageText> = emptyList(),
    val shapes: List<PageShape> = emptyList(),
    val stickies: List<PageSticky> = emptyList(),
)

/**
 * Read-only page gathering for the link picker (arc 6 / K2) — one recipe over any [SoilDao],
 * whichever notebook it belongs to: the current one's live session or a foreign file's read-only
 * open ([ForeignPageSource]). Decode failures degrade row-by-row (the family rule: a bad blob is
 * dropped, the page still previews). Suspend-only, no writes — JVM-tested over the fake DAO.
 */
object PageReads {

    /** The notebook's live pages in order, as the picker's grid items. */
    suspend fun pages(dao: SoilDao, notebookId: String): List<PickerPage> =
        dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE).map {
            PickerPage(it.id, (it.width ?: 0f).toInt(), (it.height ?: 0f).toInt())
        }

    /**
     * One page's drawable + labelable content — see [PageContent]. Every kind is read at both
     * levels (arc 28 / H1): loose on the page, and wrapped inside each link, exactly as the rows
     * have it. A **sticky arrives icon-only** on both levels — a note's content never draws on a
     * page, and this is a drawing read (D2).
     *
     * Session-free by construction: it goes through the [SoilDao] alone, so the same recipe serves
     * the live notebook, a foreign file's read-only open and the export bake.
     */
    suspend fun content(dao: SoilDao, pageId: String): PageContent {
        val strokes = dao.childrenOfType(pageId, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }
        val headings = dao.childrenOfType(pageId, SoilSchema.TYPE_HEADING).mapNotNull { HeadingRows.toHeading(it) }
        val texts = dao.childrenOfType(pageId, SoilSchema.TYPE_TEXT).mapNotNull { TextRows.toText(it) }
        val shapes = dao.childrenOfType(pageId, SoilSchema.TYPE_SHAPE).mapNotNull { ShapeRows.toShape(it) }
        val stickies = dao.stickiesOf(pageId).mapNotNull { StickyRows.toSticky(it) }
        val links = dao.linksOf(pageId).mapNotNull { row ->
            val childStrokes = dao.childrenOfType(row.id, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }
            val childHeadings = dao.childrenOfType(row.id, SoilSchema.TYPE_HEADING).mapNotNull { HeadingRows.toHeading(it) }
            val childTexts = dao.childrenOfType(row.id, SoilSchema.TYPE_TEXT).mapNotNull { TextRows.toText(it) }
            val childShapes = dao.childrenOfType(row.id, SoilSchema.TYPE_SHAPE).mapNotNull { ShapeRows.toShape(it) }
            val childStickies = dao.childrenOfType(row.id, SoilSchema.TYPE_STICKY).mapNotNull { StickyRows.toSticky(it) }
            LinkRows.toLink(row, childStrokes, childHeadings, childTexts, childShapes, childStickies)
        }
        return PageContent(strokes, headings, links, texts, shapes, stickies)
    }
}
