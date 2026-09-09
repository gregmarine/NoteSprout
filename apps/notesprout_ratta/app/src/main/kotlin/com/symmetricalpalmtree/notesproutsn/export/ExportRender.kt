package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.crypto.KeyResolver
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.symmetricalpalmtree.notesproutsn.notebook.PageLabels
import com.symmetricalpalmtree.notesproutsn.notebook.PagePreview
import com.symmetricalpalmtree.notesproutsn.notebook.PageRaster
import com.symmetricalpalmtree.notesproutsn.notebook.PageReads
import com.symmetricalpalmtree.notesproutsn.notebook.StickyRows
import com.symmetricalpalmtree.notesproutsn.notebook.StrokeRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * **The other thing that gets exported** (arc 18 / D1): every page of the notebook, baked
 * full-fidelity into one encoded image each and streamed as a [PageBundle] — what a
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.SOURCE_PAGES] exporter receives
 * through its read fd in place of the `.soil`.
 *
 * It exists because of the seam, not in spite of it: **the host renders, the extension assembles.**
 * An exporter that turns a notebook into a document (a PDF) could never receive the file itself —
 * no key crosses — so the one process that *can* read the notebook does the reading, and hands over
 * pixels. The "what a notebook is" question stays host-side for good: when a kind of page arrives
 * that draws differently, it draws differently here and no extension changes.
 *
 * The guards are [ExportOpen]'s — the family's one door, in the order that *is* the invariant (the
 * file is there, the file is not held, there is a key, the open is sealed in a `finally`), because
 * the same things are at stake here as in [ExportArtifact.prepare]: a live writer, a missing key, a
 * file that will not open. What is this file's own:
 *
 *  1. **Nothing is written** — not even `notebook_meta`'s `exportedAt`, which the soil path stamps
 *     because *that file* is the thing travelling. A PDF is not the notebook, and a render must not
 *     mutate what it renders.
 *  2. **Bake page by page** into [ExportArtifact.freshDir] — the same directory the soil copy uses
 *     and [ExportArtifact.clean] wipes, so one `finally` in the screen takes both artifacts away.
 *
 * **One page in memory at a time** is a rule, not an optimisation: a whole notebook of full-size
 * bitmaps is an OOM on a 3 GB device. Each page is allocated, drawn, encoded, appended and recycled
 * before the next one starts, and [PageBundle]'s API is shaped to make anything else awkward.
 *
 * The bake itself is the page as it stands on the glass, minus the chrome: white ground, the
 * template under everything (unless the exporter's page-template toggle says otherwise — arc 18 /
 * D2, the one option this render executes), then the [PagePreview] layering — the one place that
 * order is written down ([PagePreview.drawContent]), which since arc 28 also carries text objects,
 * shapes and sticky icons (never a note's content). No link chrome, no selection chrome; those are
 * the screen's furniture, not the page's content. Pixels are [Bitmap.Config.RGB_565] over an opaque ground and
 * WEBP lossy q100 ([BuiltInTemplates.toWebp] — the app's one measured encoder, the F5 finding),
 * at the **page's own** size and scale 1: a page authored on another panel keeps its own edge, and
 * the screen's size never enters this file. Those last two paragraphs' worth of drawing is
 * [PageRaster]'s since arc 31 / HV2 — page-to-template wants the same picture, and one recipe with
 * two readers is the only way it stays the same picture. What stays here is the *bake*: the scope,
 * the plan, the endnotes, the one-page-at-a-time loop and the template held across pages.
 *
 * **Endnotes (arc 28 / D7).** For an exporter that reads the version-2 bundle, every sticky note
 * with content becomes one more page after the notebook's: its strokes on white at the note's
 * content size, a caption strip under them, and two links in the bundle's trailer (icon → note,
 * caption → source page). [Endnotes] decides the numbering, sizes and links; this file draws
 * them, one page at a time like every other. Facing a version-1 exporter the pages carry their
 * icons and nothing more — the bundle written is exactly the arc-18 one.
 */
object ExportRender {

    private const val TAG = "ExportRender"

    /** Why a render could not produce a bundle. Each maps to one sentence on screen. */
    enum class Problem {
        /** A connection to this `.soil` is open in this process — never render under a live writer. */
        IN_USE,

        /** No key session (the process was killed and nothing has unlocked since). */
        NO_KEY,

        /** This notebook has its own passphrase and nothing in this process has typed it
         *  (arc 26 / U4) — not a missing key, one notebook that is still shut. */
        LOCKED,

        /** The `.soil` is missing or empty — the index row outlived its file. */
        MISSING,

        /** The file would not open, or would not read (wrong key, damaged). */
        UNREADABLE,

        /** No live page at all. [PageBundle] carries one page at least, and a document of nothing
         *  is not a document — so this is an honest refusal, never an empty file. */
        EMPTY,

        /** A page row carries no usable size (a damaged or foreign-written file) — a data problem,
         *  which must not wear [RENDER_FAILED]'s memory-or-space sentence: the user would free
         *  storage and retry forever against a file that never changes (the D3 review). */
        DAMAGED,

        /** More pages than [PageBundle.MAX_PAGES] — the container's own cap, refused with its own
         *  sentence for the same reason as [DAMAGED]. */
        TOO_LONG,

        /** A page would not allocate, draw, encode or write — out of memory, or out of space. */
        RENDER_FAILED,
    }

    sealed class Outcome {
        /** [file] lives in the cache dir; [bytes] is the bundle's length, for the log and nothing
         *  else — a page bundle's size is deliberately **not** what the destination ends up
         *  holding (see [ExportVerification]).
         *
         *  [pageTitles] is one entry per baked **notebook** page, in bundle order: the page's
         *  topmost heading by the Contents rule ([PageLabels.titleOf]), or null when it has none
         *  (arc 31 / HV1). It exists for the per-page delivery, which names every file after its
         *  own page — read here because the bake already holds each page's content, and reading it
         *  a second time would be a second full open. The endnote pages get **no entry**: a note is
         *  not a page anyone named, and a per-page exporter never sees one (it declares bundle
         *  version 1, so no endnote is planned at all). */
        class Ready(
            val file: File,
            val bytes: Long,
            val pageTitles: List<String?> = emptyList(),
        ) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    /**
     * Render [notebookId]'s pages into a bundle. IO throughout; never touches the UI, never logs a
     * name or a path. [progress] is called on IO **before** each page with (page number, page
     * count) so the screen can say which one is under the brush — it may suspend to hop to Main,
     * and a slow one only slows the render.
     *
     * [includeTemplate] false bakes the ink on white ground (the arc-18 / D2 toggle). It is the
     * host's answer to give because the bundle carries finished pixels: once a page is baked there
     * is no paper left in it for an extension to take out.
     *
     * [bundleVersion] is the exporter's own ceiling (`ExporterInfo.bundleVersion`): at
     * [PageBundle.VERSION] and above the sticky notes go out as endnotes; below it they stay
     * icons and the bundle is version 1.
     *
     * [pageIds] is the scope (arc 30 / PE2, [ExportScope.pageIds]): null bakes every page, a set
     * bakes those pages only — filtered **before** [plan], so the endnotes (collected from the
     * baked pages) and every count follow the page for free.
     */
    suspend fun render(
        context: Context,
        notebookId: String,
        includeTemplate: Boolean,
        progress: suspend (Int, Int) -> Unit,
        resolved: KeyResolver.Resolved? = null,
        bundleVersion: Int = PageBundle.VERSION_1,
        pageIds: Set<String>? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        // The bake's own failures are caught inside the open, not around it: they mean the *render*
        // failed, which is a different sentence from the file not opening — and the seal still runs.
        val opened = ExportOpen.readOnly(context, notebookId, "render", resolved) { db ->
            try {
                bake(context, db, notebookId, includeTemplate, bundleVersion, pageIds, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class and message only — a render failure's message can carry a path.
                Log.w(TAG, "page render failed: ${e.javaClass.simpleName}")
                Outcome.Failed(Problem.RENDER_FAILED)
            } catch (e: OutOfMemoryError) {
                // A page allocation that the per-page recycle could not save. Not a crash: the
                // notebook is untouched and the screen has a sentence for it.
                Log.w(TAG, "page render ran out of memory")
                Outcome.Failed(Problem.RENDER_FAILED)
            }
        }
        when (opened) {
            is ExportOpen.Opened.Read -> opened.value
            is ExportOpen.Opened.Blocked -> Outcome.Failed(problemOf(opened.guard))
        }
    }

    /** The family's guards in this render's own words — the bake's reasons, one for one. */
    private fun problemOf(guard: ExportOpen.Guard): Problem = when (guard) {
        ExportOpen.Guard.MISSING -> Problem.MISSING
        ExportOpen.Guard.IN_USE -> Problem.IN_USE
        ExportOpen.Guard.NO_KEY -> Problem.NO_KEY
        ExportOpen.Guard.LOCKED -> Problem.LOCKED
        ExportOpen.Guard.UNREADABLE -> Problem.UNREADABLE
    }

    /** One page as the bake takes it: identity, its **own** pixel size, and the paper under it. */
    class PageBake(val id: String, val widthPx: Int, val heightPx: Int, val templateId: String)

    /**
     * The page rows as the bake reads them — pure, so the three decisions that shape every exported
     * page are pinned by test rather than by eye: **display order** is the row order the DAO
     * already sorted by `"order"`; the size is the **page's own** authored size (the screen's never
     * enters this file, and a notebook written on another panel exports at the edge it was written
     * at); and blank paper is `""`, which is what an absent `refId` *means* in the format, not a
     * missing answer.
     *
     * Null when any row carries no usable size: a page that cannot be drawn at its own size would
     * have to be guessed at or dropped, and a document silently missing a page is worse than one
     * that refuses out loud.
     */
    fun plan(rows: List<SoilObjectEntity>): List<PageBake>? = rows.map { row ->
        val width = (row.width ?: 0f).toInt()
        val height = (row.height ?: 0f).toInt()
        if (width < 1 || height < 1) return null
        PageBake(row.id, width, height, row.refId.orEmpty())
    }

    /**
     * The bundle write. Throws on anything that means the bundle is not whole — a short write, an
     * allocation that failed: a truncated bundle must never reach the exporter, and
     * [PageBundle.Writer.close] enforces the same thing from its own side. The two *data* refusals
     * (an unsized page row, more pages than the container carries) return their own [Problem]s
     * instead of throwing, so they never wear the memory-or-space sentence.
     */
    private suspend fun bake(
        context: Context,
        db: SoilDatabase,
        notebookId: String,
        includeTemplate: Boolean,
        bundleVersion: Int,
        pageIds: Set<String>?,
        progress: suspend (Int, Int) -> Unit,
    ): Outcome {
        val dao = db.dao()
        val rows = ExportScope.pagesInScope(dao.childrenOfType(notebookId, SoilSchema.TYPE_PAGE), pageIds)
        if (rows.isEmpty()) return Outcome.Failed(Problem.EMPTY)
        // Each refusal keeps its own Problem — routing either through the generic render catch
        // would blame memory or space for a data problem (the D3 review).
        val pages = plan(rows) ?: return Outcome.Failed(Problem.DAMAGED)
        if (pages.size > PageBundle.MAX_PAGES) return Outcome.Failed(Problem.TOO_LONG)
        // The endnotes are planned before the first page is drawn: the bundle declares its page
        // count and its links up front, and both include the notes (D7).
        val endnotes = if (bundleVersion >= PageBundle.VERSION) {
            Endnotes.plan(endnoteSources(dao, pages), pages.size)
        } else {
            Endnotes.Plan(emptyList(), emptyList())
        }
        val total = pages.size + endnotes.notes.size
        if (total > PageBundle.MAX_PAGES) return Outcome.Failed(Problem.TOO_LONG)

        val bundle = File(ExportArtifact.freshDir(context), "$notebookId.pages")

        val metrics = context.resources.displayMetrics
        // One set for the whole bake: the pages are rendered one at a time on this coroutine, and
        // a Paint / Drawable is mutated as it draws (PagePreview.Paints). A sticky icon that will
        // not load costs the note icons, never the export.
        val paints = PagePreview.Paints.of(
            metrics.scaledDensity,
            runCatching { AppCompatResources.getDrawable(context, R.drawable.ic_sticker_2)?.mutate() }
                .getOrNull(),
        )
        // The pages of a notebook share one template row in the ordinary case, so the decode is
        // held across pages that want the same one and dropped the moment they do not — two
        // bitmaps at the high-water mark instead of one per page decoded again and again.
        var templateId: String? = null
        var template: Bitmap? = null
        // The writer owns the stream from the moment it is constructed — but not before: a
        // constructor that refuses (too many pages) or a header write that fails would otherwise
        // leave the fd open with nothing left holding it.
        val out = FileOutputStream(bundle)
        val bundleWriter = try {
            PageBundle.Writer(out, total, endnotes.links)
        } catch (e: Throwable) {
            runCatching { out.close() }
            throw e
        }
        // One entry per notebook page, in bundle order — the per-page delivery's filenames (arc
        // 31 / HV1). Filled from the content this bake already reads; never from a second open.
        val pageTitles = ArrayList<String?>(pages.size)
        try {
            bundleWriter.use { writer ->
                pages.forEachIndexed { index, page ->
                    progress(index + 1, total)
                    // White ground is the *absence* of the decode, not a decoded bitmap thrown
                    // away: a template the page will not carry must not cost the page's worth of
                    // memory on the way past (the one-page-at-a-time rule cuts both ways).
                    if (includeTemplate && page.templateId != templateId) {
                        template?.recycle()
                        template = null
                        templateId = page.templateId
                        template = PageRaster.decodeTemplate(dao, page.templateId)
                    }
                    val content = PageReads.content(dao, page.id)
                    pageTitles += PageLabels.titleOf(content)
                    val image = PageRaster.toWebp(
                        page.widthPx, page.heightPx, template, content, metrics.density, paints,
                    )
                    writer.writePage(page.widthPx, page.heightPx, image)
                }
                // The template is done with before the first note: a note has no paper.
                template?.recycle()
                template = null
                for (note in endnotes.notes) {
                    progress(note.page, total)
                    val strokes = dao.childrenOfType(note.stickyId, SoilSchema.TYPE_STROKE)
                        .mapNotNull { StrokeRows.toStroke(it) }
                    val image = bakeEndnote(note, strokes)
                    writer.writePage(note.widthPx, note.heightPx, image)
                }
            }
        } finally {
            template?.recycle()
        }
        val bytes = bundle.length()
        Slog.d(TAG) { "rendered ${pages.size} page(s) + ${endnotes.notes.size} endnote(s) into $bytes bytes" }
        return Outcome.Ready(bundle, bytes, pageTitles)
    }

    /**
     * Every sticky note with content, in **page order then z-order** — loose on the page first,
     * then each link's wrapped ones in link order (the draw order's walk, [PagePreview.drawContent]).
     * Icon-only rows: the notes' strokes are read one note at a time when its page is drawn,
     * never here. A note with no content ([SoilDao.stickyIdsWithContent]) gets no endnote.
     */
    internal suspend fun endnoteSources(dao: SoilDao, pages: List<PageBake>): List<Endnotes.Source> {
        val withContent = dao.stickyIdsWithContent().toHashSet()
        if (withContent.isEmpty()) return emptyList()
        val sources = ArrayList<Endnotes.Source>()
        pages.forEachIndexed { index, page ->
            val rows = ArrayList<SoilObjectEntity>(dao.stickiesOf(page.id))
            for (link in dao.linksOf(page.id)) rows += dao.childrenOfType(link.id, SoilSchema.TYPE_STICKY)
            for (row in rows) {
                if (row.id !in withContent) continue
                val sticky = StickyRows.toSticky(row) ?: continue
                sources += Endnotes.Source(
                    stickyId = sticky.id,
                    fromPage = index + 1,
                    iconL = sticky.x, iconT = sticky.y,
                    iconR = sticky.x + sticky.width, iconB = sticky.y + sticky.height,
                    contentW = sticky.contentW, contentH = sticky.contentH,
                    pageW = page.widthPx, pageH = page.heightPx,
                )
            }
        }
        return sources
    }

    /**
     * One endnote page: the note's strokes (local space — `(0,0)` is the content's top-left, which
     * is the bitmap's) on white, a hairline, then the caption strip. Same pixel recipe as a page
     * (RGB_565, WEBP q100), same recycle-before-the-next rule.
     */
    private fun bakeEndnote(note: Endnotes.Note, strokes: List<Stroke>): ByteArray {
        val w = note.widthPx
        val h = note.heightPx
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${w}x$h endnote would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipRect(0, 0, w, note.contentH)
            StrokeRasterizer.draw(canvas, strokes)
            canvas.restore()
            val top = note.contentH.toFloat()
            canvas.drawRect(0f, top, w.toFloat(), top + 1f, captionPaint)
            val metrics = captionPaint.fontMetrics
            val baseline = top + Endnotes.CAPTION_PX / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(Endnotes.caption(note.number, note.fromPage), Endnotes.CAPTION_INSET_PX, baseline, captionPaint)
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The caption strip's text and rule: black sans at [Endnotes.CAPTION_TEXT_PX]. */
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = Endnotes.CAPTION_TEXT_PX
        typeface = Typeface.SANS_SERIF
    }
}
