package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** The container round trip and its refusals — pure `java.io`, the whole point of the format. */
class PageBundleTest {

    private fun bundleOf(vararg pages: Triple<Int, Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        PageBundle.Writer(out, pages.size).use { w ->
            for ((width, height, image) in pages) w.writePage(width, height, image)
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripsThreePages() {
        val images = listOf(byteArrayOf(1), byteArrayOf(2, 3), ByteArray(1024) { it.toByte() })
        val bytes = bundleOf(
            Triple(1404, 1872, images[0]),
            Triple(1920, 2560, images[1]),
            Triple(7, 9, images[2]),
        )
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { r ->
            assertEquals(3, r.pageCount)
            val first = r.readPage()
            assertEquals(1404, first.widthPx)
            assertEquals(1872, first.heightPx)
            assertArrayEquals(images[0], first.image)
            assertArrayEquals(images[1], r.readPage().image)
            assertArrayEquals(images[2], r.readPage().image)
            // Driving past pageCount is a caller bug, not an EOF to swallow.
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }

    @Test
    fun writerRefusesBadShapes() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(out, 0) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(out, PageBundle.MAX_PAGES + 1) }
        PageBundle.Writer(ByteArrayOutputStream(), 1).let { w ->
            assertThrows(IllegalArgumentException::class.java) { w.writePage(0, 10, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) {
                w.writePage(10, PageBundle.MAX_DIMENSION_PX + 1, byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) { w.writePage(10, 10, ByteArray(0)) }
            w.writePage(10, 10, byteArrayOf(1))
            // The declared count is a contract in both directions.
            assertThrows(IllegalStateException::class.java) { w.writePage(10, 10, byteArrayOf(1)) }
            w.close()
        }
    }

    @Test
    fun closingShortOfTheDeclaredCountThrows() {
        val w = PageBundle.Writer(ByteArrayOutputStream(), 2)
        w.writePage(10, 10, byteArrayOf(1))
        assertThrows(IOException::class.java) { w.close() }
    }

    @Test
    fun readerRefusesWrongMagicAndVersion() {
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream(byteArrayOf(1, 2)))
        }
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream("SOIL0000".toByteArray()))
        }
        val badVersion = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).apply { write(PageBundle.MAGIC); writeInt(99); writeInt(1) }
        }.toByteArray()
        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(badVersion)) }
    }

    @Test
    fun readerRefusesCapViolationsBeforeAllocating() {
        fun header(count: Int, block: DataOutputStream.() -> Unit = {}): ByteArray =
            ByteArrayOutputStream().also { bos ->
                DataOutputStream(bos).apply { write(PageBundle.MAGIC); writeInt(PageBundle.VERSION); writeInt(count); block() }
            }.toByteArray()

        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(header(0))) }
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream(header(PageBundle.MAX_PAGES + 1)))
        }
        // A corrupt image length past the cap is refused without a huge allocation being asked for.
        val bogusLength = header(1) { writeInt(10); writeInt(10); writeInt(PageBundle.MAX_PAGE_BYTES + 1) }
        PageBundle.Reader(ByteArrayInputStream(bogusLength)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
        val bogusDimension = header(1) { writeInt(-5); writeInt(10); writeInt(1); write(1) }
        PageBundle.Reader(ByteArrayInputStream(bogusDimension)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }

    @Test
    fun truncatedBundleThrowsInsideThePage() {
        val whole = bundleOf(Triple(10, 10, ByteArray(100) { 7 }))
        val cut = whole.copyOf(whole.size - 40)
        PageBundle.Reader(ByteArrayInputStream(cut)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }

    // ── Version 2 (arc 28 / D7) ─────────────────────────────────────────────

    @Test
    fun noLinksWritesAVersionOneStreamByteForByte() {
        // The compatible-tail rule in stream clothes: an exporter that only reads v1 must still
        // open every bundle that has nothing it could not use.
        val bytes = bundleOf(Triple(10, 10, byteArrayOf(1)))
        val version = java.nio.ByteBuffer.wrap(bytes, 4, 4).int
        assertEquals(PageBundle.VERSION_1, version)
        val explicit = ByteArrayOutputStream()
        PageBundle.Writer(explicit, 1, emptyList()).use { it.writePage(10, 10, byteArrayOf(1)) }
        assertArrayEquals(bytes, explicit.toByteArray())
    }

    @Test
    fun versionOneStreamReadsThroughTheVersionTwoReaderWithNoLinks() {
        val bytes = bundleOf(Triple(10, 10, byteArrayOf(1)), Triple(20, 20, byteArrayOf(2)))
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { r ->
            assertEquals(PageBundle.VERSION_1, r.version)
            r.readPage(); r.readPage()
            assertEquals(emptyList<PageBundle.Link>(), r.readLinks())
        }
    }

    @Test
    fun linkTrailerRoundTrips() {
        val links = listOf(
            PageBundle.Link(1, 10f, 20f, 82f, 92f, 3),
            PageBundle.Link(3, 0f, 500f, 400f, 560f, 1),
        )
        val out = ByteArrayOutputStream()
        PageBundle.Writer(out, 3, links).use { w ->
            assertEquals(PageBundle.VERSION, w.version)
            repeat(3) { w.writePage(10, 10, byteArrayOf(it.toByte())) }
        }
        PageBundle.Reader(ByteArrayInputStream(out.toByteArray())).use { r ->
            assertEquals(PageBundle.VERSION, r.version)
            assertEquals(3, r.pageCount)
            // The trailer sits behind the pages; asking early is a bug, not an empty answer.
            assertThrows(IOException::class.java) { r.readLinks() }
            repeat(3) { r.readPage() }
            val back = r.readLinks()
            assertEquals(2, back.size)
            assertEquals(1, back[0].fromPage); assertEquals(3, back[0].toPage)
            assertEquals(10f, back[0].l); assertEquals(20f, back[0].t)
            assertEquals(82f, back[0].r); assertEquals(92f, back[0].b)
            assertEquals(3, back[1].fromPage); assertEquals(1, back[1].toPage)
            // A second call is the same answer, not a second read.
            assertEquals(2, r.readLinks().size)
        }
    }

    @Test
    fun writerRefusesLinksOutsideThePageCount() {
        assertThrows(IllegalArgumentException::class.java) {
            PageBundle.Writer(ByteArrayOutputStream(), 2, listOf(PageBundle.Link(1, 0f, 0f, 1f, 1f, 3)))
        }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(0, 0f, 0f, 1f, 1f, 1) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(1, 5f, 0f, 1f, 1f, 1) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(1, Float.NaN, 0f, 1f, 1f, 1) }
    }

    @Test
    fun readerRefusesABadTrailer() {
        fun v2(count: Int, block: DataOutputStream.() -> Unit = {}): ByteArray =
            ByteArrayOutputStream().also { bos ->
                DataOutputStream(bos).apply {
                    write(PageBundle.MAGIC); writeInt(PageBundle.VERSION); writeInt(1)
                    writeInt(10); writeInt(10); writeInt(1); write(1)
                    writeInt(count); block()
                }
            }.toByteArray()
        // A count past the cap is refused before anything is allocated for it.
        PageBundle.Reader(ByteArrayInputStream(v2(PageBundle.MAX_LINKS + 1))).use { r ->
            r.readPage()
            assertThrows(IOException::class.java) { r.readLinks() }
        }
        // A page number the stream cannot hold.
        val badPage = v2(1) { writeInt(1); writeFloat(0f); writeFloat(0f); writeFloat(1f); writeFloat(1f); writeInt(2) }
        PageBundle.Reader(ByteArrayInputStream(badPage)).use { r ->
            r.readPage()
            assertThrows(IOException::class.java) { r.readLinks() }
        }
        // A trailer that ends early.
        val cut = v2(1) { writeInt(1); writeFloat(0f) }
        PageBundle.Reader(ByteArrayInputStream(cut)).use { r ->
            r.readPage()
            assertThrows(IOException::class.java) { r.readLinks() }
        }
        // A v2 stream that stops dead after its pages: no trailer is not an empty trailer.
        val none = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).apply {
                write(PageBundle.MAGIC); writeInt(PageBundle.VERSION); writeInt(1)
                writeInt(10); writeInt(10); writeInt(1); write(1)
            }
        }.toByteArray()
        PageBundle.Reader(ByteArrayInputStream(none)).use { r ->
            r.readPage()
            assertThrows(IOException::class.java) { r.readLinks() }
        }
    }
}
