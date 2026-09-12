package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two tidies a text object's source goes through before it is stored (arc 28 / H2) — one for
 * what a recognizer guessed, one for what a person typed. They are different rules on purpose, and
 * this is where that difference is pinned.
 */
class TextLinesTest {

    // ── normalize: recognized ink ────────────────────────────────────────────

    @Test
    fun `runs of horizontal whitespace collapse to one space`() {
        assertEquals("one two three", TextLines.normalize("one   two\t\tthree"))
    }

    @Test
    fun `each line is trimmed`() {
        assertEquals("one\ntwo", TextLines.normalize("  one  \n\ttwo\t"))
    }

    @Test
    fun `newlines survive — a text object may be a paragraph`() {
        assertEquals("one\ntwo\nthree", TextLines.normalize("one\ntwo\nthree"))
    }

    @Test
    fun `leading and trailing blank lines go`() {
        assertEquals("one\ntwo", TextLines.normalize("\n \n one \n two \n\n\n"))
    }

    @Test
    fun `an interior blank line survives, but never two in a row`() {
        // One blank line is a paragraph break; the recognizer's idea of how many is not the author's.
        assertEquals("one\n\ntwo", TextLines.normalize("one\n\ntwo"))
        assertEquals("one\n\ntwo", TextLines.normalize("one\n\n\n \n\ntwo"))
    }

    @Test
    fun `CRLF and a lone CR both count as a newline`() {
        assertEquals("one\ntwo\nthree", TextLines.normalize("one\r\ntwo\rthree"))
    }

    @Test
    fun `nothing readable is the empty string — the caller's failure signal`() {
        assertEquals("", TextLines.normalize(""))
        assertEquals("", TextLines.normalize("   \n\t\n \r\n "))
    }

    // ── typed: the dialog's field ────────────────────────────────────────────

    @Test
    fun `typed text keeps its interior exactly`() {
        // Two spaces before a newline are a Markdown line break, and a run of blank lines is the
        // author's spacing — collapsing either would silently rewrite what they wrote.
        val src = "# Title\n\n\nbody  \nmore   words"
        assertEquals("# Title\n\n\nbody\nmore   words", TextLines.typed(src))
    }

    @Test
    fun `typed text loses trailing whitespace per line and blank lines top and bottom`() {
        // Only the *trailing* whitespace: an indent is Markdown, so it is kept (see below).
        assertEquals("  one\ntwo", TextLines.typed("\n\n  one   \ntwo\t\n \n\n"))
    }

    @Test
    fun `typed leading indentation is kept — it is Markdown`() {
        assertEquals("- a\n    - b", TextLines.typed("- a\n    - b\n"))
    }

    @Test
    fun `a field with only whitespace in it is blank — the caller deletes`() {
        assertEquals("", TextLines.typed("  \n\t\n"))
        assertEquals("", TextLines.typed(""))
    }

    @Test
    fun `typed text tolerates CRLF`() {
        assertEquals("one\ntwo", TextLines.typed("one\r\ntwo\r\n"))
    }
}
