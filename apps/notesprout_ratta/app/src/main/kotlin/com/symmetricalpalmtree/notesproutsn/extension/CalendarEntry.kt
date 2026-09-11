package com.symmetricalpalmtree.notesproutsn.extension

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface

/**
 * The Calendar's entry button (arc 23 / Y1, its transfers Y3) — both doors, the library's and the
 * notebook's, on [ExtensionScreenEntry]: the pad's door shape, and since Y4 the pad's door itself.
 * Visibility and re-discovery, the busy latch, the "Opening…" wait, [beforeLaunch], both transfers'
 * host half and the held bind's life all live there, once.
 *
 * All that is the calendar's own is what is below: its registry lookup, its client (whose placement
 * is a real [CalendarTarget] where the pad's is an int), its four strings and its send result code.
 */
class CalendarEntry(
    activity: AppCompatActivity,
    button: View,
    /** True when this caller can receive ink back — the notebook (Y3). */
    sendEnabled: Boolean = false,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    beforeLaunch: () -> Unit = {},
    afterLaunchFailed: () -> Unit = {},
    /** An outbound send is across — fired **after** the last `receiveInk` returns, never at the tap. */
    onSent: () -> Unit = {},
    /** Ink the calendar sent back, already sanitized and capped; the bind is finished the moment this
     *  returns. */
    onDrained: suspend (List<DrainedInk>) -> Unit = {},
    /** The calendar closed asking for the page it was showing to be exported (arc 31 / HV4 —
     *  `RESULT_CALENDAR_EXPORT`). The caller opens the Export screen at that target and brings the
     *  calendar back when it comes home. */
    onExport: (CalendarTarget) -> Unit = {},
    /** The showing is over (Y4): `RESULT_CALENDAR_OPEN_SCRATCH_PAD` asks the caller to open the pad
     *  and bring the calendar back afterwards. */
    onClosed: (resultCode: Int) -> Unit = {},
) : ExtensionScreenEntry<ICalendar, CalendarTarget>(
    activity = activity,
    button = button,
    tag = TAG,
    surface = Surface.CALENDAR,
    discover = { ExtensionRegistry.calendar(it) },
    newClient = { context, ref -> CalendarClient(context, ref) },
    wording = WORDING,
    resultSend = ExtensionContract.RESULT_CALENDAR_SEND,
    sendEnabled = sendEnabled,
    beforeLaunch = beforeLaunch,
    afterLaunchFailed = afterLaunchFailed,
    onSent = onSent,
    onDrained = onDrained,
    // The calendar's own Scratch Pad door (Y4): it exists only when the host finds a trusted pad —
    // discovery is the host's, an extension never queries for another.
    decorateIntent = { ctx, ref, intent ->
        intent.putExtra(ExtensionContract.EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE, ExtensionRegistry.scratchPad(ctx) != null)
        // The calendar's Export door (arc 31 / HV4), by the arc-30 rule the page sheet's row
        // already keeps: **any** exporter installed. Whether one of them takes pages of a calendar
        // is the Export screen's own question, and it answers it with its own dialog rather than
        // leaving a button that lies about what is behind it. The version half is the seam's: only
        // a calendar declaring API 9 has a `render` to be asked for, so an older one never sees the
        // extra and shows no door. Both halves are IO, and this already runs in the entry's
        // coroutine.
        intent.putExtra(
            ExtensionContract.EXTRA_CALENDAR_EXPORT_ENABLED,
            ref.apiVersion >= ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_RENDER &&
                ExtensionRegistry.exporters(ctx).isNotEmpty(),
        )
    },
    resultExport = ExtensionContract.RESULT_CALENDAR_EXPORT,
    onExport = onExport,
    // A whole-page send comes home with the calendar's grid as well as its ink (arc 31 / HV5): the
    // notebook lands a NEW page papered with it. The calendar is the one point that can draw a
    // page, and only from API 9 — the entry checks that itself before it asks.
    paperOnPageSend = true,
    onClosed = onClosed,
) {

    private companion object {
        const val TAG = "CalendarEntry"
        val WORDING = EntryWording(
            failedTitleRes = R.string.calendar_failed_title,
            failedBodyRes = R.string.calendar_failed_body,
            drainFailedTitleRes = R.string.calendar_drain_failed_title,
            drainFailedBodyRes = R.string.calendar_drain_failed_body,
            receivingRes = R.string.calendar_receiving,
            exportFailedTitleRes = R.string.calendar_export_failed_title,
            exportFailedBodyRes = R.string.calendar_export_failed_body,
        )
    }
}
