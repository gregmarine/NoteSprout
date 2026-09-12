package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * The two ways a text object's Markdown source is tidied before it is stored (arc 28 / H2) — pure,
 * so the rules are a table a test can read rather than regexes buried in a dialog and a flow.
 *
 * They are deliberately **not** the same rule, because the two inputs are not the same thing:
 *
 *  - [normalize] takes what a *recognizer* handed back. Its line breaks are guesses about where the
 *    writing wrapped, not paragraphs the user typed, and its spacing is whatever the engine
 *    measured between glyphs — so runs of space collapse and no more than one blank line survives.
 *  - [typed] takes what a *person* typed into [TextEditDialog]. Every character in it is deliberate:
 *    two spaces before a newline are a Markdown line break and a run of blank lines is the author's
 *    spacing, so nothing inside a line is touched and interior blank lines are left exactly as
 *    they are.
 *
 * Both drop leading and trailing blank lines, and both answer `""` for an input with nothing in it —
 * the one answer both callers act on the same way, because a blank text object never exists (D1).
 */
object TextLines {

    /** Every run of horizontal whitespace inside one line — tabs included, newlines excluded. */
    private val HORIZONTAL = Regex("[^\\S\\n]+")

    /**
     * Recognized ink → storable Markdown source: runs of horizontal whitespace collapse to one
     * space, every line is trimmed, leading and trailing blank lines go, and a run of blank lines
     * in the middle becomes a single one (a paragraph break, however many the recognizer emitted).
     *
     * This is [HeadingConvert]'s `oneLine` with the newlines kept — the multi-line half of the same
     * job, which is why the two live one call apart.
     */
    fun normalize(raw: String): String {
        val lines = splitLines(raw).map { it.replace(HORIZONTAL, " ").trim() }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            // At most one blank in a row, and never one before the first non-blank line.
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out.add(line)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    /**
     * Typed field text → storable Markdown source: each line loses its trailing whitespace, and the
     * blank lines above the first line and below the last go. Nothing else — the interior is the
     * author's, and collapsing it would silently rewrite their paragraphs.
     */
    fun typed(raw: String): String {
        val lines = splitLines(raw).map { it.trimEnd() }
        var first = 0
        var last = lines.size - 1
        while (first <= last && lines[first].isEmpty()) first++
        while (last >= first && lines[last].isEmpty()) last--
        if (first > last) return ""
        return lines.subList(first, last + 1).joinToString("\n")
    }

    /** Split on newlines, tolerating CRLF and a lone CR — a keyboard or an extension may send either. */
    private fun splitLines(raw: String): List<String> =
        raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
}
