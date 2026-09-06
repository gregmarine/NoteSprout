package com.symmetricalpalmtree.notesproutsn.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the cloud leg's two listings mean (arc 27 / L4): which entries under `Backups/` are device
 * folders, and which device folders are backups.
 */
class CloudRestoreRulesTest {

    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"
    private val store = "com.symmetricalpalmtree.notesproutsn.ext.tags"

    private fun file(name: String, size: Long = 10L, modifiedAt: Long = 1L) =
        Listed(name, size, isDir = false, modifiedAt = modifiedAt)

    private fun folder(name: String) = Listed(name, 0L, isDir = true, modifiedAt = 1L)

    // ── deviceFolders ────────────────────────────────────────────────────────

    @Test
    fun `only folders are device folders, and they come back by name`() {
        val listing = listOf(folder("nomad"), file("readme.txt"), folder("manta"), folder("g102"))
        assertEquals(
            listOf("g102", "manta", "nomad"),
            CloudRestoreRules.deviceFolders(listing).map { it.name },
        )
    }

    @Test
    fun `a stray file at the Backups level is ignored, never refused`() {
        // A person's cloud is their own; something else's file in it is not a broken backup.
        val listing = listOf(file("notesprout.db"), file("holiday.pdf"))
        assertTrue(CloudRestoreRules.deviceFolders(listing).isEmpty())
    }

    @Test
    fun `an empty Backups folder has no device folders`() {
        assertTrue(CloudRestoreRules.deviceFolders(emptyList()).isEmpty())
    }

    // ── rowFor ───────────────────────────────────────────────────────────────

    @Test
    fun `a folder with no index is not a backup`() {
        val entries = listOf(file("$uuidA.soil"), file("$store.db"))
        assertNull(CloudRestoreRules.rowFor("nomad", entries))
    }

    @Test
    fun `an empty device folder is not a backup`() {
        assertNull(CloudRestoreRules.rowFor("nomad", emptyList()))
    }

    @Test
    fun `a row counts the notebooks and weighs what the listing said`() {
        val entries = listOf(
            file("notesprout.db", size = 100L, modifiedAt = 5_000L),
            file("$uuidA.soil", size = 20L),
            file("$uuidB.soil", size = 30L),
            file("$store.db", size = 7L),
        )
        val row = CloudRestoreRules.rowFor("nomad", entries)!!
        assertEquals("nomad", row.name)
        assertEquals(2, row.notebookCount)
        assertEquals(157L, row.totalBytes)
    }

    @Test
    fun `the row's date is the index entry's, not the folder's or another file's`() {
        val entries = listOf(
            file("$uuidA.soil", modifiedAt = 9_000L),
            file("notesprout.db", modifiedAt = 5_000L),
        )
        assertEquals(5_000L, CloudRestoreRules.rowFor("nomad", entries)!!.indexModifiedAt)
    }

    @Test
    fun `the handle is the device folder's name, because the fetch re-lists by path`() {
        val entries = listOf(file("notesprout.db"))
        assertEquals("nomad", CloudRestoreRules.rowFor("nomad", entries)!!.handle)
    }

    @Test
    fun `a stale sidecar in a cloud folder is neither counted nor weighed (R3)`() {
        val entries = listOf(
            file("notesprout.db", size = 100L),
            file("notesprout.db-wal", size = 4_000L),
            file("$uuidA.soil", size = 20L),
            file("$uuidA.soil-wal", size = 8_000L),
        )
        val row = CloudRestoreRules.rowFor("nomad", entries)!!
        assertEquals(1, row.notebookCount)
        // 120, not 12 120: a `-wal` in a cloud folder is stale by construction and never fetched,
        // so it must not be paid for in the free-space gate either.
        assertEquals(120L, row.totalBytes)
    }

    @Test
    fun `the refused shapes never reach a cloud row either`() {
        val entries = listOf(
            file("notesprout.db", size = 100L),
            file("$uuidA.soil.part", size = 1L),
            file("$uuidB.soil.old", size = 2L),
            file("$uuidA.soil.rekey.tmp", size = 4L),
            file("$uuidB.soil.old.bak", size = 8L),
            file("$uuidA.soil-shm", size = 16L),
        )
        val row = CloudRestoreRules.rowFor("nomad", entries)!!
        assertEquals(0, row.notebookCount)
        assertEquals(100L, row.totalBytes)
    }
}
