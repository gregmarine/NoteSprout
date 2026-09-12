package com.symmetricalpalmtree.notesproutsn.extension

import android.content.ActivityNotFoundException

/**
 * The instant between a successful `begin` and an extension screen's launch, as one pure sequence
 * (arc 34 / M8) — pinned by `ScreenLaunchTest` without an Activity.
 *
 * `HeldInkClient.open` validates the **service**; the screen's Intent is another component, and
 * the system can still refuse it (`ActivityNotFoundException` — the package replaced or disabled
 * between the bind and the launch; `SecurityException` — an export or permission the host does not
 * hold). Before M8 that escaped the open coroutine **after** `beforeLaunch()` had handed the EPD
 * pipeline over: a host crash, with the extension's `begin`ned showing leaked.
 *
 * Two rules: the Intent is [resolves]d **before** [beforeLaunch], so the common refusal never
 * releases the pen at all; and a refusal thrown by [launch] runs [afterFailure] — the caller's
 * re-arm, exactly what its result path would have done — and is answered as [Outcome.Refused] for
 * the caller's ordinary failure road (bind closed, latch cleared, box down, dialog, re-discovery).
 * Anything else [launch] throws is a bug and propagates as before.
 */
object ScreenLaunch {

    sealed interface Outcome {
        object Launched : Outcome
        /** [resolves] said no — nothing was released. */
        object Unresolved : Outcome
        /** [launch] threw a refusal — [afterFailure] has run. */
        class Refused(val cause: Exception) : Outcome
    }

    inline fun attempt(
        resolves: () -> Boolean,
        beforeLaunch: () -> Unit,
        launch: () -> Unit,
        afterFailure: () -> Unit,
    ): Outcome {
        if (!resolves()) return Outcome.Unresolved
        beforeLaunch()
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            afterFailure()
            return Outcome.Refused(e)
        } catch (e: SecurityException) {
            afterFailure()
            return Outcome.Refused(e)
        }
        return Outcome.Launched
    }
}
