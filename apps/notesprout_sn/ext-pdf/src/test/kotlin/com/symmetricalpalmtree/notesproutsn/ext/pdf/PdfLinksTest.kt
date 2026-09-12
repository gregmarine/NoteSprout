package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfLinksTest {

    @Test
    fun noLinksMeansNoAnnotations() {
        // The byte-identical-without-stickies guarantee rests on this: an empty trailer gives the
        // assembly nothing to touch.
        assertEquals(emptyList<PdfLinks.Annotation>(), PdfLinks.annotations(emptyList(), listOf(1872, 1872)))
    }

    @Test
    fun flipsToBottomLeftOriginAndZeroBasedPages() {
        val links = listOf(
            PageBundle.Link(1, 100f, 200f, 172f, 272f, 3),
            PageBundle.Link(3, 0f, 940f, 800f, 1000f, 1),
        )
        val out = PdfLinks.annotations(links, listOf(1872, 1872, 1000))
        assertEquals(PdfLinks.Annotation(0, 100f, 1872f - 272f, 172f, 1872f - 200f, 2), out[0])
        assertEquals(PdfLinks.Annotation(2, 0f, 0f, 800f, 60f, 0), out[1])
    }

    @Test
    fun refusesALinkOutsideThePages() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfLinks.annotations(listOf(PageBundle.Link(1, 0f, 0f, 1f, 1f, 3)), listOf(10, 10))
        }
    }
}
