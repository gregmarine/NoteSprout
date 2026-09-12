package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.InkTransferSession

/**
 * Process-wide state shared by [CalendarService] (the host's held bind) and `CalendarActivity` (the
 * screen) — they live in the same process. Everything it holds and every rule it holds it under are
 * `:ext-ink`'s [InkTransferSession] since arc 23: the store binder from `begin`, the inbound ink
 * accumulating over `receiveInk` chunks under one monitor with the caps re-check and the page bound
 * by the first chunk, the outbound chunks for `takeOutgoing` and the one-shot "open selected"
 * record. `end()` clears it all. **Nothing here is ever written to disk by the extension itself** —
 * its data lives in the host store.
 *
 * The calendar's two type parameters: the placement is a real type, [CalendarTarget] (which every
 * chunk carries and which is unmarshal-validated), and the record is [CalendarStore.Received].
 *
 * **`recordInboundPageSize = false`** — the sender's page size is dropped, because a calendar page
 * is minted `0 × 0` and takes the screen's size the first time a screen shows it; the notebook
 * page's size is the notebook's. (The pad's answer is the other one, and that difference is the
 * parameter rather than a second copy of this class.)
 */
object CalendarSession : InkTransferSession<CalendarTarget, CalendarStore.Received>(recordInboundPageSize = false) {

    /**
     * The page a parked **whole-page** send or an Export request came from (arc 31 / HV4) — what
     * `ICalendar.outgoingTarget` answers on the bind the host still holds. Null after a selection
     * send (the host lands that on the page it is showing) and null when nothing is parked. Set by
     * the screen on the Main thread as it leaves, read on the Binder thread: volatile, like the
     * outbound chunks beside it.
     */
    @Volatile
    var outboundTarget: CalendarTarget? = null

    fun parkTarget(target: CalendarTarget?) {
        outboundTarget = target
    }

    /** One parked page beyond the first (arc 35 / HA1): its chunks, its size and the page they came
     *  from — what `park` + `parkTarget` set for the page the host drains first. */
    class OutboundPage(val chunks: List<List<WireStroke>>, val width: Float, val height: Float, val target: CalendarTarget)

    /**
     * The pages parked **after** the one `takeOutgoing` / `outgoingTarget` currently answer for
     * (arc 35 / HA1 — a Day send parks both halves, AM first). Filled by the screen on Main as it
     * leaves, consumed on the Binder thread by [advance]; the monitor covers both.
     */
    private val queue = ArrayDeque<OutboundPage>()

    /** Park [pages] behind the current one, in landing order. Replaces anything queued. */
    @Synchronized
    fun queueAfterCurrent(pages: List<OutboundPage>) {
        queue.clear()
        queue.addAll(pages)
    }

    /** `ICalendar.advanceOutgoing`: move the next queued page into the base fields the host reads;
     *  false — and nothing changed — when none is queued. */
    @Synchronized
    fun advance(): Boolean {
        val next = queue.removeFirstOrNull() ?: return false
        park(next.chunks, next.width, next.height)
        outboundTarget = next.target
        return true
    }

    /** How many pages are still queued behind the current one — for logs and tests. */
    val queuedCount: Int @Synchronized get() = queue.size

    @Synchronized
    override fun clear() {
        super.clear()
        outboundTarget = null
        queue.clear()
    }
}
