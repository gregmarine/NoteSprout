package com.symmetricalpalmtree.notesproutsn.ext.image

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * What this exporter will accept in an [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec]
 * — pure, so it is JVM-tested rather than device-tested (arc 31 / D1, the `:ext-pdf` rule).
 *
 * **An option this build cannot act on is refused, not ignored** — the opposite of the soil
 * exporter's forward-compat rule, and deliberately so. There, every declared option was
 * *host-executed*: the transform had already run and an unknown key changed nothing the extension
 * did. Here an unknown id can only have come from a host that made it up, or from a future
 * descriptor this build does not implement, and either way answering "exported" to a question this
 * process did not understand is a success report for work nobody did. [SUPPORTED_OPTIONS] is
 * therefore [ImageDescriptor]'s ids exactly — a control exists in both places or in neither.
 *
 * **No secret is ever expected here.** A PNG has no password, so this exporter declares no protect
 * toggle and a non-null export secret is a spec that disagrees with the descriptor it was built
 * from: it is refused rather than silently dropped, because dropping it would write an unprotected
 * file for a user who may believe otherwise.
 *
 * Every refusal is an [IllegalArgumentException] because that is one of the three shapes that
 * actually reach the host; a non-marshalable exception kills the transaction silently and the host
 * reads an empty reply as success. **No message ever names, quotes or measures the secret** — not
 * its length, not whether it was blank: an id is the contract, a secret is never anything but
 * present or absent.
 */
internal object ImageExportSpec {

    /** Option ids this build can act on — [ImageDescriptor]'s one, and nothing else. */
    val SUPPORTED_OPTIONS: Set<String> = setOf(
        ExporterContract.OPTION_PAGE_TEMPLATE,
    )

    /**
     * @throws IllegalArgumentException if the spec asks for an option this exporter never declared,
     *   or carries an export secret, which nothing here ever asks for.
     */
    fun require(values: Map<String, String>, exportSecret: String?) {
        val unknown = values.keys.filter { it !in SUPPORTED_OPTIONS }.sorted()
        require(unknown.isEmpty()) {
            "options not offered by this exporter: ${unknown.joinToString(", ")}"
        }
        require(exportSecret == null) { "an export secret arrived that nothing asked for" }
    }
}
