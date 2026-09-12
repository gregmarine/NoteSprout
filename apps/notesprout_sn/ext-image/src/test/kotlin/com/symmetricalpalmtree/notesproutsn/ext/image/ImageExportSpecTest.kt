package com.symmetricalpalmtree.notesproutsn.ext.image

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules this exporter enforces on its way in — refuse what this build cannot actually do. */
class ImageExportSpecTest {

    private val template = ExporterContract.OPTION_PAGE_TEMPLATE

    @Test
    fun theDeclaredOptionIsAccepted() {
        ImageExportSpec.require(mapOf(template to "1"), null)
        ImageExportSpec.require(mapOf(template to "0"), null)
        ImageExportSpec.require(emptyMap(), null)
    }

    @Test
    fun anOptionThisBuildCannotActOnIsRefused() {
        // Not ignored, unlike the soil exporter's host-executed options: an unknown id is either a
        // host that made it up or a descriptor this build does not implement, and answering
        // "exported" to either is a success report for work nobody did.
        for (values in listOf(
            mapOf("protect" to "1"),
            mapOf("keying" to "keep"),
            mapOf(template to "1", "future" to "x"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ImageExportSpec.require(values, null)
            }
        }
    }

    @Test
    fun theRefusalNamesTheOptionIdsAndNothingElse() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ImageExportSpec.require(mapOf("shred" to "1", "watermark" to "0"), null)
        }
        val message = e.message ?: ""
        // Ids, sorted, and no value: a value is the caller's data, an id is the contract.
        assertTrue(message.contains("shred"))
        assertTrue(message.contains("watermark"))
        assertTrue(message.indexOf("shred") < message.indexOf("watermark"))
        assertTrue("=" !in message && "0" !in message && "1" !in message)
    }

    @Test
    fun aSecretIsAlwaysRefusedBecauseNothingHereEverAsksForOne() {
        for (values in listOf(emptyMap(), mapOf(template to "1"), mapOf(template to "0"))) {
            assertThrows(IllegalArgumentException::class.java) {
                ImageExportSpec.require(values, "hunter2")
            }
        }
    }

    @Test
    fun theSecretRefusalNeverEchoesTheSecret() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ImageExportSpec.require(mapOf(template to "1"), "hunter2")
        }
        val message = e.message ?: ""
        // Not its text, not its length — a secret is never anything but present or absent.
        assertTrue("hunter2" !in message)
        assertTrue("7" !in message)
    }
}
