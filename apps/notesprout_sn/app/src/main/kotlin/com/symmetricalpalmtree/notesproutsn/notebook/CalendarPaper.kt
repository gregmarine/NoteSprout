package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.template.TemplateImport

/**
 * **Whether a page of paper an extension drew may become a template row** (arc 31 / HV5) — pure
 * arithmetic, no `android.graphics`, JVM-tested, because these are the numbers that decide whether
 * the app stores a blob it can never read back and whether it trusts a picture that came out of
 * another process.
 *
 * The calendar's `render` is bounded on its own side and its bundle is bounds-checked on the way in
 * ([com.symmetricalpalmtree.notesproutsn.extension.PageBundle.Reader] does the magic, the count,
 * the dimensions and the length). None of that is a reason to skip this: **bytes from an extension
 * are untrusted**, and the two questions asked here are the ones the container cannot answer —
 *
 *  - the encoded bytes must fit under [TemplateImport.MAX_BLOB_BYTES], the same ceiling an imported
 *    picture is held to and for the same reason (SQLCipher's 8 MiB `CursorWindow` caps what can be
 *    read back, not what can be written);
 *  - the **decoded** picture must be exactly the page it claims to be. The paper is stored as the
 *    new page's template and the ink lands on it 1:1, so a picture of another size is a grid the
 *    writing does not sit on — refuse it and let the ink land the ordinary way instead of papering
 *    a page wrongly.
 *
 * A refusal is never a repair: nothing is scaled, cropped or re-encoded here.
 */
object CalendarPaper {

    /**
     * True when [byteCount] encoded bytes decoding to [width] x [height] may paper a page of
     * [pageWidth] x [pageHeight]. Every argument is a count of pixels or bytes — a zero or negative
     * one is a refusal, not a special case.
     */
    fun accept(byteCount: Int, width: Int, height: Int, pageWidth: Int, pageHeight: Int): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0) return false
        if (byteCount <= 0 || byteCount > TemplateImport.MAX_BLOB_BYTES) return false
        return width == pageWidth && height == pageHeight
    }
}
