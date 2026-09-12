package com.symmetricalpalmtree.notesproutsn.ext.image

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.extension.ExportResult
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.INotebookExporter

/**
 * Notesprout SN's fourth exporter on arc 15's one `NOTEBOOK_EXPORTER` point (arc 31 / D1) — no new
 * capability point, and none was needed. Bound stateless, one call per bind
 * (`ExtensionBinder.call`), never a held binding: the operation is a single describe or a single
 * export, not a showing.
 *
 * **The seam: the host renders, the extension assembles.** An image exporter can never receive the
 * `.soil` — no key crosses an extension seam — so this service declares the page-bundle source kind
 * ([ImageDescriptor]) and the host bakes the page full-fidelity into a
 * [com.symmetricalpalmtree.notesproutsn.extension.PageBundle] container in its own cache. That
 * container arriving on the read fd is **the only inbound**: no `.soil`, no key, no path, no
 * notebook id ever reaches this process, and the only thing this process can write to is the
 * destination fd it was handed — the writes-nothing-to-disk rule, kept to the letter.
 *
 * **One page per call, always.** A PNG is one picture, so the descriptor declares per-page
 * delivery and the *host* owns the splitting: at a scope of more than one page it bakes once,
 * writes a one-page bundle per page, and calls this service once per page with a fresh destination
 * in a folder the user picked. Nothing about `export(source, destination, spec)` changes — which is
 * why the manifest declares API version 9: an older host would stream a whole notebook here and
 * read the first page back as the whole export, so it must skip this service at discovery instead.
 * [ImageAssembly] refuses a multi-page bundle as the backstop.
 *
 * **The output is a transform, not a copy.** A PNG's size is not the container's, so the verbatim
 * `bytesWritten == streamBytes` equality does not apply here and the host corroborates against the
 * destination's own answers instead. What this side owes is an **honest count**: what was actually
 * written to the destination stream, never a guess.
 *
 * **One option, executed on the other side** (arc 31 / D1 — [ImageDescriptor]): the page-template
 * toggle is the host's, because the bundle arrives as finished pixels and paper is either in a page
 * or was never in it. Nothing crosses for it but the value, so this side can see what was asked.
 * There is no password option and no export secret — see [ImageExportSpec] for why one arriving
 * anyway is refused rather than ignored.
 */
class ImageExporterService : Service() {

    private val binder = object : INotebookExporter.Stub() {

        // No keying: the trio is `.soil`-specific and the device key is the host's business. The
        // descriptor itself lives in ImageDescriptor, where a JVM test can pin its shape.
        override fun describe(): ExporterInfo {
            enforce()
            return ImageDescriptor.info()
        }

        /**
         * The export: a one-page bundle in, a PNG out, one bitmap in memory at a time — decode,
         * compress, recycle.
         *
         * The whole method is one `try`/`finally` around the two descriptors: they are this
         * process's dups and are closed here whatever happens — success, refusal or crash — because
         * a leaked fd on an e-ink device outlives the call that made it. The caller check is
         * **inside** the try for exactly that reason: a `SecurityException` thrown above it would
         * leak both. (The streams below take ownership too; closing a `ParcelFileDescriptor` twice
         * is a no-op.)
         *
         * **Only marshalable exceptions leave** ([SecurityException] / [IllegalArgumentException] /
         * [IllegalStateException]): anything else kills the transaction silently and the host reads
         * an empty reply as success. So every `IOException` becomes an `IllegalStateException`
         * naming the stage it failed in — never a path, never a page's content.
         */
        override fun export(
            source: ParcelFileDescriptor?,
            destination: ParcelFileDescriptor?,
            spec: ExportSpec?,
        ): ExportResult {
            try {
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                val asked = spec ?: throw IllegalArgumentException("no export spec")
                // Refused before a single byte is read: an option this build cannot act on, or a
                // secret nothing here ever asks for, would otherwise produce a file that is not the
                // one the user asked for and report it as a success.
                ImageExportSpec.require(asked.values, asked.exportSecret)
                return ExportResult(ImageAssembly.assemble(src, dst, TAG))
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                // Includes IOException and OutOfMemoryError: nothing but the three marshalable
                // shapes may cross, and the message carries a class name, never a payload.
                Log.w(TAG, "export failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("export failed (${e.javaClass.simpleName})")
            } finally {
                runCatching { source?.close() }
                runCatching { destination?.close() }
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "ImageExporter"
    }
}
