package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.export.ExportPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Preset row's four rules (arc 31 / HV3) — what is listed, what is captured, what is applied. */
class ExportPresetsTest {

    private fun preset(
        exporter: String,
        destination: String = ExportPreset.DESTINATION_LOCAL,
        cloudPath: List<String>? = null,
        values: Map<String, String> = emptyMap(),
        documentSource: Boolean = false,
    ) = ExportPreset(
        exporter = exporter, values = values, documentSource = documentSource,
        destination = destination, cloudPath = cloudPath,
    )

    // ── listable ─────────────────────────────────────────────────────────────

    @Test
    fun `a preset whose exporter is not listed on this screen is hidden`() {
        val rows = listOf(
            ExportPresets.Row("1", "Drive PDF", preset("com.example.pdf")),
            ExportPresets.Row("2", "Images", preset("com.example.png")),
        )
        val listed = ExportPresets.listable(rows, setOf("com.example.pdf"))
        assertEquals(listOf("Drive PDF"), listed.map { it.name })
    }

    /** The same set answers "not installed" and "hidden by the scope" — the screen's candidates
     *  are already cut by both, which is why there is one question here and not two. */
    @Test
    fun `an exporter the scope does not list hides its presets too`() {
        val rows = listOf(ExportPresets.Row("1", "Whole notebook", preset("com.example.soil")))
        assertTrue(ExportPresets.listable(rows, setOf("com.example.pdf")).isEmpty())
        assertEquals(1, ExportPresets.listable(rows, setOf("com.example.soil", "com.example.pdf")).size)
    }

    @Test
    fun `the given order is kept`() {
        val rows = listOf(
            ExportPresets.Row("1", "Alpha", preset("a")),
            ExportPresets.Row("2", "Beta", preset("b")),
            ExportPresets.Row("3", "Gamma", preset("c")),
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), ExportPresets.listable(rows, setOf("a", "b", "c")).map { it.name })
        assertEquals(listOf("1", "2", "3"), ExportPresets.listable(rows, setOf("a", "b", "c")).map { it.id })
    }

    // ── capture / apply ──────────────────────────────────────────────────────

    @Test
    fun `capture and apply are inverse on a full state`() {
        val state = ExportPresets.State(
            exporter = "com.example.pdf",
            values = mapOf("pageTemplate" to "0", "protect" to "1"),
            documentSource = true,
            destination = ExportDestination.Choice.CLOUD,
            cloudPath = listOf("Exports", "Term 1"),
        )
        val applied = ExportPresets.apply(ExportPresets.capture(state), cloudAvailable = true)
        assertEquals(state, applied.state)
        assertFalse(applied.cloudFallback)
    }

    /** The folder is captured even under a local destination: the screen keeps it for the flip
     *  back, and a capture that dropped it would not be a picture of the screen. */
    @Test
    fun `a local state keeps its remembered folder through a round trip`() {
        val state = ExportPresets.State(
            exporter = "com.example.pdf",
            values = emptyMap(),
            documentSource = false,
            destination = ExportDestination.Choice.LOCAL,
            cloudPath = listOf("Exports", "Term 1"),
        )
        val applied = ExportPresets.apply(ExportPresets.capture(state), cloudAvailable = false)
        assertEquals(state, applied.state)
        assertFalse(applied.cloudFallback)
    }

    @Test
    fun `a cloud preset with no account falls back to local and says so, keeping the folder`() {
        val stored = preset(
            "com.example.pdf",
            destination = ExportPreset.DESTINATION_CLOUD,
            cloudPath = listOf("Exports", "Term 1"),
        )
        val applied = ExportPresets.apply(stored, cloudAvailable = false)
        assertTrue(applied.cloudFallback)
        assertEquals(ExportDestination.Choice.LOCAL, applied.state.destination)
        assertEquals(listOf("Exports", "Term 1"), applied.state.cloudPath)
    }

    @Test
    fun `a local preset never reports a fallback`() {
        val applied = ExportPresets.apply(preset("com.example.pdf"), cloudAvailable = false)
        assertFalse(applied.cloudFallback)
        assertEquals(ExportDestination.Choice.LOCAL, applied.state.destination)
    }

    @Test
    fun `apply carries the values and the source answer`() {
        val stored = preset("com.example.pdf", values = mapOf("a" to "1"), documentSource = true)
        val applied = ExportPresets.apply(stored, cloudAvailable = true)
        assertEquals(mapOf("a" to "1"), applied.state.values)
        assertTrue(applied.state.documentSource)
    }

    // ── the row itself ───────────────────────────────────────────────────────

    @Test
    fun `the row is on screen only with something to list`() {
        assertFalse(ExportPresets.rowVisible(emptyList()))
        assertTrue(
            ExportPresets.rowVisible(
                ExportPresets.listable(listOf(ExportPresets.Row("1", "One", preset("a"))), setOf("a"))
            )
        )
    }
}
