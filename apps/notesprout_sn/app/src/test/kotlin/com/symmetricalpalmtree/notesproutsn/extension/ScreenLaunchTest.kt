package com.symmetricalpalmtree.notesproutsn.extension

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScreenLaunch] — the instant between a good `begin` and the screen (arc 34 / M8). */
class ScreenLaunchTest {

    private class Trace {
        val steps = ArrayList<String>()
        fun step(s: String): () -> Unit = { steps += s }
    }

    @Test
    fun `a launch that lands releases the pipeline first and never re-arms it`() {
        val t = Trace()
        val outcome = ScreenLaunch.attempt(
            resolves = { true }, beforeLaunch = t.step("release"), launch = t.step("launch"), afterFailure = t.step("re-arm"),
        )
        assertEquals(ScreenLaunch.Outcome.Launched, outcome)
        assertEquals(listOf("release", "launch"), t.steps)
    }

    @Test
    fun `an Intent that does not resolve is found out before the pipeline goes over`() {
        val t = Trace()
        val outcome = ScreenLaunch.attempt(
            resolves = { false }, beforeLaunch = t.step("release"), launch = t.step("launch"), afterFailure = t.step("re-arm"),
        )
        assertEquals(ScreenLaunch.Outcome.Unresolved, outcome)
        assertTrue("nothing ran", t.steps.isEmpty())
    }

    @Test
    fun `a launch the system refuses re-arms what was released — no crash, no leak`() {
        for (refusal in listOf(ActivityNotFoundException("gone"), SecurityException("not exported"))) {
            val t = Trace()
            val outcome = ScreenLaunch.attempt(
                resolves = { true }, beforeLaunch = t.step("release"), launch = { throw refusal }, afterFailure = t.step("re-arm"),
            )
            assertTrue("was $outcome", outcome is ScreenLaunch.Outcome.Refused && outcome.cause === refusal)
            assertEquals(listOf("release", "re-arm"), t.steps)
        }
    }

    @Test
    fun `anything else a launch throws is a bug and propagates`() {
        val t = Trace()
        val thrown = runCatching {
            ScreenLaunch.attempt(
                resolves = { true }, beforeLaunch = t.step("release"), launch = { throw IllegalStateException("bug") }, afterFailure = t.step("re-arm"),
            )
        }.exceptionOrNull()
        assertTrue("was $thrown", thrown is IllegalStateException)
        assertEquals(listOf("release"), t.steps)
    }
}
