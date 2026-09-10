package com.symmetricalpalmtree.notesproutsn.notebook

import android.database.SQLException
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** [PageOpFailure] — what the host's `runPageOp` does with what a page op threw (arc 34 / M7). */
class PageOpFailureTest {

    @Test
    fun `a cancellation is rethrown, never logged as a failure`() {
        assertEquals(PageOpFailure.Outcome.RETHROW, PageOpFailure.classify(CancellationException("closing")))
        // Also when it is wrapped — a cancelled child surfaces as its own cancellation.
        assertEquals(PageOpFailure.Outcome.RETHROW, PageOpFailure.classify(CancellationException("outer", CancellationException("inner"))))
    }

    @Test
    fun `a database or disk failure is a dialog — the tap that did nothing must say so`() {
        assertEquals(PageOpFailure.Outcome.DIALOG, PageOpFailure.classify(SQLiteException("disk I/O error")))
        assertEquals(PageOpFailure.Outcome.DIALOG, PageOpFailure.classify(SQLException("database is locked")))
        assertEquals(PageOpFailure.Outcome.DIALOG, PageOpFailure.classify(IOException("ENOSPC")))
        // Wrapped by whatever ran it (a transaction helper, a coroutine boundary): the cause counts.
        assertEquals(PageOpFailure.Outcome.DIALOG, PageOpFailure.classify(RuntimeException("op", SQLiteException("full"))))
        assertEquals(PageOpFailure.Outcome.DIALOG, PageOpFailure.classify(IllegalStateException("op", IOException("gone"))))
    }

    @Test
    fun `anything else is logged`() {
        assertEquals(PageOpFailure.Outcome.LOG, PageOpFailure.classify(IllegalStateException("bug")))
        assertEquals(PageOpFailure.Outcome.LOG, PageOpFailure.classify(NullPointerException()))
        assertEquals(PageOpFailure.Outcome.LOG, PageOpFailure.classify(RuntimeException("a", RuntimeException("b"))))
    }

    @Test
    fun `a cause chain that loops still terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(PageOpFailure.Outcome.LOG, PageOpFailure.classify(a))
    }
}
