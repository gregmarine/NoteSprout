package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * A sticky note (arc 28 / H1) as the notebook screen holds it: the **icon box** on the page and
 * the note's content size. The page knows the note only by its icon — the content is `stroke`
 * rows parented to [id] in the note's **local** space (`(0,0)` = the content's top-left), read by
 * the editor (H5), the clipboard, the undo snapshot and the PDF endnotes (H6), and **never drawn
 * on the page** (D2). A drag rewrites this one row: the children do not move with it, because
 * they are not in page space at all.
 */
data class PageSticky(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** The note's content size in px, fixed at creation to the creating device's editor paper. */
    val contentW: Int,
    val contentH: Int,
    /** Z-order among the page's sticky rows (`"order"` column). */
    val order: Int,
    /**
     * The note's live child strokes in LOCAL space, in writing order — **empty on the page's own
     * load path** (the notebook never reads inside a note to draw it); populated only where a
     * caller needs the content: a clipboard capture, a delete/paste undo snapshot, the editor's
     * open, the endnote render. Ids are what a delete / restore carries.
     */
    val strokes: List<Stroke> = emptyList(),
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    /** Ids of the note's content rows — the soft-delete / restore set beside the sticky's own id. */
    val childIds: List<String> get() = strokes.map { it.id }

    /** Shifts the icon only — the children are local and stay exactly where they are. */
    fun translated(dx: Float, dy: Float): PageSticky = copy(x = x + dx, y = y + dy)
}

/**
 * The `flags` word of a sticky row — the content size packed into one 64-bit integer because SN
 * adds no column to the family table: bits 0–19 content width px, bits 20–39 content height px
 * (each ≤ [MAX]). Pure — JVM-tested for the round trip.
 */
object StickyFlags {
    const val MAX = 0xFFFFF   // 1,048,575
    private const val MASK = 0xFFFFFL
    private const val H_SHIFT = 20

    fun pack(contentW: Int, contentH: Int): Long =
        (contentW.coerceIn(0, MAX).toLong()) or (contentH.coerceIn(0, MAX).toLong() shl H_SHIFT)

    fun contentW(flags: Long?): Int = ((flags ?: 0L) and MASK).toInt()

    fun contentH(flags: Long?): Int = ((flags ?: 0L) shr H_SHIFT and MASK).toInt()
}

/**
 * The one place a [PageSticky] becomes a `sticky_note` row and back — the arc-28 additive family
 * row type (`SoilSchema.TYPE_STICKY` documents the column contract). The children are rows of
 * their own — passed in and out, never encoded here (the link mapper's rule). Pure — JVM-tested.
 */
object StickyRows {

    fun toRow(sticky: PageSticky, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = sticky.id, parentId = pageId, type = SoilSchema.TYPE_STICKY, order = sticky.order,
        createdAt = now, updatedAt = now,
        x = sticky.x, y = sticky.y, width = sticky.width, height = sticky.height,
        flags = StickyFlags.pack(sticky.contentW, sticky.contentH),
    )

    /**
     * Decode one row with its (possibly empty) already-decoded children; null when it is not a
     * usable sticky — wrong type, or any of the four icon bounds missing / non-finite / negative.
     * A zero content size is tolerated (an old or foreign row): the editor substitutes its own
     * paper size on open rather than refusing the note.
     */
    fun toSticky(row: SoilObjectEntity, strokes: List<Stroke> = emptyList()): PageSticky? {
        if (row.type != SoilSchema.TYPE_STICKY) return null
        val x = row.x ?: return null
        val y = row.y ?: return null
        val w = row.width ?: return null
        val h = row.height ?: return null
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        return PageSticky(
            id = row.id, x = x, y = y, width = w, height = h,
            contentW = StickyFlags.contentW(row.flags), contentH = StickyFlags.contentH(row.flags),
            order = row.order, strokes = strokes,
        )
    }

    /** The icon's edge at creation, in dp (decision 2). */
    const val ICON_DP = 72f
}
