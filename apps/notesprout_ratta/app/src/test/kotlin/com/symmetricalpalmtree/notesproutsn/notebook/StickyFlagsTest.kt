package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sticky row's packed `flags` word (arc 28 / H1): content width (bits 0–19) and content
 *  height (bits 20–39), each capped at [StickyFlags.MAX]. */
class StickyFlagsTest {

    @Test
    fun `content size round-trips`() {
        val flags = StickyFlags.pack(800, 600)
        assertEquals(800, StickyFlags.contentW(flags))
        assertEquals(600, StickyFlags.contentH(flags))
    }

    @Test
    fun `zero round-trips`() {
        val flags = StickyFlags.pack(0, 0)
        assertEquals(0, StickyFlags.contentW(flags))
        assertEquals(0, StickyFlags.contentH(flags))
    }

    @Test
    fun `MAX round-trips`() {
        val flags = StickyFlags.pack(StickyFlags.MAX, StickyFlags.MAX)
        assertEquals(StickyFlags.MAX, StickyFlags.contentW(flags))
        assertEquals(StickyFlags.MAX, StickyFlags.contentH(flags))
    }

    @Test
    fun `a value above MAX is clamped at pack time`() {
        val flags = StickyFlags.pack(StickyFlags.MAX + 1000, StickyFlags.MAX + 1)
        assertEquals(StickyFlags.MAX, StickyFlags.contentW(flags))
        assertEquals(StickyFlags.MAX, StickyFlags.contentH(flags))
    }

    @Test
    fun `a negative value is clamped to zero at pack time`() {
        val flags = StickyFlags.pack(-5, -1)
        assertEquals(0, StickyFlags.contentW(flags))
        assertEquals(0, StickyFlags.contentH(flags))
    }

    @Test
    fun `width and height do not bleed into each other`() {
        val flags = StickyFlags.pack(StickyFlags.MAX, 0)
        assertEquals(StickyFlags.MAX, StickyFlags.contentW(flags))
        assertEquals(0, StickyFlags.contentH(flags))

        val flipped = StickyFlags.pack(0, StickyFlags.MAX)
        assertEquals(0, StickyFlags.contentW(flipped))
        assertEquals(StickyFlags.MAX, StickyFlags.contentH(flipped))
    }

    @Test
    fun `a null flags word reads as zero for both dimensions`() {
        assertEquals(0, StickyFlags.contentW(null))
        assertEquals(0, StickyFlags.contentH(null))
    }
}
