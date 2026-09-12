package com.symmetricalpalmtree.notesproutsn.restore

import com.symmetricalpalmtree.notesproutsn.crypto.RekeyNames
import com.symmetricalpalmtree.notesproutsn.data.EXTENSION_STORE_SUFFIX
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupPredicates
import com.symmetricalpalmtree.notesproutsn.data.extensionStorePackage

/** One directory entry as either leg lists it. */
data class Listed(val name: String, val size: Long, val isDir: Boolean, val modifiedAt: Long)

/** What a taken file *is*, which is also what the commit does with it (arc 27 / L2). */
enum class ItemKind { INDEX, INDEX_WAL, SOIL, SOIL_WAL, STORE, STORE_WAL }

/**
 * One file the restore will stage. [relativePath] is where it lands under the staging root,
 * mirroring the live layout: `notesprout.db`, `notesprout.db-wal`, `Garden/<uuid>.soil`,
 * `Garden/<uuid>.soil-wal`, `Garden/<pkg>.db`, `Garden/<pkg>.db-wal`.
 */
data class Item(val name: String, val size: Long, val kind: ItemKind, val relativePath: String)

/**
 * What a restore will take out of a backup folder, decided from the listing alone (arc 27 / L1,
 * D1) — pure, deterministic, and the **only** thing that says what gets staged.
 *
 * A backup folder is not a curated set: it is whatever the writer left, plus whatever a killed run
 * stranded. So the rules are stated as filename shapes rather than as trust in the folder —
 * `notesprout.db` (its presence is what makes a folder *a backup*), `<uuid>.soil`, and
 * `<pkg>.db` where the stem passes the existing authority (`extensionStorePackage`, reused, never
 * re-derived). Everything else is left where it lies: a `SafBackupWriter` swap's `.part`/`.old`,
 * an arc-26 `SoilRekey` commit interrupted **on the source device** and copied along by a later
 * run (`.rekey.tmp` / `.old.bak`), any `-shm` or `-journal` (rebuilt on open and never copied by
 * the writer either), every directory, and anything unrecognised.
 *
 * The WAL rule is the one place the two legs differ; see [RestoreLeg].
 *
 * Nothing here opens a file, and nothing here knows which source produced the listing.
 * ("og" = the original app, whose restore this arc reshapes for SN; no code is copied from it.)
 */
class RestoreManifest(val items: List<Item>) {

    /** Sum of the item sizes, counting a size the provider would not report (< 0) as 0. */
    val totalBytes: Long = items.sumOf { if (it.size < 0L) 0L else it.size }

    /** How many notebooks the backup holds — what the chooser row shows. */
    val notebookCount: Int = items.count { it.kind == ItemKind.SOIL }

    /** How many extension stores travel with it (arc 21 / W5's backup set). */
    val storeCount: Int = items.count { it.kind == ItemKind.STORE }

    /** The global index — always present, because [plan] refuses a listing without one. */
    val index: Item = items.first { it.kind == ItemKind.INDEX }

    /** True when at least one size was unreported, so [totalBytes] is a floor, not a total. */
    val hasUnknownSizes: Boolean = items.any { it.size < 0L }

