package com.symmetricalpalmtree.notesproutsn.ext.image

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule of the assembly that is pure enough to pin on the JVM (the decode, the size check
 * and the sync are all Android-bound): a per-page exporter is called with exactly one page, and a
 * bundle carrying more must never quietly become its first page.
 */
class ImageAssemblyTest {

    @Test
    fun oneMeansOne() {
        ImageAssembly.requireOnePage(1)
    }

    @Test
    fun aMultiPageBundleIsADeliveryFailureNotAFirstPage() {
        // An API-8 host reads the delivery tail as absent and would stream a whole notebook here;
        // writing page one and reporting success would claim the notebook was exported.
        val e = assertThrows(IllegalStateException::class.java) { ImageAssembly.requireOnePage(2) }
        assertTrue((e.message ?: "").contains("2"))
    }

    @Test
    fun anEmptyBundleIsARefusalToo() {
        assertThrows(IllegalStateException::class.java) { ImageAssembly.requireOnePage(0) }
    }
}
