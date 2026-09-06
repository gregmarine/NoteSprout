package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import com.symmetricalpalmtree.notesproutsn.notebook.PageShape
import com.symmetricalpalmtree.notesproutsn.notebook.PageSticky
import com.symmetricalpalmtree.notesproutsn.notebook.PageText
import com.symmetricalpalmtree.notesproutsn.notebook.ShapeFlags
import com.symmetricalpalmtree.notesproutsn.notebook.ShapeRows
import com.symmetricalpalmtree.notesproutsn.notebook.ShapeType
import com.symmetricalpalmtree.notesproutsn.notebook.StickyRows
import com.symmetricalpalmtree.notesproutsn.notebook.StrokeRows
import com.symmetricalpalmtree.notesproutsn.notebook.TextRenderer
import com.symmetricalpalmtree.notesproutsn.notebook.TextRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Debug build only (arc 28 / H1) — **the only way an object of the three new kinds gets onto a
 * page at H1**, because nothing on the Insert bar is offered yet and the phases that create them
 * are H2 / H4 / H5. It writes rows straight into a notebook's `.soil` so the Nomad can be shown a
 * page that has one of everything: the draw order (D8), the hit boxes, a close-and-reopen, and the
 * selection modes all become checkable a whole arc before the creation flows exist.
 *
 * One text object, all six shapes, and one sticky note with three content strokes, on the
 * notebook's **first live page**, through the real mappers ([TextRows] / [ShapeRows] /
 * [StickyRows] / [StrokeRows]) at `MAX("order") + 1` per type — never hand-built SQL, so a row this
 * writes is a row the app itself would have written.
 *
 * Refuses a notebook that is **open in this process** and one this device holds no key for, the way
 * [RekeyProbe] does: one file, one connection, family-wide, and a silent reader never prompts.
 *
 * Removed at H7 unless the phase-start answer keeps it.
 */
object SampleObjects {

    private const val SAMPLE_TEXT =
        "# Sample\n\nA **text** object with *markdown*, wrapping across a couple of lines on the page."

    /** Where the sample lands, in page px — a column down the left, clear of both chrome bars. */
    private const val LEFT = 80f
    private const val TEXT_TOP = 200f
    private const val SHAPES_TOP = 600f
    private const val SHAPE_EDGE = 144f
    private const val LONG_EDGE = 300f
    private const val STICKY_TOP = 900f
    private const val STAR_ROTATION_DEG = 37f

