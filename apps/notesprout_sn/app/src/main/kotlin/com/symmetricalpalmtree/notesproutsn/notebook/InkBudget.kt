package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke

/**
 * Fits a whole page of ink under the recognizer's per-call caps ([ExtensionContract.MAX_INK_STROKES]
 * / [ExtensionContract.MAX_INK_POINTS] — the ~1 MB Binder transaction budget, see `docs/extensions.md`)
 * so a dense page is recognized rather than refused.
 *
 * A full Manta page of firmware ink runs well past 60,000 points (the 2026-09-11 journal page:
 * a ~1 MB single-page `.soil`), and the host-side `InkCaps` check threw before the bind — which
 * the document seed could only report as "recognition isn't available". Two moves, in this order:
 *
 *  1. **Chunk by stroke count**, in writing order — a page over `MAX_INK_STROKES` becomes
 *     several `recognizePage` calls whose texts the caller joins with `\n`. Writing order is line
 *     order on a page of handwriting, so a chunk boundary falls between lines far more often than
 *     inside one. Rare: 2,000 strokes is many pages of prose.
 *  2. **Decimate points** within each chunk that is over `MAX_INK_POINTS`: every stroke keeps its
 *     first point, every `stride`-th point after it, and its last point. ML Kit resamples the
 *     polyline internally, so dropping intermediate samples of a 300 Hz pen stream leaves the
 *     shape the recognizer sees unchanged. The stride is the smallest that fits (a stroke's
 *     endpoints can push the first estimate over, so it is raised until the chunk fits).
 *
 * Pure, JVM-tested. Never throws: a chunk this produces always passes `InkCaps.check` (given
 * well-formed input — `InkStroke` itself rejects the malformed).
 */
object InkBudget {

    /** [strokes] as one or more lists, each within [maxStrokes] strokes and [maxPoints] points. */
    fun fit(
        strokes: List<InkStroke>,
        maxStrokes: Int = ExtensionContract.MAX_INK_STROKES,
        maxPoints: Int = ExtensionContract.MAX_INK_POINTS,
    ): List<List<InkStroke>> {
        if (strokes.isEmpty()) return emptyList()
        require(maxStrokes > 0 && maxPoints > 0) { "non-positive budget" }
        return strokes.chunked(maxStrokes).map { fitPoints(it, maxPoints) }
    }

    /** The whole [strokes] list unchanged when it fits, else decimated with the smallest stride that does. */
    private fun fitPoints(strokes: List<InkStroke>, maxPoints: Int): List<InkStroke> {
        val total = strokes.sumOf { it.size.toLong() }
        if (total <= maxPoints) return strokes
        var stride = ((total + maxPoints - 1) / maxPoints).toInt().coerceAtLeast(2)
        while (true) {
            val out = strokes.map { decimate(it, stride) }
            if (out.sumOf { it.size.toLong() } <= maxPoints) return out
            stride++
        }
    }

    /** First point, every [stride]-th after it, and the last point; a stroke of ≤ 2 points is kept whole. */
    internal fun decimate(stroke: InkStroke, stride: Int): InkStroke {
        val n = stroke.size
        if (stride <= 1 || n <= 2) return stroke
        val last = n - 1
        // Indices 0, stride, 2·stride, … below `last`, then `last` itself.
        val kept = (last - 1) / stride + 1 + 1
        val x = FloatArray(kept)
        val y = FloatArray(kept)
        var i = 0
        var k = 0
        while (i < last) {
            x[k] = stroke.x[i]
            y[k] = stroke.y[i]
            k++
            i += stride
        }
        x[k] = stroke.x[last]
        y[k] = stroke.y[last]
        return InkStroke(x, y)
    }
}
