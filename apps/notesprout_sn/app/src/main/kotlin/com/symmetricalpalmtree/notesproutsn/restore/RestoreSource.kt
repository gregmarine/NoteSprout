package com.symmetricalpalmtree.notesproutsn.restore

import java.io.File

/** Why a listing or a fetch could not finish — every one of these is a message, never a throw. */
sealed class RestoreProblem {
    /** The tree grant is gone, the folder was deleted, the volume was ejected. */
    object SourceUnreachable : RestoreProblem()

    /** The directory itself would not enumerate — never confused with "the folder is empty". */
    object ListingFailed : RestoreProblem()

    /** Nothing at the root or one level down holds `notesprout.db` (D1's `dev/` rule). */
    object NotABackup : RestoreProblem()

    /** One file did not stage, which fails the whole fetch. [fileName] is a UUID or a store
     *  package name — safe to log, unlike the URI it came from. */
    data class FetchFailed(val fileName: String) : RestoreProblem()

    // The four cloud kinds (arc 27 / L4), mapping **exactly** as `CloudBackupLeg.problemFor` does
    // — one table for both directions of the same seam, so a person reading "didn't answer" on the
    // Restore screen is being told the same thing a backup run would have told them.

    /** No account is connected to the provider (or its token was revoked out from under it). */
    object CloudNotConnected : RestoreProblem()

    /** The provider could not reach its service. Nothing was read; trying again is safe. */
    object CloudNetwork : RestoreProblem()

    /** The provider did not answer at all — nothing is known, so nothing is claimed. */
    object CloudUnanswered : RestoreProblem()

    /** The provider is no longer installed on this device (discovery no longer finds it). */
    object CloudGone : RestoreProblem()
}

/** What [RestoreSource.listBackups] answers. */
sealed class ListResult {
    data class Backups(val backups: List<RestoreBackup>) : ListResult()
    data class Failed(val problem: RestoreProblem) : ListResult()
}

/** What [RestoreSource.fetchInto] answers. */
sealed class FetchResult {
    /** Every manifest item is on disk under the staging root, complete and under its real name. */
    data class Staged(val manifest: RestoreManifest) : FetchResult()
    data class Failed(val problem: RestoreProblem) : FetchResult()
}

/**
 * Where a restore reads from (arc 27 / L1 + L4, D6): one interface, two implementations —
 * [SafRestoreSource] over a picked SAF tree, and L4's cloud source over `CloudClient.list` /
 * `.download`. **The engine (L2) knows neither**: it asks for a list, asks for a fetch, and then
 * works entirely against the staged files.
 *
 * The manifest a fetch returns is **re-planned from a fresh listing at fetch time**, not carried
 * over from the enumeration — a folder can change under a user who is reading a chooser, and the
 * set that gets staged must be the set that was actually there.
 */
interface RestoreSource {

    /** Every backup this source can offer, in a stable order. */
    suspend fun listBackups(): ListResult

    /**
     * Stage every manifest item of [backup] under [staging] (already reset by [RestoreStaging]).
     * **Any single file failing fails the whole fetch** — a short set must never be committed as a
     * library, which is the one way this arc could silently destroy data. Progress is
     * `(done, total)` over the manifest.
     */
    suspend fun fetchInto(
        backup: RestoreBackup,
        staging: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): FetchResult
}
