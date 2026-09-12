package com.symmetricalpalmtree.notesproutsn.data.index

import com.symmetricalpalmtree.notesproutsn.data.export.ExportPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export-preset rows (arc 31 / HV3) against the real [IndexRepository] over an in-memory
 * `objects` table — the additive-row-type contract end to end: created by name, listed in name
 * order, a corrupt blob skipped rather than fatal, renamed with `updatedAt` bumped, soft-deleted
 * out of the listing, and duplicate names refused by the query the callers already use.
 */
class ExportPresetStoreTest {

    private val dao = FakeObjectDao()
    private val repo = IndexRepository(dao)

    private fun preset(exporter: String = "com.example.pdf") = ExportPreset(exporter = exporter)

    @Test
    fun `presets are listed by name, whatever order they were created in`() = runBlocking {
        repo.createExportPreset("Zebra", preset())
        repo.createExportPreset("apple", preset())
        repo.createExportPreset("Mango", preset())
        assertEquals(listOf("apple", "Mango", "Zebra"), repo.exportPresets().map { it.first.name })
    }

    @Test
    fun `a created row carries the type, the grammar version and no parent`() = runBlocking {
        val row = repo.createExportPreset("Drive PDF", preset())
        assertNotNull(row)
        assertEquals(ObjectType.EXPORT_PRESET, row!!.type)
        assertEquals(ExportPreset.VERSION, row.flags)
        assertNull(row.parentId)
        assertNull(row.deletedAt)
        assertEquals(preset(), repo.exportPresets().single().second)
    }

    @Test
    fun `a corrupt blob is skipped and the rest of the list survives`() = runBlocking {
        repo.createExportPreset("Good", preset())
        dao.upsert(
            ObjectEntity(
                id = "broken", type = ObjectType.EXPORT_PRESET, name = "Broken", parentId = null,
                createdAt = 0L, updatedAt = 0L, flags = 1, blob = "not json".toByteArray(),
            )
        )
        assertEquals(listOf("Good"), repo.exportPresets().map { it.first.name })
    }

    @Test
    fun `a rename bumps updatedAt and nothing else does`() = runBlocking {
        val row = repo.createExportPreset("Drive PDF", preset(), now = 100L)!!
        assertEquals(100L, dao.byId(row.id)?.updatedAt)
        repo.renameExportPreset(row.id, "Drive PDF v2", now = 500L)
        val renamed = dao.byId(row.id)
        assertEquals("Drive PDF v2", renamed?.name)
        assertEquals(500L, renamed?.updatedAt)
        assertEquals(100L, renamed?.createdAt)
    }

    @Test
    fun `a delete is soft and drops the preset from the listing`() = runBlocking {
        val row = repo.createExportPreset("Drive PDF", preset())!!
        repo.deleteExportPreset(row.id, now = 900L)
        assertTrue(repo.exportPresets().isEmpty())
        // The row itself stays — the family's rule, and what makes a restore honest.
        assertEquals(900L, dao.byId(row.id)?.deletedAt)
    }

    @Test
    fun `a duplicate name is refused, and the name frees up again after a delete`() = runBlocking {
        val row = repo.createExportPreset("Drive PDF", preset())!!
        assertTrue(repo.nameTaken(null, ObjectType.EXPORT_PRESET, "Drive PDF"))
        // The rename's own question: the row being renamed is not its own duplicate.
        assertFalse(repo.nameTaken(null, ObjectType.EXPORT_PRESET, "Drive PDF", excludeId = row.id))
        repo.deleteExportPreset(row.id)
        assertFalse(repo.nameTaken(null, ObjectType.EXPORT_PRESET, "Drive PDF"))
    }

    /** Presets are invisible to every other listing, as every additive row type is. */
    @Test
    fun `a preset is not a notebook, a folder or a template`() = runBlocking {
        repo.createExportPreset("Drive PDF", preset())
        assertTrue(repo.allNotebooks().isEmpty())
        assertTrue(repo.allFolders().isEmpty())
        assertTrue(repo.allTemplates().isEmpty())
        assertTrue(repo.notebooks(null).isEmpty())
    }
}
