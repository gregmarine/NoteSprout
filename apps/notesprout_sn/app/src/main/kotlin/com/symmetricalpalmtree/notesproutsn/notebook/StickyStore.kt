package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * `sticky_note` rows and their content children (arc 28 / H1), through the session's single
 * serial [SoilWriter] — [LinkStore]'s shape, because a sticky, like a link, is a row with
 * children: the multi-row ops run inside one Room transaction ([transact], injected so the store
 * stays JVM-testable), so an icon row and its content are never separately visible.
 *
 * Unlike a link's children, a note's are in the note's **local** space and never move with the
 * icon: [move] rewrites the one row. The page load reads icons only ([loadPage]); content is read
 * on demand ([content] / [withContent]) — by the editor (H5), the clipboard capture, the delete
 * snapshot and the endnote render (H6). Soft deletes; restores in place.
 */
class StickyStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
    /** One Room transaction around [block] — `db.withTransaction` in production, direct call in tests. */
    private val transact: suspend (block: suspend () -> Unit) -> Unit,
) {

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live stickies of [pageId] in z-order, **icons only** (`strokes` empty). */
    suspend fun loadPage(pageId: String): List<PageSticky> =
        dao.stickiesOf(pageId).mapNotNull { StickyRows.toSticky(it) }

    /** A note's live content strokes in writing order, local space. A bad blob is dropped. */
    suspend fun content(stickyId: String): List<Stroke> =
        dao.childrenOfType(stickyId, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }

    /** [sticky] with its content read — the snapshot a delete / copy / edit-undo carries.
     *  **Drain the writer first** (the arc's standing trap). */
    suspend fun withContent(sticky: PageSticky): PageSticky = sticky.copy(strokes = content(sticky.id))

    // ── Writes (Main → serial IO) ────────────────────────────────────────────

    /** New sticky (an Insert): the icon row at `MAX("order")+1` among the page's stickies. Content
     *  arrives later through [setContent]. */
    fun create(pageId: String, sticky: PageSticky) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_STICKY) + 1
        dao.upsert(StickyRows.toRow(sticky.copy(order = order), pageId, now))
        Slog.d(TAG) { "create ${sticky.id} ${sticky.contentW}x${sticky.contentH} order=$order" }
    }

    /** Soft-delete stickies **and their content** (a selection delete / an eraser hit). The live
     *  children are read here, in the job, so the caller needs no snapshot to delete — but it
     *  needs one ([withContent]) to undo. */
    fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            transact {
                for (id in ids) {
                    val children = dao.childrenOfType(id, SoilSchema.TYPE_STROKE).map { it.id }
                    (children + id).chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
                }
            }
            Slog.d(TAG) { "remove ${ids.size}" }
        }
    }

    /**
     * [remove] that also hands back the **undo snapshot** — each icon with its content read in the
     * same job, *ahead of* the soft-delete, in one transaction (arc 34 / M6). The delete is queued
     * on the spot, in writer order, so a caller inside a g-paper callback needs no page op, no
     * drain and no suspending read of its own: an erase-then-Back still lands the delete (the old
     * shape read the content under a page op that `closing` skips, and the rows came back on the
     * next open). The deferred completes on the writer; a closed writer **cancels** it, since a
     * delete that will never run deserves no undo entry.
     */
    fun removeWithContent(icons: List<PageSticky>): Deferred<List<PageSticky>> {
        val out = CompletableDeferred<List<PageSticky>>()
        if (icons.isEmpty()) { out.complete(emptyList()); return out }
        val accepted = writer.enqueue {
            try {
                val now = System.currentTimeMillis()
                val full = ArrayList<PageSticky>(icons.size)
                transact {
                    for (icon in icons) {
                        val children = dao.childrenOfType(icon.id, SoilSchema.TYPE_STROKE)
                        full += icon.copy(strokes = children.mapNotNull { StrokeRows.toStroke(it) })
                        (children.map { it.id } + icon.id).chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
                    }
                }
                Slog.d(TAG) { "removeWithContent ${icons.size}" }
                out.complete(full)
            } catch (e: Exception) {
                out.completeExceptionally(e)
                throw e
            }
        }
        if (!accepted) out.cancel()
        return out
    }

    /** Undo of [remove] / redo of a paste: revive each icon row in place (or insert the snapshot
     *  when no row exists — a paste's undo/redo) and revive its snapshot's children by id. */
    fun restore(pageId: String, stickies: List<PageSticky>) {
        if (stickies.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            transact {
                for (s in stickies) {
                    if (dao.byId(s.id) != null) dao.restore(listOf(s.id), now)
                    else dao.upsert(StickyRows.toRow(s, pageId, now))
                }
                stickies.flatMap { it.childIds }.chunked(ID_CHUNK).forEach { dao.restore(it, now) }
            }
            Slog.d(TAG) { "restore ${stickies.size} to $pageId" }
        }
    }

    /** A finished selection drag: the icon row moves; the content, being local, does not. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            dao.moveBy(ids, dx, dy, System.currentTimeMillis())
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
    }

    /**
     * Make [strokes] the note's whole content (the editor's debounced write, H5; and the replay of
     * `StickyContentEdited` in either direction): live children not in the set are soft-deleted,
     * every stroke in the set is upserted **in local space** with `"order"` = its index (relative
     * sequence preserved — writing order is load-bearing), an existing row reviving in place.
     * One transaction: a note is never seen half-written.
     */
    fun setContent(stickyId: String, strokes: List<Stroke>) = writer.enqueue {
        val now = System.currentTimeMillis()
        transact {
            val keep = strokes.mapTo(HashSet()) { it.id }
            val gone = dao.childrenOfType(stickyId, SoilSchema.TYPE_STROKE).map { it.id }.filter { it !in keep }
            gone.chunked(ID_CHUNK).forEach { dao.softDelete(it, now) }
            strokes.forEachIndexed { i, s ->
                val existing = dao.byId(s.id)
                val row = StrokeRows.toRow(s, stickyId, i, now)
                dao.upsert(if (existing != null) row.copy(createdAt = existing.createdAt) else row)
            }
        }
        Slog.d(TAG) { "setContent $stickyId ${strokes.size} strokes" }
    }

    private companion object {
        const val TAG = "StickyStore"

        /** SQLite caps bound variables at 999 — id lists go in below that. */
        const val ID_CHUNK = 500
    }
}
