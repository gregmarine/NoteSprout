package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** og's sanitize rule, pinned: this is the name the family has always given an exported file. */
class ExportNamingTest {

    private val id = "8f14e45f-ceea-467a-9b8d-8f14e45fceea"

    @Test
    fun keepsAPlainName() {
        assertEquals("Field notes", ExportNaming.base("Field notes", id))
    }

    @Test
    fun spacesInsideSurvive() {
        assertEquals("My great notebook", ExportNaming.base("My great notebook", id))
    }

    @Test
    fun stripsEverythingOutsideTheCharset() {
        assertEquals("aB9_-.", ExportNaming.base("aB9_-./\\:*?\"<>|", id))
        // A separator is removed, not replaced: the two halves close up.
        assertEquals("Meeting2026", ExportNaming.base("Meeting/2026", id))
        assertEquals("ntes", ExportNaming.base("nötes", id))
        assertEquals("emoji", ExportNaming.base("emoji🌱", id))
    }

    @Test
    fun stripsFirstThenTrims() {
        // The stripping is what exposes the outer spaces; trimming first would leave them behind.
        assertEquals("name", ExportNaming.base("  ***name***  ", id))
        assertEquals("name", ExportNaming.base(" name ", id))
    }

    @Test
    fun emptyFallsBackToTheId() {
        assertEquals(id, ExportNaming.base("", id))
        assertEquals(id, ExportNaming.base("   ", id))
        assertEquals(id, ExportNaming.base("/////", id))
        assertEquals(id, ExportNaming.base("🌱🌱", id))
    }

    @Test
    fun dotAndDotDotFallBackToTheId() {
        assertEquals(id, ExportNaming.base(".", id))
        assertEquals(id, ExportNaming.base("..", id))
        // Three dots is a legal (if odd) filename, so it is kept.
        assertEquals("...", ExportNaming.base("...", id))
        // A leading dot is legal too: only bare "." and ".." are the directory names.
        assertEquals(".hidden", ExportNaming.base(".hidden", id))
    }

    @Test
    fun suggestedFileNameAppendsTheExporterExtension() {
        assertEquals("Field notes.soil", ExportNaming.suggestedFileName("Field notes", id, "soil"))
        assertEquals("$id.soil", ExportNaming.suggestedFileName("///", id, "soil"))
        assertEquals("Field notes.pdf", ExportNaming.suggestedFileName("Field notes", id, "pdf"))
    }

    @Test
    fun specNameTruncatesToTheContractCap() {
        val long = "n".repeat(ExporterContract.MAX_NAME_CHARS + 50)
        val spec = ExportNaming.specName(long, id)
        assertEquals(ExporterContract.MAX_NAME_CHARS, spec.length)
        assertTrue(spec.all { it == 'n' })
    }

    @Test
    fun specNameIsTheSameBaseAsTheFilename() {
        assertEquals(ExportNaming.base("Field notes", id), ExportNaming.specName("Field notes", id))
        assertEquals(id, ExportNaming.specName("..", id))
        // What the spec's own constructor demands: no separator, no NUL. Spaces stay.
        val spec = ExportNaming.specName("a/b c d", id)
        assertEquals("ab c d", spec)
        assertTrue('/' !in spec)
    }

    // ── Page scope (arc 30 / PE2) ────────────────────────────────────────────

    @Test
    fun pageStemUsesTheHeadingWhenThereIsOne() {
        assertEquals("Field notes - Standup", ExportNaming.pageStem("Field notes", id, 3, "Standup"))
    }

    @Test
    fun pageStemFallsBackToThePageNumber() {
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, null))
        // A heading that strips to nothing names nothing — the number stands in.
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, "🌱 ✨"))
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, "   "))
        assertEquals("Field notes - page 3", ExportNaming.pageStem("Field notes", id, 3, ".."))
    }

    @Test
    fun pageStemSanitizesTheHeadingLikeTheName() {
        assertEquals("Field notes - Q2plan draft", ExportNaming.pageStem("Field notes", id, 1, "Q2/plan: *draft*"))
        // A dash of any other kind is stripped, which is why the separator is a plain hyphen.
        assertEquals("Field notes - a  b", ExportNaming.pageStem("Field notes", id, 1, "a — b"))
    }

    @Test
    fun pageStemCapsALongHeading() {
        val long = "x".repeat(200)
        val stem = ExportNaming.pageStem("N", id, 1, long)
        assertEquals("N - " + "x".repeat(ExportNaming.MAX_TITLE_CHARS), stem)
    }

    @Test
    fun pageStemWithoutAPlaceNamesTheNotebookAlone() {
        // The page could not be placed (it vanished): never "page 0".
        assertEquals("Field notes", ExportNaming.pageStem("Field notes", id, 0, null))
        // ...but a heading still names it.
        assertEquals("Field notes - Standup", ExportNaming.pageStem("Field notes", id, 0, "Standup"))
    }

    @Test
    fun everyPageOfAPerPageExportIsNamedFromItsOwnPage() {
        // Arc 31 / HV1: one file per page, each named by the Contents rule or by its number.
        val titles = listOf("Plan", null, "Plan")
        val names = titles.mapIndexed { index, title ->
            ExportNaming.fileName(ExportNaming.pageStem("NB", id, index + 1, title), "png")
        }
        assertEquals(listOf("NB - Plan.png", "NB - page 2.png", "NB - Plan.png"), names)
        // Two pages under one heading make two files of one name, and that is the PROVIDER's
        // question: SAF de-dupes with "(1)", an upload replaces by name, and the host renames
        // nothing behind the user's back.
        assertEquals(names[0], names[2])
    }

    @Test
    fun stemBasedNamesAgreeWithTheOriginals() {
        val stem = ExportNaming.pageStem("Field notes", id, 2, null)
        assertEquals("Field notes - page 2.pdf", ExportNaming.fileName(stem, "pdf"))
        assertEquals(stem, ExportNaming.specNameOf(stem))
        assertEquals(ExportNaming.suggestedFileName("Field notes", id, "pdf"), ExportNaming.fileName(ExportNaming.base("Field notes", id), "pdf"))
        assertTrue(ExportNaming.specNameOf("y".repeat(500)).length <= ExporterContract.MAX_NAME_CHARS)
    }
}
