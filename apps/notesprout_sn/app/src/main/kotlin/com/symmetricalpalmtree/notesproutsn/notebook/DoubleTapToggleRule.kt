package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * Whether a finger double-tap on the notebook toggles the chrome (arc 33 / F1), decided against
 * what the two taps that made it up landed on.
 *
 * `PageGestures` fires `onFingerTap` for **both** taps of a pair and `onFingerDoubleTap` last (the
 * second tap's single-tap escrow is posted before the double's), so by the time the double fires the
 * listener has already answered both taps: a sticky opened, a link followed, or nothing. This rule
 * keeps that answer as a **two-deep hit history** and says **toggle iff neither tap hit** — a note
 * or a link tapped twice is what the finger meant, and a link followed on tap 1 must never toggle
 * the page it landed on.
 *
 * Timing-free. A decision consumes the history, so a double that somehow arrives with only one
 * tap recorded is refused — worst case one refused toggle, never a wrong one; a stale entry ages
 * out by being overwritten.
 */
class DoubleTapToggleRule {

    private var previous: Boolean? = null
    private var latest: Boolean? = null

    /** A single finger tap was answered: [hit] = it opened a sticky or followed a link. */
    fun tapped(hit: Boolean) {
        previous = latest
        latest = hit
    }

    /** The double fired: toggle only when both recorded taps hit nothing. Consumes the history. */
    fun shouldToggle(): Boolean {
        val decision = previous == false && latest == false
        previous = null
        latest = null
        return decision
    }
}
