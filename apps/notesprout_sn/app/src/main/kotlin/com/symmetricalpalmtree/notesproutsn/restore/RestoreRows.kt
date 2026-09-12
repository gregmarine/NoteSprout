package com.symmetricalpalmtree.notesproutsn.restore

/**
 * **What a restore source's listing means** (arc 27 / L4, D6; widened to both legs at arc 34 / L2)
 * — pure, so the enumeration's shape is pinned by JVM test rather than reasoned about behind a
 * Binder call or a `DocumentsContract` cursor, exactly as `CloudBackupRules` pins the write side's.
 *
 * [CloudRestoreSource] and [SafRestoreSource] own everything with a side effect: the listings, the
 * reads and downloads, the failure mapping. Nothing here touches a file, a database or a provider.
 *
 * Two rules and no more, because there are only two questions a listing answers:
 *
 *  - **[deviceFolders]** — the cloud leg's only: `Backups/` holds one folder per device that has
 *    ever backed up here, and nothing else this app put there. A *file* at that level is somebody
 *    else's and is ignored, not refused: a person's cloud is their own and a stray file in it is
 *    not a broken backup. The order is by name, so the chooser's rows do not move between two
 *    showings of the same account.
 *  - **[rowFor]** — a folder's listing is a backup exactly when [RestoreManifest] can plan against
 *    it, which is to say exactly when it holds `notesprout.db`. **Both legs ask it**: the leg is a
 *    parameter, because it is the manifest's `-wal` rule that differs (the cloud never holds a
 *    sidecar, R3) and not the question.
 */
object RestoreRows {

    /**
     * The device folders under `Backups/`, by name. Folder entries only — the enumeration is one
     * level deep on this leg by construction, because the writer's own path is `Backups/<device>/`
     * and nothing is ever nested below it.
     */
    fun deviceFolders(backupsListing: List<Listed>): List<Listed> =
        backupsListing.filter { it.isDir }.sortedBy { it.name }

    /**
     * One chooser row for the backup folder [name] whose listing is [entries], planned on [leg], or
     * **null when it is not a backup** — a folder with no `notesprout.db` in it is skipped, never
     * an error: `Backups/` can hold a device that has only ever failed, and a picked SAF tree can
     * hold anything at all; one such folder must not hide the others.
     *
     * [handle] is whatever the *source* will address the folder by when the fetch re-lists it from
     * scratch (the L1 rule — what gets staged must be what is actually there now): the folder's
     * **name** on the cloud leg (a path is what `CloudClient.list` takes, the same way
     * `CloudBackupLeg` addresses the folder it writes into), the tree `Uri` string on the SAF leg.
     * Never a provider's entry id.
     *
     * [RestoreBackup.indexModifiedAt] is the index entry's own modified time — the one timestamp
     * that says when this backup was last written, rather than when its folder was.
     */
    fun rowFor(name: String, entries: List<Listed>, leg: RestoreLeg, handle: String): RestoreBackup? {
        val manifest = RestoreManifest.plan(entries, leg) ?: return null
        val index = entries.first { !it.isDir && it.name == RestoreManifest.INDEX_NAME }
        return RestoreBackup(
            name = name,
            notebookCount = manifest.notebookCount,
            indexModifiedAt = index.modifiedAt,
            totalBytes = manifest.totalBytes,
            handle = handle,
        )
    }
}
