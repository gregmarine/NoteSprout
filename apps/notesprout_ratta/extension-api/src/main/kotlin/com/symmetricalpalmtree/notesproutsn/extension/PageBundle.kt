package com.symmetricalpalmtree.notesproutsn.extension

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * **The page bundle** (arc 18 / D1) — the container a [ExporterContract.SOURCE_PAGES] exporter
 * receives through its read fd: every page of the notebook, host-rendered full-fidelity into one
 * encoded image each, in display order. It exists so an exporter that could never receive the
 * `.soil` itself (no key ever crosses) can still deliver a page-faithful document.
 *
 * The wire format, all integers big-endian ([DataOutputStream]'s order):
 *
 * ```
 * "NSPB" (4 ASCII bytes) · int version (1 or 2) · int pageCount ·
 * pageCount × ( int widthPx · int heightPx · int byteLength · byteLength image bytes ) ·
 * [ version 2 only: int linkCount · linkCount × ( int fromPage · float l t r b · int toPage ) ]
 * ```
 *
 * **Version 2 (arc 28 / D7) is version 1 plus a trailer of links** — the endnote treatment of
 * sticky notes: the host appends one page per note after the notebook's pages, and the trailer
 * says which rectangle on which page jumps to which page (icon → endnote, caption → source).
 * `pageCount` **includes** the endnote pages; page numbers in a link are **1-based** in stream
 * order, and a rectangle is in its from-page's own pixel space. A v2 reader accepts a v1 stream
 * (no trailer, [Reader.readLinks] is empty) and the [Writer] emits **version 1 whenever it has no
 * links to write**, so an exporter that only knows v1 still opens every bundle that has nothing
 * it could not use — the compatible-tail rule, in stream clothes.
 *
 * The image bytes are one platform-decodable encoded image per page (`BitmapFactory` sniffs the
 * header — the container does not name the codec; the host writes WEBP lossy q100 over an opaque
 * RGB_565 bake, the F5 measured recipe). Width/height are the page's own pixel size, carried
 * beside the bytes so a reader can size a PDF page without decoding first.
 *
 * **One page at a time, both sides — the API is the memory rule.** [Writer.writePage] appends one
 * page and keeps nothing; [Reader.readPage] hands back one page and keeps nothing. A whole
 * notebook of full-size images in memory is an OOM on a 3 GB device, so neither end ever holds
 * more than one.
 *
 * **Unmarshal is validation** (the family rule, in stream clothes): [Reader] refuses a wrong
 * magic, an unknown version, and any count or length outside the caps with an [IOException]
 * before allocating for it — the fd comes from the other side of a process boundary.
 */
object PageBundle {

    /** "NSPB" — Notesprout page bundle. */
    val MAGIC: ByteArray = byteArrayOf(0x4E, 0x53, 0x50, 0x42)

    /** The version this reader understands and the writer emits when it has links. */
    const val VERSION: Int = 2

    /** The links-free shape (arc 18): what the writer emits when there is no trailer to carry. */
    const val VERSION_1: Int = 1

    /** Most pages one bundle may carry. */
    const val MAX_PAGES: Int = 4096

    /** Largest page edge (px) — far past any real panel, small enough to refuse nonsense. */
    const val MAX_DIMENSION_PX: Int = 32768

    /** Largest encoded image (bytes). A full Manta page bakes to well under this at WEBP q100;
     *  the cap is what stops a corrupt length from asking the reader for a huge allocation. */
    const val MAX_PAGE_BYTES: Int = 32 * 1024 * 1024

    /** Most links one bundle may carry — two per note, and a note count no notebook reaches. */
    const val MAX_LINKS: Int = 65536

    /** One page as the reader hands it back: the page's own pixel size + its encoded image. */
    class Page(val widthPx: Int, val heightPx: Int, val image: ByteArray)

    /**
     * One jump (version 2): the rectangle `l t r b` on [fromPage] (that page's own pixels,
     * top-left origin) leads to [toPage]. Both page numbers are **1-based** in stream order and
     * must lie within `pageCount`; the writer and the reader each refuse anything else.
     */
    class Link(
        val fromPage: Int,
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
        val toPage: Int,
    ) {
        init {
            require(fromPage >= 1 && toPage >= 1) { "link pages must be 1-based" }
            require(l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()) { "link rect is not finite" }
            require(r > l && b > t) { "link rect is empty" }
        }
    }

    /**
     * Streams a bundle onto [out] (which it owns and closes). Declare [pageCount] up front — the
     * host knows the page list before it renders — then [writePage] exactly that many times and
     * [close]. Closing short of the declared count throws: a truncated bundle must never read
     * as a finished one.
     *
     * [links] is the version-2 trailer, declared up front like the count because the version
     * word is the first thing written: **empty means a version-1 stream**, byte-identical to what
     * the arc-18 writer produced. Every link is checked against [pageCount] here, so a reader
     * never meets a page number the writer could not have meant.
     */
    class Writer(
        out: OutputStream,
        private val pageCount: Int,
        private val links: List<Link> = emptyList(),
    ) : Closeable {

        private val stream = DataOutputStream(out.buffered())
        private var written = 0
        private var closed = false

        /** The version word actually written: [VERSION_1] with no links, else [VERSION]. */
        val version: Int = if (links.isEmpty()) VERSION_1 else VERSION

        init {
            require(pageCount in 1..MAX_PAGES) { "$pageCount pages outside 1..$MAX_PAGES" }
            require(links.size <= MAX_LINKS) { "${links.size} links > $MAX_LINKS" }
            for (link in links) {
                require(link.fromPage <= pageCount && link.toPage <= pageCount) {
                    "link ${link.fromPage}→${link.toPage} outside 1..$pageCount"
                }
            }
            stream.write(MAGIC)
            stream.writeInt(version)
            stream.writeInt(pageCount)
        }

        /** Append one page. [image] is written through and not retained. */
        fun writePage(widthPx: Int, heightPx: Int, image: ByteArray) {
            check(!closed) { "writer is closed" }
            check(written < pageCount) { "all $pageCount pages already written" }
            require(widthPx in 1..MAX_DIMENSION_PX && heightPx in 1..MAX_DIMENSION_PX) {
                "page size ${widthPx}x$heightPx outside 1..$MAX_DIMENSION_PX"
            }
            require(image.isNotEmpty() && image.size <= MAX_PAGE_BYTES) {
                "image of ${image.size} bytes outside 1..$MAX_PAGE_BYTES"
            }
            stream.writeInt(widthPx)
            stream.writeInt(heightPx)
            stream.writeInt(image.size)
            stream.write(image)
            written++
        }

        /** Flush and close. Throws [IOException] if fewer pages than declared were written. */
        override fun close() {
            if (closed) return
            closed = true
            try {
                if (written < pageCount) {
                    throw IOException("bundle closed after $written of $pageCount pages")
                }
                if (version == VERSION) {
                    stream.writeInt(links.size)
                    for (link in links) {
                        stream.writeInt(link.fromPage)
                        stream.writeFloat(link.l)
                        stream.writeFloat(link.t)
                        stream.writeFloat(link.r)
                        stream.writeFloat(link.b)
                        stream.writeInt(link.toPage)
                    }
                }
                stream.flush()
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    /**
     * Reads a bundle from [input] (which it owns and closes). The header is validated in the
     * constructor; then call [readPage] exactly [pageCount] times, then [readLinks] once (empty
     * for a version-1 stream). Every violation — wrong magic, unknown version, a count or length
     * outside the caps, a page number outside the count, a stream that ends early — is an
     * [IOException]; nothing is allocated for a length before that length has passed the cap.
     */
    class Reader(input: InputStream) : Closeable {

        private val stream = DataInputStream(input.buffered())

        val pageCount: Int

        /** The stream's version word — [VERSION_1] carries no trailer. */
        val version: Int

        private var read = 0
        private var links: List<Link>? = null

        init {
            val magic = ByteArray(MAGIC.size)
            try {
                stream.readFully(magic)
            } catch (e: EOFException) {
                throw IOException("not a page bundle: shorter than the magic", e)
            }
            if (!magic.contentEquals(MAGIC)) throw IOException("not a page bundle: wrong magic")
            val v = stream.readInt()
            if (v != VERSION_1 && v != VERSION) throw IOException("unknown page-bundle version $v")
            version = v
            val count = stream.readInt()
            if (count !in 1..MAX_PAGES) throw IOException("$count pages outside 1..$MAX_PAGES")
            pageCount = count
        }

        /** The next page, in display order. Throws [IOException] past the last page — the caller
         *  drives by [pageCount], and reading further is a bug, not an EOF to swallow. */
        fun readPage(): Page {
            if (read >= pageCount) throw IOException("all $pageCount pages already read")
            val width = stream.readInt()
            val height = stream.readInt()
            if (width !in 1..MAX_DIMENSION_PX || height !in 1..MAX_DIMENSION_PX) {
                throw IOException("page size ${width}x$height outside 1..$MAX_DIMENSION_PX")
            }
            val length = stream.readInt()
            if (length !in 1..MAX_PAGE_BYTES) {
                throw IOException("image of $length bytes outside 1..$MAX_PAGE_BYTES")
            }
            val image = ByteArray(length)
            try {
                stream.readFully(image)
            } catch (e: EOFException) {
                throw IOException("bundle ends inside page ${read + 1} of $pageCount", e)
            }
            read++
            return Page(width, height, image)
        }

        /**
         * The link trailer, after the last page has been read — empty for a version-1 stream.
         * Reading it before the pages is a bug (the trailer sits behind them), and so is an
         * [IOException]. Every link's pages are checked against [pageCount] and its rectangle
         * against finiteness before it is kept; the count is capped before anything allocates.
         */
        fun readLinks(): List<Link> {
            links?.let { return it }
            if (read < pageCount) throw IOException("links read after $read of $pageCount pages")
            val result = if (version == VERSION_1) {
                emptyList()
            } else {
                val count = try {
                    stream.readInt()
                } catch (e: EOFException) {
                    throw IOException("bundle ends before its link trailer", e)
                }
                if (count !in 0..MAX_LINKS) throw IOException("$count links outside 0..$MAX_LINKS")
                val list = ArrayList<Link>(count)
                try {
                    repeat(count) {
                        val from = stream.readInt()
                        val l = stream.readFloat()
                        val t = stream.readFloat()
                        val r = stream.readFloat()
                        val b = stream.readFloat()
                        val to = stream.readInt()
                        if (from !in 1..pageCount || to !in 1..pageCount) {
                            throw IOException("link $from→$to outside 1..$pageCount")
                        }
                        list += try {
                            Link(from, l, t, r, b, to)
                        } catch (e: IllegalArgumentException) {
                            throw IOException("malformed link rect", e)
                        }
                    }
                } catch (e: EOFException) {
                    throw IOException("bundle ends inside its link trailer", e)
                }
                list
            }
            links = result
            return result
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }
}
