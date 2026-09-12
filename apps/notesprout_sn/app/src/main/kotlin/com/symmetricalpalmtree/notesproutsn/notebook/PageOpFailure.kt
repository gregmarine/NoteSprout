package com.symmetricalpalmtree.notesproutsn.notebook

import android.database.SQLException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * What the host's `runPageOp` does with what a page op threw (arc 34 / M7) — pure, so the
 * dispatch is pinned by `PageOpFailureTest` without an Activity.
 *
 * Before M7 the op was `runCatching { … }.onFailure { Log.w }`: a `CancellationException` was
 * logged as a failure on every close, and a throwing Erase page / Delete page — a full disk, a
 * database that would not write — read as a confirmed tap that did nothing. The three answers:
 *
 * - [Outcome.RETHROW] — a cancellation is the coroutine's own business (the screen is closing);
 * - [Outcome.DIALOG] — a **store failure**: a database ([SQLException], which is both the
 *   framework's and SQLCipher's `SQLiteException` base) or the disk ([IOException]), anywhere in
 *   the cause chain — the `:ext-ink` screens' `StoreUnavailable` analogue, and the one case the
 *   person can act on (free space, try again). The host shows the plain "Couldn't change the
 *   page" dialog: nothing was saved, since every page op is one transaction or one drained write;
 * - [Outcome.LOG] — anything else is a bug, logged as before.
 */
object PageOpFailure {

    enum class Outcome { RETHROW, DIALOG, LOG }

    fun classify(t: Throwable): Outcome {
        if (t is CancellationException) return Outcome.RETHROW
        var cause: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is SQLException || cause is IOException) return Outcome.DIALOG
            cause = cause.cause
        }
        return Outcome.LOG
    }
}
