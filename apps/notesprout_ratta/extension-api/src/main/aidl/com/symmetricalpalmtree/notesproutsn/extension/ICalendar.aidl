package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget;
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle;
import android.os.ParcelFileDescriptor;

/**
 * The CALENDAR point (arc 23 / Y1) -- SN's SEVENTH capability point, the fourth screen-owning one and
 * the second with paper. IScratchPad's four methods with the placement made a real type: the
 * extension owns a Month/Week/Day organizer screen (action ACTION_CALENDAR_SCREEN) the host launches
 * for a result; the host HOLDS one bind on this service for the screen's whole showing (begin ->
 * launch -> result -> end -> unbind), and every byte of ink crosses through these methods -- never
 * through the Intent. Every method: HostCallerCheck.enforce first. Timeouts are the host's.
 */
interface ICalendar {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** Notebook -> calendar: one chunk of the inbound ink; [target] (the page it lands on -- a month,
     *  a week, or one half of a day) + [last] on every chunk. The extension appends chunks until
     *  last == true, then places them on the target page, minting its rows if it has none, and marks
     *  them "open selected" for the next screen launch. The only failure is
     *  IllegalStateException("store unavailable"). */
    void receiveInk(in InkBundle chunk, in CalendarTarget target, boolean last);

    /** Calendar -> notebook: after RESULT_CALENDAR_SEND the host drains the outbound ink chunk by
     *  chunk; an empty bundle (0 strokes) means done. */
    InkBundle takeOutgoing(int chunkIndex);

    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. */
    void end();

    // ── Appended at arc 31 / HV4 under API 9 (ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_RENDER).
    // Both sit AFTER end() so every earlier transaction code is unchanged: a calendar declaring 7
    // or 8 is bound for the four above and never asked these two.

    /**
     * Paint [targets] as finished pages into one PageBundle v1 on [destination] (WEBP q100, RGB_565, one
     * page per target in order; no links). Bind-per-call: [store] is lent for this call alone (the
     * tag manager's shape) — it is NOT the held showing's store, and a render on the held bind is
     * handed the same binder the bind already holds. [widthPx] x [heightPx] is the page size for
     * a target with no minted page; a minted page keeps its own stored size. [flags] is a mask of
     * ExtensionContract.RENDER_GRID / RENDER_INK / RENDER_RING / RENDER_MARKS — insets 0, a full-page
     * ruling. Refusals cross as IllegalArgumentException (no targets, over RENDER_MAX_TARGETS,
     * a flag outside RENDER_ALL, a size outside 1..PageBundle.MAX_DIMENSION_PX) and
     * IllegalStateException("store unavailable"); the host owns and closes [destination] and verifies the
     * bundle header before trusting a byte. Writes nothing to the store.
     */
    void render(IExtensionStore store, in CalendarTarget[] targets, int widthPx, int heightPx, int flags, in ParcelFileDescriptor destination);

    /**
     * The page a parked page-send (RESULT_CALENDAR_SEND from the top bar's Send page) or an export
     * request (RESULT_CALENDAR_EXPORT) came from — read on the held bind before end(). Null after
     * a selection send, and null when nothing is parked.
     */
    CalendarTarget outgoingTarget();
}
