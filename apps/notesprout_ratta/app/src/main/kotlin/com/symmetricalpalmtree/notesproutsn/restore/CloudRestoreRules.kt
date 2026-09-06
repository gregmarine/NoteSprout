package com.symmetricalpalmtree.notesproutsn.restore

/**
 * **What the cloud leg's two listings mean** (arc 27 / L4, D6) — pure, so the enumeration's shape is
 * pinned by JVM test rather than reasoned about behind a Binder call, exactly as
 * `CloudBackupRules` pins the write side's.
 *
 * [CloudRestoreSource] owns everything with a side effect: the `list` calls, the downloads, the
 * failure mapping. Nothing here touches a file, a database or a provider.
 *
 * Two rules and no more, because there are only two questions the listings answer:
 *
 *  - **[deviceFolders]** — `Backups/` holds one folder per device that has ever backed up here, and
 *    nothing else this app put there. A *file* at that level is somebody else's and is ignored, not
 *    refused: a person's cloud is their own and a stray file in it is not a broken backup. The order
 *    is by name, so the chooser's rows do not move between two showings of the same account.
 *  - **[rowFor]** — a device folder's listing is a backup exactly when [RestoreManifest] can plan
 *    against it, which is to say exactly when it holds `notesprout.db`. The manifest is planned on
 *    [RestoreLeg.CLOUD], so no `-wal` is ever counted, weighed or fetched (R3).
 */
object CloudRestoreRules {

    /**
     * The device folders under `Backups/`, by name. Folder entries only — the enumeration is one
     * level deep on this leg by construction, because the writer's own path is `Backups/<device>/`
     * and nothing is ever nested below it.
     */
    fun deviceFolders(backupsListing: List<Listed>): List<Listed> =
        backupsListing.filter { it.isDir }.sortedBy { it.name }

    /**
     * One chooser row for the device folder [name] whose listing is [entries], or **null when it is
     * not a backup** — a folder with no `notesprout.db` in it is skipped, never an error: the
     * `Backups/` folder can hold a device that has only ever failed, and one such folder must not
     * hide the others.
     *
     * The row's [RestoreBackup.handle] is the folder's **name**, not the provider's entry id, and
     * that is deliberate: the fetch re-lists `Backups/<name>` from scratch (the L1 rule — what gets
     * staged must be what is actually there now), and a path is what `CloudClient.list` addresses,
     * the same way `CloudBackupLeg` addresses the folder it writes into.
     *
     * [RestoreBackup.indexModifiedAt] is the index entry's own modified time — the one timestamp
     * that says when this backup was last written, rather than when its folder was.
     */
    fun rowFor(name: String, entries: List<Listed>): RestoreBackup? {
        val manifest = RestoreManifest.plan(entries, RestoreLeg.CLOUD) ?: return null
        val index = entries.first { !it.isDir && it.name == RestoreManifest.INDEX_NAME }
        return RestoreBackup(
            name = name,
            notebookCount = manifest.notebookCount,
            indexModifiedAt = index.modifiedAt,
            totalBytes = manifest.totalBytes,
            handle = name,
        )
    }
}