    /**
     * Write the sample onto [notebookId]'s first live page. Returns the one line the caller
     * toasts — a "wrote…" summary, or a `FAIL — …` that says which door refused.
     *
     * [density] / [scaledDensity] are the calling screen's: the text box is measured with the same
     * [TextRenderer.measure] the notebook uses, so it lands at exactly the size the page will
     * re-measure it to on the next load.
     */
    suspend fun insert(
        context: Context,
        notebookId: String,
        density: Float,
        scaledDensity: Float,
    ): String = withContext(Dispatchers.IO) {
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) return@withContext "FAIL — notebook is open in this process"
        if (!file.exists() || file.length() == 0L) return@withContext "FAIL — no file for this notebook"
        val resolved = SoilDatabase.resolve(context, notebookId)
        if (resolved is KeyResolver.Resolved.NeedsPrompt || resolved is KeyResolver.Resolved.NoKey) {
            return@withContext "FAIL — this notebook is locked; open it once to unlock, then run this again"
        }
        val db = try {
            SoilDatabase.open(context, notebookId, file, resolved)
        } catch (e: Exception) {
            return@withContext "FAIL — could not open: ${e.javaClass.simpleName}"
        }
        try {
            val dao = db.dao()
            val page = dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE).firstOrNull()
                ?: return@withContext "FAIL — the notebook has no live page"
            val pageWidth = (page.width ?: 0f).toInt()
            if (pageWidth <= 0) return@withContext "FAIL — the first page has no authored width"
            write(dao, page.id, pageWidth, density, scaledDensity)
            "Wrote 1 text, 6 shapes and 1 sticky note (3 strokes) to page 1"
        } catch (e: Exception) {
            "FAIL — ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            db.seal(file)   // never throws (its own contract)
        }
    }

    private suspend fun write(
        dao: SoilDao,
        pageId: String,
        pageWidth: Int,
        density: Float,
        scaledDensity: Float,
    ) {
        val now = System.currentTimeMillis()

        // ── One text object, measured exactly as the page will re-measure it ──
        val (w, h) = TextRenderer.measure(
            SAMPLE_TEXT, (pageWidth - LEFT).toInt(), density, scaledDensity,
        )
        val text = PageText(
            id = UUID.randomUUID().toString(), text = SAMPLE_TEXT,
            x = LEFT, y = TEXT_TOP, width = w, height = h,
            order = dao.maxOrder(pageId, SoilSchema.TYPE_TEXT) + 1,
        )
        dao.upsert(TextRows.toRow(text, pageId, now))

        // ── One of each shape, left to right, the star rotated so a rotation is on the glass ──
        var shapeOrder = dao.maxOrder(pageId, SoilSchema.TYPE_SHAPE) + 1
        var cx = LEFT + SHAPE_EDGE / 2f
        var rowTop = SHAPES_TOP
        for (type in ShapeType.entries) {
            val long = type == ShapeType.LINE || type == ShapeType.ARROW
            val width = if (long) LONG_EDGE else SHAPE_EDGE
            val height = if (long) 1f else SHAPE_EDGE
            // Wrap to a second row rather than run off the page (the Nomad is 1404 px wide).
            if (cx - SHAPE_EDGE / 2f + width > pageWidth - LEFT) {
                cx = LEFT + SHAPE_EDGE / 2f
                rowTop += SHAPE_EDGE * 1.5f
            }
            val shape = PageShape(
                id = UUID.randomUUID().toString(), type = type,
                cx = cx + (width - SHAPE_EDGE) / 2f, cy = rowTop + SHAPE_EDGE / 2f,
                width = width, height = height,
                strokeWidth = ShapeRows.DEFAULT_STROKE_WIDTH_PX,
                rotationDeg = if (type == ShapeType.STAR) STAR_ROTATION_DEG else 0f,
                aspectLocked = !long,
                pointCount = ShapeFlags.DEFAULT_POINTS,
                order = shapeOrder++,
            )
            dao.upsert(ShapeRows.toRow(shape, pageId, now))
            cx += width + SHAPE_EDGE / 2f
        }

        // ── One sticky note with content, so the "content never draws on the page" rule is
        //    visible as a rule and not as an empty note ──
        val iconEdge = StickyRows.ICON_DP * density
        val sticky = PageSticky(
            id = UUID.randomUUID().toString(),
            x = LEFT, y = STICKY_TOP, width = iconEdge, height = iconEdge,
            contentW = 600, contentH = 800,
            order = dao.maxOrder(pageId, SoilSchema.TYPE_STICKY) + 1,
        )
        dao.upsert(StickyRows.toRow(sticky, pageId, now))
        // Children live in the note's LOCAL space — (0,0) is the content's top-left, never the page's.
        sampleInk().forEachIndexed { i, stroke ->
            dao.upsert(StrokeRows.toRow(stroke, sticky.id, i, now))
        }
    }

    /** Three short local-space strokes: a note that reads as written in, not as a placeholder. */
    private fun sampleInk(): List<Stroke> = listOf(
        line(60f, 120f, 480f, 120f),
        line(60f, 220f, 380f, 220f),
        line(60f, 320f, 300f, 400f),
    )

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Stroke {
        val points = (0..8).map { i ->
            val t = i / 8f
            StrokePoint(x = x1 + (x2 - x1) * t, y = y1 + (y2 - y1) * t)
        }
        return Stroke(id = UUID.randomUUID().toString(), points = points, width = 3f)
    }
}
