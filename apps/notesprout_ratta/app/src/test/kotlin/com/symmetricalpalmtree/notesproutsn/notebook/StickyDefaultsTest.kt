package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an inserted sticky lands and how big its note is (arc 28 / H5, decision 2 + D2): a
 * [StickyRows.ICON_DP] square at the page centre, and a content size taken from the creating
 * device's editor paper — since arc 33 / F2 its whole window, because the editor's paper is
 * full-bleed under a floating bar. The one number the row is stuck with for life, since every
 * later opening registers the note at the size it was authored at.
 */
class StickyDefaultsTest {

    private val density = 2f
    private val edge = StickyRows.ICON_DP * density   // 144 px

    // ── The icon box ─────────────────────────────────────────────────────────

    @Test
    fun `the icon is an ICON_DP square at the page centre`() {
        val s = StickyDefaults.at("n1", pageWidth = 1404f, pageHeight = 1872f, density = density, contentW = 1404, contentH = 1800)
        assertEquals(edge, s.width, 0.001f)
        assertEquals(edge, s.height, 0.001f)
        assertEquals((1404f - edge) / 2f, s.x, 0.001f)
        assertEquals((1872f - edge) / 2f, s.y, 0.001f)
        // Centred means centred: the box's own centre is the page's.
        assertEquals(1404f / 2f, s.x + s.width / 2f, 0.001f)
        assertEquals(1872f / 2f, s.y + s.height / 2f, 0.001f)
    }

    @Test
    fun `density is what makes the icon a size and not a number`() {
        val one = StickyDefaults.at("n1", 1000f, 1000f, density = 1f, contentW = 1, contentH = 1)
        val three = StickyDefaults.at("n1", 1000f, 1000f, density = 3f, contentW = 1, contentH = 1)
        assertEquals(StickyRows.ICON_DP, one.width, 0.001f)
        assertEquals(StickyRows.ICON_DP * 3f, three.width, 0.001f)
    }

    @Test
    fun `a page too small for the icon still gets it on the page, never off it`() {
        val s = StickyDefaults.at("n1", pageWidth = 10f, pageHeight = 10f, density = density, contentW = 100, contentH = 100)
        assertEquals(0f, s.x, 0f)
        assertEquals(0f, s.y, 0f)
        assertTrue("the icon keeps its size — only its origin is clamped", s.width == edge)
    }

    @Test
    fun `the id and the content size ride through untouched`() {
        val s = StickyDefaults.at("n-42", 1404f, 1872f, density, contentW = 1404, contentH = 1758)
        assertEquals("n-42", s.id)
        assertEquals(1404, s.contentW)
        assertEquals(1758, s.contentH)
    }

    @Test
    fun `order is zero — the store lands it at the tail itself`() {
        assertEquals(0, StickyDefaults.at("n1", 1404f, 1872f, density, 800, 600).order)
    }

    @Test
    fun `a fresh sticky carries no content`() {
        assertEquals(emptyList<String>(), StickyDefaults.at("n1", 1404f, 1872f, density, 800, 600).childIds)
    }

    // ── The content size ─────────────────────────────────────────────────────

    @Test
    fun `contentSize is the whole window`() {
        // Arc 33 / F2: the top bar floats over the paper, so nothing comes off either dimension.
        assertEquals(1404 to 1872, StickyDefaults.contentSize(windowW = 1404, windowH = 1872))
    }

    @Test
    fun `contentSize takes the window's height whole — the bar no longer comes off it`() {
        val (w, h) = StickyDefaults.contentSize(1404, 1872)
        assertEquals(1404, w)
        assertEquals(1872, h)
    }

    @Test
    fun `a size that cannot be positive floors at one px`() {
        // Nothing real produces these; a note whose packed size is 0 would read as "unknown" and
        // send the editor back to its own paper area, which is a worse answer than one px.
        assertEquals(1 to 1, StickyDefaults.contentSize(windowW = 0, windowH = 0))
        assertEquals(1 to 1, StickyDefaults.contentSize(windowW = -50, windowH = -100))
    }

    @Test
    fun `a size past the packing limit is capped, not truncated into another field`() {
        // StickyFlags packs the pair into 20 bits each; an unclamped value would spill its high
        // bits into the height's field and come back as a different note entirely.
        val (w, h) = StickyDefaults.contentSize(
            windowW = StickyFlags.MAX + 500,
            windowH = StickyFlags.MAX + 500,
        )
        assertEquals(StickyFlags.MAX, w)
        assertEquals(StickyFlags.MAX, h)
        val flags = StickyFlags.pack(w, h)
        assertEquals(StickyFlags.MAX, StickyFlags.contentW(flags))
        assertEquals(StickyFlags.MAX, StickyFlags.contentH(flags))
    }
}
