package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * `text` rows (arc 28 / H1), through the session's single serial [SoilWriter] — [HeadingStore]'s
 * shape, row for row: write-through and dumb, the screen's in-memory list is the working copy,
 * every mutation is fire-and-forget in queue order, undo replays through these same calls then
 * reloads the page. Soft deletes; restores in place.
 */
class TextStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {

    /** Live texts of [pageId] in `"order"`. A malformed row is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<PageText> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_TEXT).mapNotNull { TextRows.toText(it) }

    /** New text object: insert its row, `"order"` = max among the page's texts (live or not) + 1. */
    fun create(pageId: String, text: PageText) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_TEXT) + 1
        dao.upsert(TextRows.toRow(text.copy(order = order), pageId, now))
        Slog.d(TAG) { "create ${text.id} order=$order ${text.text.length} chars" }
    }

    /** Delete (eraser sweep, selection delete, blank edit-save) — soft, like everything here. */
    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** Undo of a delete: revive the rows in place — position, size and order all kept. */
    fun restore(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.restore(ids, System.currentTimeMillis())
            Slog.d(TAG) { "restore ${ids.size}" }
        }
    }

    /** A finished selection drag: shift each live row's stored top-left by the same delta. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            dao.moveBy(ids, dx, dy, System.currentTimeMillis())
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
    }

    /** An edit-dialog Save: rewrite the source and the re-measured box. Top-left is kept — a text
     *  object grows and shrinks from its anchor, it never wanders. The caller owns the measure. */
    fun updateContent(text: PageText) = writer.enqueue {
        dao.setTextContent(text.id, text.text, text.width, text.height, System.currentTimeMillis())
        Slog.d(TAG) { "update ${text.id} ${text.text.length} chars" }
    }

    private companion object {
        const val TAG = "TextStore"
    }
}