    companion object {

        /** The global index's name in a backup folder — the writer's own constant, reused. */
        const val INDEX_NAME = BackupPredicates.INDEX_NAME

        private const val INDEX_WAL_NAME = INDEX_NAME + BackupPredicates.WAL_SUFFIX

        private const val SOIL_SUFFIX = ".soil"
        private const val SOIL_WAL_SUFFIX = SOIL_SUFFIX + BackupPredicates.WAL_SUFFIX
        private const val STORE_WAL_SUFFIX = EXTENSION_STORE_SUFFIX + BackupPredicates.WAL_SUFFIX

        private const val SHM_SUFFIX = "-shm"
        private const val JOURNAL_SUFFIX = "-journal"

        /** A notebook file's stem is a UUID; nothing else is accepted as one. */
        private val SOIL_STEM = Regex("[A-Za-z0-9_-]+")

        /** Where the notebooks and the extension stores live, under the staging root and live. */
        private const val GARDEN = "Garden/"

        /**
         * True when [entries] are a backup folder's: a **non-directory** `notesprout.db` is in
         * them. Only the index decides this — an index `-wal` standing alone is nothing, a folder
         * of `.soil` files with no index is not a library, and a *directory* called
         * `notesprout.db` is somebody else's.
         */
        fun isBackup(entries: List<Listed>): Boolean =
            entries.any { !it.isDir && it.name == INDEX_NAME }

        /**
         * What a restore would take from [entries] on [leg], in staging order: the index, its WAL,
         * then each notebook (main then WAL) by name, then each store the same way. Null when
         * [isBackup] is false — there is nothing to plan against.
         */
        fun plan(entries: List<Listed>, leg: RestoreLeg): RestoreManifest? {
            if (!isBackup(entries)) return null

            val taken = ArrayList<Item>(entries.size)
            for (entry in entries) {
                val kind = kindOf(entry) ?: continue
                taken.add(Item(entry.name, entry.size, kind, relativePathFor(entry.name, kind)))
            }

            // The WAL rule: a sidecar is only ever staged with the main file it belongs to, and
            // the cloud leg drops every one of them (R3 — see RestoreLeg).
            val mainNames = taken.filter { !it.kind.isWal() }.mapTo(HashSet()) { it.name }
            val kept = taken.filter { item ->
                when {
                    !item.kind.isWal() -> true
                    leg == RestoreLeg.CLOUD -> false
                    else -> mainOf(item.name) in mainNames
                }
            }

            return RestoreManifest(ordered(kept))
        }

        // ── The rules, one filename at a time ────────────────────────────────

        private fun kindOf(entry: Listed): ItemKind? {
            if (entry.isDir) return null
            val name = entry.name
            // Refused first, by suffix, before any name is recognised. `.old.bak` ends in `.bak`,
            // not `.old`, so it needs its own test — it is not caught by the `.old` one.
            if (name.endsWith(BackupPredicates.PART_SUFFIX) ||
                name.endsWith(BackupPredicates.OLD_SUFFIX) ||
                name.endsWith(RekeyNames.TMP_SUFFIX) ||
                name.endsWith(RekeyNames.BAK_SUFFIX) ||
                name.endsWith(SHM_SUFFIX) ||
                name.endsWith(JOURNAL_SUFFIX)
            ) return null

            // The index is matched before the store rule, so `notesprout.db` is never read as an
            // extension store named `notesprout`.
            if (name == INDEX_NAME) return ItemKind.INDEX
            if (name == INDEX_WAL_NAME) return ItemKind.INDEX_WAL

            if (name.endsWith(SOIL_WAL_SUFFIX)) {
                return if (isSoilStem(name.dropLast(SOIL_WAL_SUFFIX.length))) ItemKind.SOIL_WAL else null
            }
            if (name.endsWith(SOIL_SUFFIX)) {
                return if (isSoilStem(name.dropLast(SOIL_SUFFIX.length))) ItemKind.SOIL else null
            }

            if (name.endsWith(STORE_WAL_SUFFIX)) {
                val main = name.dropLast(BackupPredicates.WAL_SUFFIX.length)
                return if (extensionStorePackage(main) != null) ItemKind.STORE_WAL else null
            }
            if (extensionStorePackage(name) != null) return ItemKind.STORE

            return null
        }

        private fun isSoilStem(stem: String): Boolean = SOIL_STEM.matches(stem)

        private fun relativePathFor(name: String, kind: ItemKind): String = when (kind) {
            ItemKind.INDEX, ItemKind.INDEX_WAL -> name
            else -> GARDEN + name
        }

        private fun ItemKind.isWal(): Boolean =
            this == ItemKind.INDEX_WAL || this == ItemKind.SOIL_WAL || this == ItemKind.STORE_WAL

        private fun mainOf(walName: String): String =
            walName.dropLast(BackupPredicates.WAL_SUFFIX.length)

        /**
         * Staging order, and it is the commit's order too: the index first (a restore that dies
         * before it lands has staged nothing worth committing), then each main file immediately
         * followed by its own WAL so a pair is never split across the progress count.
         */
        private fun ordered(items: List<Item>): List<Item> {
            val wals = items.filter { it.kind.isWal() }.associateBy { mainOf(it.name) }
            val out = ArrayList<Item>(items.size)
            fun emit(main: Item) {
                out.add(main)
                wals[main.name]?.let { out.add(it) }
            }
            items.firstOrNull { it.kind == ItemKind.INDEX }?.let(::emit)
            items.filter { it.kind == ItemKind.SOIL }.sortedBy { it.name }.forEach(::emit)
            items.filter { it.kind == ItemKind.STORE }.sortedBy { it.name }.forEach(::emit)
            return out
        }
    }
}
