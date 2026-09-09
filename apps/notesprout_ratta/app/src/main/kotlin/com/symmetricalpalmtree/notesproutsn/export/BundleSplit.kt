package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * **One bundle in, one bundle per page out** (arc 31 / HV1) — the host's splitter for a
 * [com.symmetricalpalmtree.notesproutsn.extension.ExporterContract.DELIVERY_PER_PAGE] exporter.
 *
 * The render bakes **once** ([ExportRender], [DocumentPdfRender]) and this reads that bundle back
 * one page at a time, writing each page as its own **version-1, one-page** bundle. So the seam is
 * untouched: the exporter is handed exactly what its contract says it may receive — a bundle of one
 * page — and every existing exporter is unaffected.
 *
 * Three rules:
 *
 *  - **Any version in, version 1 out.** A v2 bundle's link trailer is *dropped*: links are jumps
 *    between pages of one document, and a one-page file has nowhere to jump to. The trailer is
 *    never even read — [PageBundle.Writer] emits version 1 whenever it has no links, so each part
 *    is byte-shaped exactly like the arc-18 bundle a v1 exporter has always opened.
 *  - **One page in memory at a time**, both sides, the container's own rule: a whole notebook of
 *    full-size images is an OOM on a 3 GB device, so a page is read, written and let go before the
 *    next one starts.
 *  - **The pixels are not touched.** Each part carries the page's own width, height and encoded
 *    bytes verbatim — the split is a re-frame of the container, never a re-encode.
 *
 * Pure over streams (JVM-tested); the [File] overload is the convenience the screen uses.
 */
object BundleSplit {

    /**
     * Split the bundle on [input] (owned and closed here) into one-page bundles.
     *
     * [sink] is asked for a fresh [OutputStream] per page — `(0-based index, page count)` — and
     * **the stream it returns is owned by this writer**, closed before the next page is asked for.
     * Returns the number of pages written, which is the source bundle's own page count.
     */
    fun split(input: InputStream, sink: (index: Int, pageCount: Int) -> OutputStream): Int =
        PageBundle.Reader(input).use { reader ->
            val count = reader.pageCount
            for (index in 0 until count) {
                val page = reader.readPage()
                // The writer owns the stream from construction on; a one-page bundle can only
                // refuse for a reason the source would already have refused for.
                PageBundle.Writer(sink(index, count), pageCount = 1).use { writer ->
                    writer.writePage(page.widthPx, page.heightPx, page.image)
                }
            }
            count
        }

    /**
     * [split] into [dir]: `page-1.pages` … `page-N.pages`, in the bundle's own page order, which is
     * the order the names and the destinations follow. The files are returned in that order; they
     * live wherever the caller says, which for the export flow is [ExportArtifact.DIR] — the one
     * cache directory the screen's `finally` wipes.
     */
    fun split(bundle: File, dir: File): List<File> {
        val parts = ArrayList<File>()
        bundle.inputStream().use { input ->
            split(input) { index, _ ->
                val part = File(dir, "page-${index + 1}.pages")
                parts += part
                FileOutputStream(part)
            }
        }
        return parts
    }
}
