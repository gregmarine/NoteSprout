package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import com.symmetricalpalmtree.notesproutsn.ink.InkTransferSession
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

/**
 * `ICalendar.render` (arc 31 / HV4): calendar pages as **finished pixels** into one `PageBundle`
 * v1, off the Binder thread's back — no screen, no view, no g-paper engine. The host becomes a
 * fourth bundle producer with this, so PDF and PNG of a calendar view come from the exporters that
 * exist; the host paints nothing calendar-shaped itself (the audit row "the host computes no
 * calendar arithmetic" holds — the grid, the ring, the marks all stay here).
 *
 * Per target: the page's rows through [CalendarStore] (the store is the one the host lent for this
 * call — `open()` first, because the host's gate refuses a query on a binder that has not declared
 * its schema), the size by [RenderRequest.pageSize], a white RGB_565 ground, then by flag: the
 * ruling from [CalendarTemplate] at the **full page** (arc 33 / F4 — the screen draws the grid full
 * page under floating bars, so the render agrees with it) with today's ring only when asked and the
 * marks from [EventStore] over [GridMarks.rangeOf] only when asked; the ink through g-paper's own
 * [StrokeRasterizer] — the same door the host's export bakes endnotes through, so ink on a calendar
 * file is pixel-identical to ink on a notebook file.
 * Encoded WEBP q100 (the F5 recipe the host bakes with), one `writePage` per target.
 *
 * Reads only. Density is this process's display density — the page was sized under it. The two
 * inks come from resources like the screen's; the "Notes" label too.
 *
 * Failures: the store gone → `IllegalStateException("store unavailable")` (the seam's one text);
 * anything that is not an argument fault — an allocation, an encode, a write to the host's fd —
 * → `IllegalStateException("render failed")`, because an `IOException` or an `OutOfMemoryError`
 * does not cross Binder and the host would read the silence as success. Never a path, never a
 * stroke, never an event title in a message or a log.
 */
internal object CalendarRender {

    private const val TAG = "CalendarRender"

    fun render(context: Context, store: IExtensionStore, request: RenderRequest, destination: ParcelFileDescriptor) {
        val t0 = SystemClock.elapsedRealtime()
        val calendar = CalendarStore(store)
        val events = EventStore(store)
        try {
            calendar.open()   // declares the schema on this call's binder; reads the bookmark, unused
        } catch (e: StoreUnavailable) {
            throw IllegalStateException(InkTransferSession.STORE_UNAVAILABLE)
        }
        val density = context.resources.displayMetrics.density
        val palette = CalendarTemplate.Palette(
            ink = ContextCompat.getColor(context, R.color.inkBlack),
            light = ContextCompat.getColor(context, R.color.inkLight),
        )
        val notesLabel = context.getString(R.string.calendar_notes_label)
        val today: LocalDate? = if (request.ring) LocalDate.now() else null
        var strokes = 0
        try {
            BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(destination)).use { out ->
                val writer = PageBundle.Writer(out, request.targets.size)
                for (target in request.targets) {
                    val stored = if (request.ink) calendar.readPage(target) else calendar.readHeader(target)
                    val (w, h) = request.pageSize(stored.width, stored.height)
                    val marks = if (request.marks && request.grid) {
                        val (from, to) = GridMarks.rangeOf(target)
                        events.marksFor(from, to)
                    } else emptyMap()
                    val page = bake(target, w, h, density, palette, notesLabel, today, marks, stored, request)
                    strokes += stored.strokes.size
                    writer.writePage(w, h, page)
                }
                writer.close()
            }
        } catch (e: StoreUnavailable) {
            throw IllegalStateException(InkTransferSession.STORE_UNAVAILABLE)
        } catch (e: IllegalArgumentException) {
            throw e   // an argument fault of ours (a stored size out of range) — crosses as itself
        } catch (e: IOException) {
            Slog.d(TAG) { "render failed: ${e.javaClass.simpleName}" }
            throw IllegalStateException(RENDER_FAILED)
        } catch (e: OutOfMemoryError) {
            Slog.d(TAG) { "render failed: out of memory" }
            throw IllegalStateException(RENDER_FAILED)
        }
        Slog.d(TAG) {
            "render: ${request.targets.size} page(s), $strokes stroke(s), flags=${request.flags} in ${SystemClock.elapsedRealtime() - t0} ms"
        }
    }

    /** One page's pixels: white ground · ruling (by flag) · ink (by flag) → WEBP q100. */
    private fun bake(
        target: CalendarTarget,
        w: Int,
        h: Int,
        density: Float,
        palette: CalendarTemplate.Palette,
        notesLabel: String,
        today: LocalDate?,
        marks: Map<LocalDate, List<DayMark>>,
        stored: CalendarStore.StoredPage,
        request: RenderRequest,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            if (request.grid) {
                val ruling = when (target.kind) {
                    CalendarTarget.KIND_WEEK -> CalendarTemplate.week(
                        CalendarGeometry.week(w, h, density), target.localDate, today, density, palette, notesLabel, marks,
                    )
                    CalendarTarget.KIND_DAY -> CalendarTemplate.day(
                        CalendarGeometry.day(w, h, density), target.half, density, palette, marks[target.localDate].orEmpty(),
                    )
                    else -> CalendarTemplate.month(
                        CalendarGeometry.month(w, h, density), target.localDate, today, density, palette, notesLabel, marks,
                    )
                }
                try {
                    canvas.drawBitmap(ruling, null, Rect(0, 0, w, h), RULING_PAINT)
                } finally {
                    ruling.recycle()
                }
            }
            if (request.ink && stored.strokes.isNotEmpty()) {
                // The store answers in planned ranges; writing order is the pair's first.
                StrokeRasterizer.draw(canvas, stored.strokes.sortedBy { it.first }.map { it.second })
            }
            return toWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private val RULING_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

    /** The host's `BuiltInTemplates.toWebp`, six lines, inlined — this module has no host code. */
    private fun toWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        if (!bitmap.compress(format, 100, out)) throw IOException("encode failed")
        return out.toByteArray()
    }

    /** The one non-store failure text — an `IllegalStateException`, which survives Binder. */
    const val RENDER_FAILED = "render failed"
}
