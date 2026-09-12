package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composite's stroke-width margin (eye-check #7): `Stroke.bounds` is point-tight, rendered ink
 * overhangs it by half the width plus the cap, so the bitmap must be padded — and the renderer's
 * reuse check must expect the padded size, or a stale bitmap would be reused at the wrong offset.
 */
class LinkCompositeTest {

    private fun link(strokes: List<Stroke>, headings: List<Heading> = emptyList()) = PageLink(
        id = "l1", payload = "", chrome = LinkPayload.CHROME_NONE,
        x = 10f, y = 20f, width = 100f, height = 50f, order = 0,
        strokes = strokes, headings = headings,
    )

    private fun stroke(width: Float) = Stroke(
        id = "s", points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)), width = width,
    )

    @Test
    fun `pad is half the widest stroke plus a pixel of slop`() {
        assertEquals(3, LinkComposite.padOf(link(listOf(stroke(3f), stroke(1f)))))  // ceil(1.5)+1
        assertEquals(5, LinkComposite.padOf(link(listOf(stroke(8f)))))              // 4+1
    }

    @Test
    fun `a heading-only link needs no pad`() {
        val h = Heading(id = "h", text = "## T", level = 2, x = 0f, y = 0f, width = 60f, height = 30f, order = 0)
        assertEquals(0, LinkComposite.padOf(link(emptyList(), listOf(h))))
    }

    @Test
    fun `sizeOf is the bounds plus the pad on each side`() {
        val l = link(listOf(stroke(3f)))   // pad 3
        assertEquals(106 to 56, LinkComposite.sizeOf(l))
    }

    // ── Arc 28 (H1): a shape's outline overhangs its geometry too ────────────

    private fun shape(strokeWidth: Float) = PageShape(
        id = "sh", type = ShapeType.RECTANGLE, cx = 50f, cy = 40f, width = 30f, height = 20f,
        strokeWidth = strokeWidth, rotationDeg = 0f, aspectLocked = false,
        pointCount = ShapeFlags.DEFAULT_POINTS, order = 0,
    )

    @Test
    fun `a wrapped shape's outline width counts toward the pad`() {
        // The widest of ink and outline decides — a shape's path is a centre line, exactly like ink.
        assertEquals(4, LinkComposite.padOf(link(emptyList()).copy(shapes = listOf(shape(6f)))))
        assertEquals(
            5,
            LinkComposite.padOf(link(listOf(stroke(8f))).copy(shapes = listOf(shape(2f)))),
        )
        assertEquals(
            4,
            LinkComposite.padOf(link(listOf(stroke(2f))).copy(shapes = listOf(shape(6f)))),
        )
    }

    @Test
    fun `text and sticky boxes need no pad — they carry their own`() {
        val t = PageText(id = "t", text = "x", x = 0f, y = 0f, width = 40f, height = 20f, order = 0)
        val n = PageSticky(id = "n", x = 0f, y = 0f, width = 72f, height = 72f, contentW = 100, contentH = 100, order = 0)
        assertEquals(0, LinkComposite.padOf(link(emptyList()).copy(texts = listOf(t), stickies = listOf(n))))
    }
}
