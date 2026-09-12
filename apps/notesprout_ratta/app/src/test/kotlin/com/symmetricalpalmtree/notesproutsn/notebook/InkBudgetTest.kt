package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.InkCaps
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InkBudgetTest {

    /** A stroke of [n] points along a diagonal, so every point is distinguishable by value. */
    private fun stroke(n: Int, offset: Float = 0f) =
        InkStroke(FloatArray(n) { offset + it }, FloatArray(n) { offset + it * 2 })

    @Test
    fun emptyPageFitsAsNothing() {
        assertEquals(emptyList<List<InkStroke>>(), InkBudget.fit(emptyList()))
    }

    @Test
    fun aPageUnderBothCapsCrossesUntouchedInOneCall() {
        val strokes = List(10) { stroke(50, it.toFloat()) }
        val out = InkBudget.fit(strokes)
        assertEquals(1, out.size)
        // The very same objects — nothing was copied or resampled.
        for (i in strokes.indices) assertSame(strokes[i], out[0][i])
    }

    @Test
    fun aDensePageIsDecimatedToFitThePointCap() {
        // 200 strokes × 1,000 points = 200,000 points: over the 60,000 cap by more than 3×.
        val strokes = List(200) { stroke(1_000, it.toFloat()) }
        val out = InkBudget.fit(strokes)
        assertEquals(1, out.size)
        val chunk = out[0]
        assertEquals(200, chunk.size)
        assertTrue(chunk.sumOf { it.size } <= ExtensionContract.MAX_INK_POINTS)
        // What the recognizer receives is exactly what InkCaps lets through.
        InkCaps.check(chunk, 1920f, 2560f)
        // Endpoints survive on every stroke: the pen-down and pen-up positions bound the glyph.
        for (i in chunk.indices) {
            assertEquals(strokes[i].x[0], chunk[i].x[0], 0f)
            assertEquals(strokes[i].x[999], chunk[i].x.last(), 0f)
            assertEquals(strokes[i].y[999], chunk[i].y.last(), 0f)
        }
    }

    @Test
    fun theStrideIsTheSmallestThatFits() {
        // 3,000 points against a cap of 1,000: stride 3 keeps ~334 + endpoints per stroke — over on
        // three strokes (3 × 335 = 1,005), so the budget has to step to stride 4.
        val strokes = List(3) { stroke(1_000, it.toFloat()) }
        val out = InkBudget.fit(strokes, maxPoints = 1_000)
        assertEquals(1, out.size)
        val points = out[0].sumOf { it.size }
        assertTrue("$points", points <= 1_000)
        assertTrue("$points", points > 700)   // not thrown away wholesale — the smallest stride, not a huge one
    }

    @Test
    fun tooManyStrokesSplitIntoCallsInWritingOrder() {
        val strokes = List(4_500) { stroke(3, it.toFloat()) }
        val out = InkBudget.fit(strokes)
        assertEquals(listOf(2_000, 2_000, 500), out.map { it.size })
        // Writing order is preserved across the split: chunk k starts where chunk k-1 ended.
        assertEquals(0f, out[0][0].x[0], 0f)
        assertEquals(2_000f, out[1][0].x[0], 0f)
        assertEquals(4_000f, out[2][0].x[0], 0f)
        assertEquals(4_499f, out[2].last().x[0], 0f)
        out.forEach { InkCaps.check(it, 1920f, 2560f) }
    }

    @Test
    fun aChunkOverBothCapsIsSplitThenDecimated() {
        // 3,000 strokes × 100 points: two chunks (2,000 + 1,000), the first at 200,000 points.
        val strokes = List(3_000) { stroke(100, it.toFloat()) }
        val out = InkBudget.fit(strokes)
        assertEquals(listOf(2_000, 1_000), out.map { it.size })
        out.forEach { chunk ->
            assertTrue(chunk.sumOf { it.size } <= ExtensionContract.MAX_INK_POINTS)
            InkCaps.check(chunk, 1920f, 2560f)
        }
    }

    @Test
    fun decimateKeepsFirstEveryStrideThAndLast() {
        val s = stroke(10)
        val d = InkBudget.decimate(s, 3)
        assertArrayEquals(floatArrayOf(0f, 3f, 6f, 9f), d.x, 0f)
        assertArrayEquals(floatArrayOf(0f, 6f, 12f, 18f), d.y, 0f)
        // Where the last point is not on the stride it is still kept.
        assertArrayEquals(floatArrayOf(0f, 3f, 6f, 8f), InkBudget.decimate(stroke(9), 3).x, 0f)
        // A stride that skips everything in between keeps the endpoints.
        assertArrayEquals(floatArrayOf(0f, 9f), InkBudget.decimate(s, 100).x, 0f)
    }

    @Test
    fun tinyStrokesAreNeverDecimated() {
        // A dot and a two-point tick are already their own endpoints.
        val dot = stroke(1)
        val tick = stroke(2)
        assertSame(dot, InkBudget.decimate(dot, 5))
        assertSame(tick, InkBudget.decimate(tick, 5))
        // Stride 1 is no decimation at all.
        val s = stroke(50)
        assertSame(s, InkBudget.decimate(s, 1))
    }
}
