package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * An on-page text object (arc 28 / H1) as the notebook screen holds it: raw Markdown source and
 * its box in page px. The top-left is **authored** (it stays put through an edit — the box grows
 * down and right); the size is **derived** (`TextRenderer.measure` at write time and again on
 * every page load — the heading N3 finding, position is authored, size is measured).
 */
data class PageText(
    val id: String,
    /** Raw Markdown source. Always non-blank — a blank text object never exists (D1). */
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Z-order among the page's text rows (`"order"` column). */
    val order: Int,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    fun translated(dx: Float, dy: Float): PageText = copy(x = x + dx, y = y + dy)
}

/**
 * The one place a [PageText] becomes a `text` row and back — the arc-28 additive family row type
 * (`SoilSchema.TYPE_TEXT` documents the column contract). Pure Kotlin — JVM-tested.
 */
object TextRows {

    fun toRow(text: PageText, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = text.id, parentId = pageId, type = SoilSchema.TYPE_TEXT, order = text.order,
        createdAt = now, updatedAt = now,
        text = text.text,
        x = text.x, y = text.y, width = text.width, height = text.height,
    )

    /**
     * Decode one row; null for the wrong type or a blank/missing `text` — the contract says it is
     * never blank, but a row written by something else degrades to "not rendered", never a crash.
     * Non-finite or negative geometry is dropped the same way.
     */
    fun toText(row: SoilObjectEntity): PageText? {
        if (row.type != SoilSchema.TYPE_TEXT) return null
        val text = row.text ?: return null
        if (text.isBlank()) return null
        val x = row.x ?: 0f
        val y = row.y ?: 0f
        val w = row.width ?: 0f
        val h = row.height ?: 0f
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        return PageText(id = row.id, text = text, x = x, y = y, width = w, height = h, order = row.order)
    }
}
