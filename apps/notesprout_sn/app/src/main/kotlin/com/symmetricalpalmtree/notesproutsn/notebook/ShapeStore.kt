package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * `shape` rows (arc 28 / H1), through the session's single serial [SoilWriter] — [HeadingStore]'s
 * shape. A shape's `x`/`y` is its **centre**, but a move is still a delta on the same two
 * columns, so [move] is [SoilDao.moveBy] like every other columnar row. A transform (H4)
 * rewrites the whole geometry word in one statement ([transform]).
 */
class ShapeStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {

    /** Live shapes of [pageId] in `"order"`. A row of an unknown type is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<PageShape> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_SHAPE).mapNotNull { ShapeRows.toShape(it) }

    /** New shape: insert its row, `"order"` = max among the page's shapes (live or not) + 1. */
    fun create(pageId: String, shape: PageShape) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_SHAPE) + 1
        dao.upsert(ShapeRows.toRow(shape.copy(order = order), pageId, now))
        Slog.d(TAG) { "create ${shape.id} ${shape.type} order=$order" }
    }

    /** Delete (eraser sweep, selection delete) — soft, like everything here. */
    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** Undo of a delete: revive the rows in place — geometry, rotation and order all kept. */
    fun restore(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.restore(ids, System.currentTimeMillis())
            Slog.d(TAG) { "restore ${ids.size}" }
        }
    }

    /** A finished selection drag: shift each live row's stored centre by the same delta. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            dao.moveBy(ids, dx, dy, System.currentTimeMillis())
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
    }

    /** A finished transform (H4) or its replay: centre, extents, rotation, aspect lock and point
     *  count written as one row update. `style` and `strokeWidth` never change after creation. */
    fun transform(shape: PageShape) = writer.enqueue {
        dao.setShapeGeometry(
            shape.id, shape.cx, shape.cy, shape.width, shape.height,
            ShapeFlags.pack(shape.aspectLocked, shape.pointCount, shape.rotationDeg),
            System.currentTimeMillis(),
        )
        Slog.d(TAG) { "transform ${shape.id} ${shape.width}x${shape.height} @${shape.rotationDeg}" }
    }

    private companion object {
        const val TAG = "ShapeStore"
    }
}
