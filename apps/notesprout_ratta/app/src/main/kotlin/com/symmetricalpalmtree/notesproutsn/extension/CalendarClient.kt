package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.extstore.lease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The host's client for the one calendar (arc 23 / Y1) — the pad's held bind on `ICalendar`, and
 * since Y4 literally the pad's: every rule (the pre-open on IO, the uid-bound store binder, `begin`
 * under the call budget, the two-boolean Intent, the chunked send whose last chunk is settled rather
 * than believed, the drain under [TransferCaps.Drain], the settle-then-`end`-then-unbind-and-revoke
 * teardown) lives once in [HeldInkClient].
 *
 * All that is the calendar's own is [Point] — and the one shape difference the seam has: the pad's
 * placement is an `Int`, the calendar's is a real [CalendarTarget], which rides **every** chunk.
 */
class CalendarClient(context: Context, ref: ProviderRef) :
    HeldInkClient<ICalendar, CalendarTarget>(context, ref, Point) {

    /** The calendar's names and budgets — the whole of what makes a [HeldInkClient] this point's. */
    companion object Point : HeldInkPoint<ICalendar, CalendarTarget> {

        const val TAG = "CalendarClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /** The last `receiveInk` chunk — the extension places the whole transfer inside this call.
         *  The pad's number, kept after Y3 measured 119 ms for 19 strokes on the Nomad. */
        const val PLACE_TIMEOUT_MS = 10_000L

        /** How long `finish` waits for a call a timeout orphaned before it tears the bind down —
         *  the placement budget again: a placement that has not returned in twice its budget is a
         *  hung extension, and the store is revoked under it as the lesser harm. */
        const val SETTLE_TIMEOUT_MS = PLACE_TIMEOUT_MS

        override val tag: String get() = TAG
        override val serviceAction: String get() = ExtensionContract.ACTION_CALENDAR
        override val screenAction: String get() = ExtensionContract.ACTION_CALENDAR_SCREEN
        override val sendEnabledExtra: String get() = ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED
        override val openReceivedExtra: String get() = ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED
        override val callTimeoutMs: Long get() = CALL_TIMEOUT_MS
        override val placeTimeoutMs: Long get() = PLACE_TIMEOUT_MS
        override val settleTimeoutMs: Long get() = SETTLE_TIMEOUT_MS

        override fun asInterface(binder: IBinder): ICalendar? = ICalendar.Stub.asInterface(binder)

        override fun begin(iface: ICalendar, store: IExtensionStore) = iface.begin(store)

        /** The [placement] is the page the ink lands on, and it rides every chunk: the extension
         *  refuses a transfer whose target changes mid-way, so one target is built and reused. */
        override fun receiveInk(iface: ICalendar, chunk: InkBundle, placement: CalendarTarget, last: Boolean) =
            iface.receiveInk(chunk, placement, last)

        override fun takeOutgoing(iface: ICalendar, chunkIndex: Int): InkBundle =
            iface.takeOutgoing(chunkIndex)

        override fun end(iface: ICalendar) = iface.end()

        /** The page a parked page-send or an export request came from (arc 31 / HV4). The calendar
         *  is the only point that has one — and only from API 9; an older one is simply not asked
         *  (the host reads a target only on a result code an older calendar never returns). */
        override fun outgoingTarget(iface: ICalendar): CalendarTarget? = iface.outgoingTarget()

        /** The next parked half of a Day send (arc 35 / HA1) — asked only of a calendar declaring
         *  API 10; the entry gates the call on the version, this is the call itself. */
        override fun advanceOutgoing(iface: ICalendar): Boolean = iface.advanceOutgoing()

        /** One page of paper on the **held** bind (arc 31 / HV5) — the whole-page send's grid. The
         *  store is the showing's own, handed straight back to the extension that lent it its use;
         *  [render] below is the same call bind-per-call, for the Export screen. */
        override fun render(
            iface: ICalendar,
            store: IExtensionStore,
            target: CalendarTarget,
            widthPx: Int,
            heightPx: Int,
            flags: Int,
            destination: ParcelFileDescriptor,
        ) = iface.render(store, arrayOf(target), widthPx, heightPx, flags, destination)

        /** Drawing a page is not a state read: the measured budget the Export screen's render
         *  takes, for the same work. */
        override val renderTimeoutMs: Long get() = ExtensionContract.CALENDAR_RENDER_TIMEOUT_MS

        override fun describe(placement: CalendarTarget): String =
            "target=${placement.kind}/${placement.date}/${placement.half}"

        /**
         * **Draw calendar pages into a file** (arc 31 / HV4) — the calendar's one bind-per-call
         * method, and the only thing on this point that is not the held showing.
         *
         * The tag manager's second call shape, to the line: the store is [ExtensionStores.lease]d
         * on IO **before** the bind (a cold KDF must never sit inside a call budget), it rides the
         * one call, and it is revoked in `finally` whatever happened. The bind is
         * [ExtensionBinder.call]'s — signature re-checked at bind, the call on IO under
         * [ExtensionContract.CALENDAR_RENDER_TIMEOUT_MS], unbind in `finally`.
         *
         * [destination] is opened here, write-only, created and truncated, and **closed here** the
         * moment the transaction has been marshalled — the [ExporterClient.export] rule, for the
         * same reason: an fd handed across a Binder is duplicated on the far side, and the near
         * copy is this process's to let go of. What lands in the file is one `PageBundle` v1, one
         * page per target in order — **untrusted**, so the caller reads its header before trusting
         * a byte of it ([com.symmetricalpalmtree.notesproutsn.export.CalendarRender]).
         *
         * [widthPx] x [heightPx] is the page size for a target with no minted page; a minted one
         * keeps its own. [flags] is a mask of `RENDER_*`.
         *
         * Logs counts, flags and durations — and the targets' kind/date/half triple, which is what
         * a target *is* and no more: a date is not a secret, and nothing about a calendar's content
         * is in one.
         *
         * @throws ExtensionCallException the store, the bind, the destination or the call failed.
         */
        suspend fun render(
            context: Context,
            ref: ProviderRef,
            targets: List<CalendarTarget>,
            widthPx: Int,
            heightPx: Int,
            flags: Int,
            destination: File,
        ) {
            val appContext = context.applicationContext
            val store = ExtensionStores.lease(appContext, ref.packageName, TAG)
                ?: throw ExtensionCallException("store unavailable")
            val t0 = System.currentTimeMillis()
            try {
                val pfd = try {
                    withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(
                            destination,
                            ParcelFileDescriptor.MODE_WRITE_ONLY or
                                ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE,
                        )
                    }
                } catch (e: IOException) {
                    // Never the path: the host's own cache directory is not the user's business
                    // and not a log line's either.
                    throw ExtensionCallException("the render destination would not open", e)
                }
                try {
                    ExtensionBinder.call(
                        appContext, ref, ExtensionContract.ACTION_CALENDAR, TAG,
                        asInterface = { ICalendar.Stub.asInterface(it) },
                        callTimeoutMs = ExtensionContract.CALENDAR_RENDER_TIMEOUT_MS,
                    ) { iface -> iface.render(store, targets.toTypedArray(), widthPx, heightPx, flags, pfd) }
                } finally {
                    runCatching { pfd.close() }
                }
                Slog.d(TAG) {
                    "render: ${targets.size} target(s) [${targets.joinToString { describe(it) }}] " +
                        "flags=$flags size=${widthPx}x$heightPx → ${destination.length()} bytes " +
                        "in ${System.currentTimeMillis() - t0} ms"
                }
            } finally {
                store.revoke()
            }
        }
    }
}
