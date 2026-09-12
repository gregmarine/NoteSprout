package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipEnvelope
import com.symmetricalpalmtree.notesproutsn.data.clip.ClipRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * The sticky editor's two clipboard rules (arc 28 / H5, decision 3): what a note **copies** and
 * what it **pastes**. Pure — JVM-tested.
 *
 * **Copy** is the notebook's own envelope ([ObjectClip.capture]) built from the editor's in-memory
 * strokes rather than a row read: the rows are what the debounce will write, and the selection is
 * what the user has in hand *now*. Their `parentId` is the sticky's id, which is what makes the
 * notebook's paste treat the note as the "source page" and re-parent them onto the page it lands
 * on — a note's local space and a page's space are both just px from a top-left, so ink moves
 * between the two 1:1.
 *
 * **Paste** takes the **stroke rows only** of whatever the clipboard holds (derived rule: nothing
 * but ink lives inside a note). Page-space ink first — loose strokes and a link's wrapped strokes,
 * which share one coordinate space; only when the payload holds none of those does a copied
 * sticky's own content (local space) count, so two spaces are never mixed in one placement. Every
 * pasted stroke takes a fresh id; the caller places the set by its ink extent.
 */
object StickyClip {

    /** What a paste can use, and whether the clipboard held more than that. */
    data class Extracted(
        /** Decoded, fresh-id strokes in their source coordinates (not yet placed). */
        val strokes: List<Stroke>,
        /** True when something on the clipboard was DROPPED — the "ink only" notice. A copied sticky
         *  whose children all came in drops only its icon, which does not count. */
        val leftOut: Boolean,
    ) {
        /** The ink extent — point bounds grown by half the width (the K2 trap), the box a
         *  placement centres and clamps. Null with no strokes. */
        val bounds: Bounds?
            get() {
                var b: Bounds? = null
                for (s in strokes) { val r = s.bounds.inflated(s.width / 2f); b = b?.union(r) ?: r }
                return b
            }
    }

    /** The rows a copy of [strokes] carries: the note's content rows as the writer would write
     *  them, parented to [stickyId] in local space, `"order"` = index. */
    fun rowsFor(strokes: List<Stroke>, stickyId: String, now: Long): List<SoilObjectEntity> =
        strokes.mapIndexed { i, s -> StrokeRows.toRow(s, stickyId, i, now) }

    /** See the class note. Null when [env] is not an objects payload at all. */
    fun extract(env: ClipEnvelope, newId: () -> String): Extracted? {
        if (env.kind != ClipEnvelope.KIND_OBJECTS) return null
        val stickyIds = env.rows.filter { it.type == SoilSchema.TYPE_STICKY }.mapTo(HashSet()) { it.id }
        val strokeRows = env.rows.filter { it.type == SoilSchema.TYPE_STROKE }
        val pageSpace = strokeRows.filter { it.parentId !in stickyIds }
        val chosen = if (pageSpace.isNotEmpty()) pageSpace else strokeRows
        val strokes = chosen.mapNotNull { row ->
            StrokeRows.toStroke(row.toEntity())?.copy(id = newId())
        }
        // "Left out" is measured against what was DROPPED, not what was on the clipboard: a copied
        // sticky whose children all came in left nothing behind but its icon, which is not ink the
        // user drew — no notice. A heading, a text, a shape, a link's own row, or a stroke set
        // aside for living in another space: each one is.
        val chosenIds = chosen.mapTo(HashSet()) { it.id }
        val leftOut = env.rows.any { row ->
            when (row.type) {
                SoilSchema.TYPE_STROKE -> row.id !in chosenIds
                SoilSchema.TYPE_STICKY -> strokeRows.any { it.parentId == row.id && it.id !in chosenIds }
                else -> true
            }
        }
        return Extracted(strokes, leftOut)
    }

    /** Place [extracted] centred on a tap inside a `pageW × pageH` note, clamped to it. */
    fun placed(extracted: Extracted, tapX: Float, tapY: Float, pageW: Float, pageH: Float): List<Stroke> {
        val box = extracted.bounds ?: return emptyList()
        val off = ObjectPlacement.centredOn(box, tapX, tapY, pageW, pageH)
        return extracted.strokes.map { it.translated(off.dx, off.dy) }
    }

    private fun ClipRow.toEntity() = SoilObjectEntity(
        id = id, parentId = parentId, type = type, order = order,
        createdAt = 0L, updatedAt = 0L, deletedAt = null,
        text = text, refId = refId, x = x, y = y, width = width, height = height,
        color = color, strokeWidth = strokeWidth, style = style, flags = flags,
        blob = blobBytes(),
    )
}
