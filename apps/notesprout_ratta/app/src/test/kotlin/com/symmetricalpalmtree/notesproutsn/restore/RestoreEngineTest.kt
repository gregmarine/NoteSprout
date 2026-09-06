package com.symmetricalpalmtree.notesproutsn.restore

import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.restore.RestoreEngine.Problem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The engine's pure parts (L2): the two free-space gates and the validation rule. The swap itself
 * is renames against a real volume and is proved on the Nomad in L5's failure-injection pass.
 */
class RestoreEngineTest {

    private val mb = 1L shl 20
    private val headroom = RestoreStaging.HEADROOM_BYTES

    // ── Free space ───────────────────────────────────────────────────────────

    @Test
    fun `pre-fetch gate - fits when total plus headroom is within usable`() {
        assertNull(RestoreEngine.spaceProblem(totalBytes = 100 * mb, usableBytes = 100 * mb + headroom))
    }

    @Test
    fun `pre-fetch gate - one byte short names the shortfall`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 100 * mb, usableBytes = 100 * mb + headroom - 1)
        assertEquals(Problem.NotEnoughSpace(1L), p)
    }

    @Test
    fun `pre-fetch gate - shortfall is what is missing, not what was asked`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 500 * mb, usableBytes = 200 * mb)
        assertEquals(Problem.NotEnoughSpace(300 * mb + headroom), p)
    }

    @Test
    fun `pre-fetch gate - unknown usable refuses`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 1 * mb, usableBytes = -1L)
        assertTrue(p is Problem.NotEnoughSpace)
    }

    @Test
    fun `pre-fetch gate - unknown total refuses`() {
        assertTrue(RestoreEngine.spaceProblem(totalBytes = -1L, usableBytes = 10_000 * mb) is Problem.NotEnoughSpace)
    }

    @Test
    fun `post-stage gate - only the headroom must still fit`() {
        assertNull(RestoreEngine.headroomProblem(usableBytes = headroom))
        assertEquals(Problem.NotEnoughSpace(1L), RestoreEngine.headroomProblem(usableBytes = headroom - 1))
    }

    @Test
    fun `headroom is og's 64 MB`() {
        assertEquals(64L shl 20, headroom)
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("restore-engine-test").toFile()
        File(staging, "Garden").mkdirs()
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
    }

    private fun stage(item: Item, bytes: Int = item.size.toInt()) {
        val f = RestoreStaging.targetFor(staging, item)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    private val index = Item("notesprout.db", 4096, ItemKind.INDEX, "notesprout.db")
    private val indexWal = Item("notesprout.db-wal", 100, ItemKind.INDEX_WAL, "notesprout.db-wal")
    private val soil = Item("a1.soil", 8192, ItemKind.SOIL, "Garden/a1.soil")
    private val soilWal = Item("a1.soil-wal", 50, ItemKind.SOIL_WAL, "Garden/a1.soil-wal")
    private val store = Item("com.x.ext.db", 2048, ItemKind.STORE, "Garden/com.x.ext.db")

    private val allEncrypted: (File) -> SoilFileKind = { SoilFileKind.Encrypted }

    @Test
    fun `every main file encrypted and every file present - passes`() {
        val manifest = RestoreManifest(listOf(index, indexWal, soil, soilWal, store))
        manifest.items.forEach { stage(it) }
        assertNull(RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `an Invalid notebook fails the whole restore by name`() {
        val manifest = RestoreManifest(listOf(index, soil, store))
        manifest.items.forEach { stage(it) }
        val probe: (File) -> SoilFileKind = { if (it.name == "a1.soil") SoilFileKind.Invalid else SoilFileKind.Encrypted }
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, probe))
    }

    @Test
    fun `a Plaintext file is refused too - SN has no plaintext mode`() {
        val manifest = RestoreManifest(listOf(index, store))
        manifest.items.forEach { stage(it) }
        val probe: (File) -> SoilFileKind = { if (it.name == "com.x.ext.db") SoilFileKind.Plaintext else SoilFileKind.Encrypted }
        assertEquals(Problem.InvalidFile("com.x.ext.db"), RestoreEngine.validationProblem(staging, manifest, probe))
    }

    @Test
    fun `a Plaintext index is refused`() {
        val manifest = RestoreManifest(listOf(index))
        stage(index)
        assertEquals(Problem.InvalidFile("notesprout.db"), RestoreEngine.validationProblem(staging, manifest) { SoilFileKind.Plaintext })
    }

    @Test
    fun `a missing staged file fails by name - WAL included`() {
        val manifest = RestoreManifest(listOf(index, soil, soilWal))
        stage(index); stage(soil) // the WAL never landed
        assertEquals(Problem.InvalidFile("a1.soil-wal"), RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `a short staged file fails by name`() {
        val manifest = RestoreManifest(listOf(index, soil))
        stage(index); stage(soil, bytes = 8000)
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `an unreported size is not held against the file`() {
        val unsized = soil.copy(size = -1L)
        val manifest = RestoreManifest(listOf(index, unsized))
        stage(index); stage(unsized, bytes = 123)
        assertNull(RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `WAL files are never probed`() {
        val manifest = RestoreManifest(listOf(index, indexWal, soil, soilWal))
        manifest.items.forEach { stage(it) }
        val probed = ArrayList<String>()
        assertNull(RestoreEngine.validationProblem(staging, manifest) { probed += it.name; SoilFileKind.Encrypted })
        assertEquals(listOf("notesprout.db", "a1.soil"), probed)
    }

    @Test
    fun `the first bad file is the one named, in manifest order`() {
        val manifest = RestoreManifest(listOf(index, soil, store))
        manifest.items.forEach { stage(it) }
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest) { if (it.name == "notesprout.db") SoilFileKind.Encrypted else SoilFileKind.Invalid })
    }

    @Test
    fun `the RESTORE limiter bucket is its own`() {
        assertEquals("RESTORE", RestoreEngine.LIMITER_KEY)
    }
}
