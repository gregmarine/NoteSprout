package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Arc 31 / HV1: the split is a re-frame of the container, never a re-encode. Every part is a
 * version-1, one-page bundle carrying its page's own size and bytes, and a v2 bundle's links are
 * dropped — a one-page file has nowhere to jump to.
 */
class BundleSplitTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Distinct sizes so a part that carried the wrong page's bytes could not pass. */
    private fun image(seed: Int, size: Int) = ByteArray(size) { (seed * 31 + it).toByte() }

    private class Source(val widthPx: Int, val heightPx: Int, val image: ByteArray)

    private fun bundle(pages: List<Source>, links: List<PageBundle.Link> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        PageBundle.Writer(out, pages.size, links).use { writer ->
            for (page in pages) writer.writePage(page.widthPx, page.heightPx, page.image)
        }
        return out.toByteArray()
    }

    private val threePages = listOf(
        Source(1404, 1872, image(1, 40)),
        Source(1404, 1872, image(2, 61)),
        Source(700, 900, image(3, 17)),
    )

    private fun splitToBytes(bytes: ByteArray): List<ByteArray> {
        val parts = ArrayList<ByteArrayOutputStream>()
        val count = BundleSplit.split(ByteArrayInputStream(bytes)) { _, _ ->
            ByteArrayOutputStream().also { parts += it }
        }
        assertEquals(parts.size, count)
        return parts.map { it.toByteArray() }
    }

    private fun readWhole(bytes: ByteArray): Pair<List<Source>, List<PageBundle.Link>> =
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { reader ->
            val pages = (0 until reader.pageCount).map {
                val page = reader.readPage()
                Source(page.widthPx, page.heightPx, page.image)
            }
            pages to reader.readLinks()
        }

    @Test
    fun aThreePageBundleBecomesThreeOnePageBundles() {
        val parts = splitToBytes(bundle(threePages))
        assertEquals(3, parts.size)
        parts.forEachIndexed { index, part ->
            val (pages, links) = readWhole(part)
            assertEquals(1, pages.size)
            assertTrue(links.isEmpty())
            assertEquals(threePages[index].widthPx, pages[0].widthPx)
            assertEquals(threePages[index].heightPx, pages[0].heightPx)
            assertArrayEquals(threePages[index].image, pages[0].image)
        }
    }

    @Test
    fun everyPartIsAVersionOneStream() {
        for (part in splitToBytes(bundle(threePages))) {
            PageBundle.Reader(ByteArrayInputStream(part)).use { reader ->
                assertEquals(PageBundle.VERSION_1, reader.version)
                assertEquals(1, reader.pageCount)
            }
        }
    }

    @Test
    fun aVersionTwoBundleSplitsToVersionOnePagesWithNoLinks() {
        val links = listOf(
            PageBundle.Link(1, 10f, 10f, 60f, 60f, 3),
            PageBundle.Link(3, 0f, 0f, 100f, 40f, 1),
        )
        val whole = bundle(threePages, links)
        PageBundle.Reader(ByteArrayInputStream(whole)).use { assertEquals(PageBundle.VERSION, it.version) }
        val parts = splitToBytes(whole)
        assertEquals(3, parts.size)
        parts.forEachIndexed { index, part ->
            val (pages, partLinks) = readWhole(part)
            assertEquals(1, pages.size)
            assertTrue("a one-page bundle has nowhere to jump to", partLinks.isEmpty())
            assertArrayEquals(threePages[index].image, pages[0].image)
        }
    }

    @Test
    fun aOnePageBundleSplitsToOneFile() {
        val one = listOf(Source(800, 600, image(9, 23)))
        val parts = splitToBytes(bundle(one))
        assertEquals(1, parts.size)
        val (pages, _) = readWhole(parts[0])
        assertEquals(800, pages[0].widthPx)
        assertEquals(600, pages[0].heightPx)
        assertArrayEquals(one[0].image, pages[0].image)
    }

    @Test
    fun theSinkIsAskedOncePerPageInOrder() {
        val seen = ArrayList<Pair<Int, Int>>()
        val count = BundleSplit.split(ByteArrayInputStream(bundle(threePages))) { index, pageCount ->
            seen += index to pageCount
            ByteArrayOutputStream()
        }
        assertEquals(3, count)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), seen)
    }

    @Test
    fun theFileOverloadWritesOneNumberedPartPerPageInOrder() {
        val dir = temp.newFolder("export")
        val whole = temp.newFile("whole.pages")
        whole.writeBytes(bundle(threePages))
        val parts = BundleSplit.split(whole, dir)
        assertEquals(listOf("page-1.pages", "page-2.pages", "page-3.pages"), parts.map { it.name })
        parts.forEachIndexed { index, part ->
            val (pages, _) = readWhole(part.readBytes())
            assertEquals(1, pages.size)
            assertArrayEquals(threePages[index].image, pages[0].image)
        }
    }
}
