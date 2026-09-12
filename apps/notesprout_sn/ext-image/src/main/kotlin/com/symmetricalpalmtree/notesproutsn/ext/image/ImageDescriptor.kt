package com.symmetricalpalmtree.notesproutsn.ext.image

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle

/**
 * What this exporter answers `describe()` with — lifted out of the service so the shape is pinned
 * by a JVM test rather than by a device walk. Nothing here touches Android: the parcelables validate
 * in their constructors and are only *written* to a `Parcel` later, so a malformed descriptor fails
 * in this module's own tests instead of dropping the exporter silently on a Supernote.
 *
 * **One toggle, and nothing else** (arc 31 / D1). [ExporterContract.OPTION_PAGE_TEMPLATE] is
 * declared here and **executed by the host**: the bundle carries finished pixels, so paper is
 * either baked into the page or was never in it, and there is nothing this side could add or strip
 * afterwards. Default `"1"` — the page as written. There is deliberately no password option: a PNG
 * has no such thing, and no keying option either — the keying trio is `.soil`-specific and this
 * exporter never sees a file to key.
 *
 * `bundleVersion` = [PageBundle.VERSION_1] **on purpose**, not for lack of ambition: a PNG is one
 * picture with no place to put an endnote, so asking for the version-2 trailer would only make the
 * host bake pages nothing here could ever use. Declaring 1 is what tells `ExportRender` to plan
 * none.
 *
 * `delivery` = [ExporterContract.DELIVERY_PER_PAGE]: a raster format has no notion of a multi-page
 * document, so one export is one file *per page*. The host bakes once, splits the bundle, and calls
 * this exporter once per page with a one-page bundle and a fresh destination — the seam's
 * one-call-one-file contract is unchanged. That is also why the manifest declares
 * [ExporterContract.MIN_API_VERSION_FOR_DELIVERY]: a host that cannot split must not reach this
 * service at all.
 */
internal object ImageDescriptor {

    val options: List<OptionDescriptor> = listOf(
        OptionDescriptor(
            id = ExporterContract.OPTION_PAGE_TEMPLATE,
            label = "Include page template",
            kind = ExporterContract.KIND_TOGGLE,
            choiceIds = emptyList(),
            choiceLabels = emptyList(),
            defaultValue = "1",
        ),
    )

    fun info(): ExporterInfo = ExporterInfo(
        formatLabel = "PNG image",
        fileExtension = "png",
        mimeType = "image/png",
        options = options,
        sourceKind = ExporterContract.SOURCE_PAGES,
        bundleVersion = PageBundle.VERSION_1,
        delivery = ExporterContract.DELIVERY_PER_PAGE,
    )
}
