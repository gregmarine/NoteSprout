package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.export.ExportPreset

/**
 * **What a preset is, in screen terms** (arc 31 / HV3) — the pure half of the Preset row, so that
 * the four rules it stands on are pinned by JVM test rather than by a device walk.
 *
 * One [State] is *everything the Export screen asks that a preset may remember*: the exporter, its
 * option values, the host's Source answer, the destination and the cloud folder. The scope is
 * deliberately not in it (the door's question, never a saved one) and neither is any secret — see
 * [ExportPreset] for why that filter is structural rather than a blacklist.
 *
 * The rules:
 *
 *  - **[listable]**: a preset is shown only when its exporter is *listed on this screen right now*
 *    — which is one question, not two, because the screen's candidate list is already cut by what
 *    is installed **and** by what the current scope allows (a Soil exporter is not listed at page
 *    scope, so neither is a Soil preset there). GONE rather than disabled, the family rule: a
 *    preset that cannot be applied is one row fewer, and it comes back the moment its exporter
 *    does.
 *  - **[capture]** takes the state verbatim, cloud folder included even when the destination is
 *    local — the screen keeps that folder for the flip back, and a capture that quietly dropped it
 *    would not be a picture of the screen.
 *  - **[apply]** answers what to adopt, and the one thing it may overrule: a preset naming the
 *    cloud with no connected provider applies as **local**, saying so ([Applied.cloudFallback] —
 *    the caller's toast). The folder is kept even then: the account may come back, and forgetting
 *    it would cost the user the walk through the browser as well as the connection.
 *  - **[rowVisible]**: the radio row exists only with something to list. The *Save preset…* action
 *    is not this question — it is always available, and the screen draws it whether or not the row
 *    above it has any rows in it.
 */
object ExportPresets {

    /** The screen state a preset captures and applies — nothing else. */
    data class State(
        /** The chosen exporter's package name. */
        val exporter: String,
        /** The panel's option values, in the exporter's declaration order. */
        val values: Map<String, String>,
        /** The host's Source answer: false = pages, true = the document laid out on paper. */
        val documentSource: Boolean,
        val destination: ExportDestination.Choice,
        /** The cloud folder as names under the provider's root, or null = ask at export. */
        val cloudPath: List<String>?,
    )

    /** One stored preset as the repository answers it — its row identity and its blob. */
    class Row(val id: String, val name: String, val preset: ExportPreset)

    /** One preset the row will draw a radio for. */
    class Listed(val id: String, val name: String, val preset: ExportPreset)

    /** What [apply] answers: the state to adopt, and whether the cloud was overruled. */
    class Applied(val state: State, val cloudFallback: Boolean)

    /**
     * Which of [rows] the Preset row shows: those whose exporter is among [listedPackages] — the
     * packages the screen's own candidate list holds, already cut by installation and by scope.
     * Order is [rows]' own (the query's, by name).
     */
    fun listable(rows: List<Row>, listedPackages: Set<String>): List<Listed> =
        rows.filter { it.preset.exporter in listedPackages }.map { Listed(it.id, it.name, it.preset) }

    /** The preset to store for [state] — a picture of the screen, taken verbatim. */
    fun capture(state: State): ExportPreset = ExportPreset(
        exporter = state.exporter,
        values = LinkedHashMap(state.values),
        documentSource = state.documentSource,
        destination =
            if (state.destination == ExportDestination.Choice.CLOUD) ExportPreset.DESTINATION_CLOUD
            else ExportPreset.DESTINATION_LOCAL,
        cloudPath = state.cloudPath,
    )

    /**
     * The state to adopt for [preset]. The destination falls to
     * [ExportDestination.Choice.LOCAL] — with [Applied.cloudFallback] set, so the caller can say
     * so — when the preset names the cloud and [cloudAvailable] is false.
     */
    fun apply(preset: ExportPreset, cloudAvailable: Boolean): Applied {
        val wantsCloud = preset.destination == ExportPreset.DESTINATION_CLOUD
        val cloud = wantsCloud && cloudAvailable
        return Applied(
            State(
                exporter = preset.exporter,
                values = LinkedHashMap(preset.values),
                documentSource = preset.documentSource,
                destination = if (cloud) ExportDestination.Choice.CLOUD else ExportDestination.Choice.LOCAL,
                cloudPath = preset.cloudPath,
            ),
            cloudFallback = wantsCloud && !cloudAvailable,
        )
    }

    /** The radio row is on screen only with at least one listable preset. */
    fun rowVisible(listed: List<Listed>): Boolean = listed.isNotEmpty()
}
