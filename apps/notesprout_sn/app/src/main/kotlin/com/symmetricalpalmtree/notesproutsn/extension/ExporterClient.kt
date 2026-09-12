package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * Bind-per-operation client for one notebook exporter (arc 15 / E1), over [ExtensionBinder.call]
 * (signature re-check at bind, bind ≤ 3 s, call on IO under a per-method timeout, unbind in
 * `finally`, every failure → one [ExtensionCallException]). Stateless point — no store; an export
 * is one operation. **A per-page export is one operation too** (arc 34 / M9a): [hold] binds once
 * and the loop runs [Held.export] per page over the same connection — the same per-call timeout,
 * the same fd rule — instead of a bind + unbind per page, which on a long notebook was most of the
 * loop's time and a `bindService` churn the system had no reason to see.
 *
 * **Inward is untrusted, and unmarshal is the validation**: a descriptor or result over the
 * [ExporterContract] caps fails in its parcelable constructor inside the call and surfaces as
 * [ExtensionCallException] — the caller drops that exporter with a log line, never crashes.
 *
 * **fd lifecycle** (the ashmem handshake's shape in `ParcelFileDescriptor` clothes): [export]
 * takes ownership of both descriptors and closes them in `finally` once the transaction has been
 * marshalled — success, failure, or timeout; the extension closes its own dups on its side. The
 * host verifies size-written against the artifact before claiming success — that check is the
 * caller's, on the [ExportResult] this returns.
 *
 * Logs (tag [TAG]): bind/unbind, byte counts and durations — never a name, never a path.
 */
class ExporterClient(context: Context, private val ref: ProviderRef) {

    private val appContext = context.applicationContext

    /** The exporter's descriptor. Fast; a malformed one throws [ExtensionCallException]. */
    suspend fun describe(): ExporterInfo =
        call(ExporterContract.DESCRIBE_TIMEOUT_MS) { it.describe() }

    /**
     * Run the export: [source] (the host-prepared artifact, opened read-only) streamed to
     * [destination] (the SAF-created document, opened for writing). Both descriptors are closed
     * here in `finally`. Returns the extension's byte count — verify it before toasting.
     */
    suspend fun export(
        source: ParcelFileDescriptor,
        destination: ParcelFileDescriptor,
        spec: ExportSpec,
    ): ExportResult {
        val t0 = System.currentTimeMillis()
        try {
            val result = call(ExporterContract.EXPORT_TIMEOUT_MS) { it.export(source, destination, spec) }
            Slog.d(TAG) { "export: ${result.bytesWritten} bytes in ${System.currentTimeMillis() - t0} ms" }
            return result
        } finally {
            runCatching { source.close() }
            runCatching { destination.close() }
        }
    }

    /**
     * One bind, held across a per-page loop (arc 34 / M9a). The caller owns the [Held] and
     * [Held.close]s it in `finally`; a bind that fails throws [ExtensionCallException] with nothing
     * to close, exactly as a failed [export] would.
     */
    suspend fun hold(): Held =
        Held(
            ExtensionBinder.hold(
                appContext, ref, ExporterContract.ACTION_NOTEBOOK_EXPORTER, TAG,
                asInterface = { INotebookExporter.Stub.asInterface(it) },
            ),
        )

    /** [export] over a held bind — [ExporterContract.EXPORT_TIMEOUT_MS] per call, both descriptors
     *  closed in `finally`, every failure one [ExtensionCallException]. */
    class Held internal constructor(private val binding: ExtensionBinder.HeldBinding<INotebookExporter>) {

        suspend fun export(
            source: ParcelFileDescriptor,
            destination: ParcelFileDescriptor,
            spec: ExportSpec,
        ): ExportResult {
            val t0 = System.currentTimeMillis()
            try {
                val result = binding.call(ExporterContract.EXPORT_TIMEOUT_MS) { it.export(source, destination, spec) }
                Slog.d(TAG) { "export (held): ${result.bytesWritten} bytes in ${System.currentTimeMillis() - t0} ms" }
                return result
            } finally {
                runCatching { source.close() }
                runCatching { destination.close() }
            }
        }

        /** Unbind; idempotent. */
        fun close() = binding.close()
    }

    private suspend fun <T> call(timeoutMs: Long, block: (INotebookExporter) -> T): T =
        ExtensionBinder.call(
            appContext, ref, ExporterContract.ACTION_NOTEBOOK_EXPORTER, TAG,
            asInterface = { INotebookExporter.Stub.asInterface(it) },
            callTimeoutMs = timeoutMs,
            block = block,
        )

    companion object {
        private const val TAG = "ExporterClient"
    }
}
