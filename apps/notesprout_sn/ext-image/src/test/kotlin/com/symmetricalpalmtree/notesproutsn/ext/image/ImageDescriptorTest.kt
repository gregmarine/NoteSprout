package com.symmetricalpalmtree.notesproutsn.ext.image

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor's shape, pinned here rather than on a device: what the host draws is entirely this
 * list, and the two tails decide how the host *calls* this exporter at all — a wrong `delivery`
 * would hand a whole notebook to a format that is one picture.
 */
class ImageDescriptorTest {

    @Test
    fun theDescriptorIsAPerPagePngOverTheHostRenderedBundle() {
        val info = ImageDescriptor.info()
        assertEquals("PNG image", info.formatLabel)
        assertEquals("png", info.fileExtension)
        assertEquals("image/png", info.mimeType)
        // An image exporter can never receive the .soil: no key crosses the seam.
        assertEquals(ExporterContract.SOURCE_PAGES, info.sourceKind)
        // Version 1 on purpose: a PNG has no place for an endnote, so the host plans none.
        assertEquals(PageBundle.VERSION_1, info.bundleVersion)
        // The arc-31 tail: one file per page, so the host splits and calls once per page.
        assertEquals(ExporterContract.DELIVERY_PER_PAGE, info.delivery)
    }

    @Test
    fun theOneOptionIsTheHostExecutedPaperToggle() {
        val info = ImageDescriptor.info()
        assertEquals(listOf(ExporterContract.OPTION_PAGE_TEMPLATE), info.options.map { it.id })
        assertTrue(info.options.all { it.kind == ExporterContract.KIND_TOGGLE })
        assertTrue(info.options.all { it.choiceIds.isEmpty() && it.choiceLabels.isEmpty() })
        // The page as it was written.
        assertEquals("1", info.options.single().defaultValue)
    }

    @Test
    fun noKeyingAndNoPasswordOptionIsDeclared() {
        // The keying trio is `.soil`-specific, and a PNG has no password at all.
        assertTrue(ImageDescriptor.options.none { it.id == ExporterContract.OPTION_KEYING })
        assertTrue(ImageDescriptor.options.none { it.id == ExporterContract.OPTION_PROTECT })
    }

    @Test
    fun theDeclaredOptionsAndTheSupportedSetAreTheSameSet() {
        // A control exists in both places or in neither: an offered id missing from the spec would
        // be refused the moment a user touched it, and a supported id the descriptor never offers
        // could only arrive from a host that made it up.
        assertEquals(ImageDescriptor.options.map { it.id }.toSet(), ImageExportSpec.SUPPORTED_OPTIONS)
    }
}
