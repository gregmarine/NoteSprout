package com.symmetricalpalmtree.notesproutsn.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **A saved export preset** (arc 31 / HV3) — the blob of one
 * [com.symmetricalpalmtree.notesproutsn.data.index.ObjectType.EXPORT_PRESET] row, serialized as
 * kotlinx JSON exactly as [com.symmetricalpalmtree.notesproutsn.data.backup.BackupConfig] is.
 *
 * What a preset holds is *every question the Export screen asks that is not the scope*: which
 * exporter, the values of its options, the host's own Source answer, where the file goes and — for
 * the cloud — which folder. What it does **not** hold is the point of the shape:
 *
 *  - **Never a secret.** A rekey passphrase and an export password are not option values (the
 *    passphrase kind never reaches [values]; `ExportOptions.specValues` drops it at the source),
 *    and nothing else here could carry one. A preset for a protected export therefore applies with
 *    the password fields **empty**, for the user to type — which is the only honest thing a saved
 *    setting can do with a secret. The filter is structural rather than a blacklist: applying a
 *    preset merges [values] back through `ExportOptions.specValues`, which keeps only values that
 *    are *declared choices* of the exporter's own options, and a secret is never a declared choice.
 *  - **Never the scope.** This page versus the whole notebook is the door's question, answered by
 *    where the screen was opened from, and a saved answer could only ever contradict it.
 *
 * [decode] never throws and answers **null** for anything it cannot vouch for — absent, malformed,
 * a grammar this build does not know, a destination string it cannot read, or an empty exporter
 * name. Null means *skip this row*: one preset missing from a radio list is a small loss, and a
 * screen that crashes on a bad blob is not.
 *
 * Growing this row is additive, on `BackupConfig`'s rule: a new field **with a default** is
 * readable by an older build (`ignoreUnknownKeys`) and an older blob by a newer one (the default
 * fills in), so [VERSION] does not move for one. It moves only for a change an older build would
 * misread — and then an older build's [decode] answers null rather than guessing.
 */
@Serializable
data class ExportPreset(
    /** The grammar version of this blob. Written into the row's `flags` too. */
    val version: Int = VERSION,
    /** The chosen exporter's **package name** — the host's own handle on a candidate, never a
     *  label (labels are the extension's and change with a locale or an update). */
    val exporter: String,
    /** Option id → chosen value, the panel's own map. Re-validated against the live descriptor at
     *  apply time, so a value an exporter no longer declares becomes that option's default. */
    val values: Map<String, String> = emptyMap(),
    /** The host's Source answer: false = the notebook's pages, true = the document on paper. */
    val documentSource: Boolean = false,
    /** [DESTINATION_LOCAL] or [DESTINATION_CLOUD] — a string, not an ordinal, so the blob says what
     *  it means and an unknown one is refusable rather than silently the first enum constant. */
    val destination: String = DESTINATION_LOCAL,
    /** The cloud folder, as names under the provider's root (`["Exports", …]`), or **null** = ask
     *  at export, which is what every export did before this arc. */
    val cloudPath: List<String>? = null,
) {
    companion object {
        /** Preset grammar version. */
        const val VERSION = 1

        /** SAF on this device — the default, and what every preset means when it says nothing. */
        const val DESTINATION_LOCAL = "LOCAL"

        /** The connected provider's own tree. */
        const val DESTINATION_CLOUD = "CLOUD"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Encoded UTF-8 JSON. Null only if serialization itself fails (never expected) — the
         *  caller then writes no row at all rather than an unreadable one. */
        fun encode(preset: ExportPreset): ByteArray? = try {
            json.encodeToString(serializer(), preset).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

        /** The preset in [bytes], or null for anything this build cannot vouch for. See the class
         *  doc: null is *skip this row*, never a guess and never a throw. */
        fun decode(bytes: ByteArray?): ExportPreset? {
            if (bytes == null || bytes.isEmpty()) return null
            val preset = try {
                json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
            if (preset.version !in 1..VERSION) return null
            if (preset.exporter.isBlank()) return null
            if (preset.destination != DESTINATION_LOCAL && preset.destination != DESTINATION_CLOUD) return null
            return preset
        }
    }
}
