package com.symmetricalpalmtree.notesproutsn.restore

import android.content.ContentResolver
import android.net.Uri
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.backup.SafBackupReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The local leg's [RestoreSource] (arc 27 / L1, D6): a SAF tree the user just picked, read through
 * [SafBackupReader].
 *
 * **Enumeration is one level deep** (D1): the picked tree counts as a backup if it directly holds
 * `notesprout.db`, and each immediate subfolder that holds one counts too. That is og's rule, and
 * it is what makes a debug build's `dev/` subfolder reachable — and what lets a user pick a parent
 * holding several devices' folders. It stops there on purpose: a full-tree walk over a provider
 * costs one query per directory, and a backup is never nested deeper than the writer puts it.
 *
 * The listing is the only thing that decides anything. Nothing here opens a `.soil`, tries a key,
 * or touches the live library; a fetch writes into the staging directory and nowhere else.
 */
class SafRestoreSource(private val reader: SafBackupReader) : RestoreSource {

    constructor(resolver: ContentResolver, treeUri: Uri) : this(SafBackupReader(resolver, treeUri))

    override suspend fun listBackups(): ListResult = withContext(Dispatchers.IO) {
        val root = reader.root() ?: return@withContext ListResult.Failed(RestoreProblem.SourceUnreachable)
        val rootEntries = reader.list(root) ?: return@withContext ListResult.Failed(RestoreProblem.ListingFailed)

        val found = ArrayList<RestoreBackup>()
        backupOf(rootEntries, reader.rootName() ?: DEFAULT_NAME, root)?.let(found::add)

        for (sub in rootEntries.filter { it.isDir }.sortedBy { it.name }) {
            val subEntries = reader.list(sub.uri)
            if (subEntries == null) {
                // One unreadable subfolder is not a failed enumeration — the rest of the tree may
                // still hold the backup the user came for.
                Slog.d(TAG) { "skipping unreadable subfolder ${sub.name}" }
                continue
            }
            backupOf(subEntries, sub.name, sub.uri)?.let(found::add)
        }

        Slog.d(TAG) { "enumerated ${found.size} backup(s) one level deep" }
        if (found.isEmpty()) ListResult.Failed(RestoreProblem.NotABackup) else ListResult.Backups(found)
    }

    override suspend fun fetchInto(
        backup: RestoreBackup,
        staging: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): FetchResult = withContext(Dispatchers.IO) {
        val dir = Uri.parse(backup.handle)
        // Re-planned from a fresh listing, never from the enumeration's: a folder can change under
        // a user who is reading the chooser, and what gets staged must be what is actually there.
        val entries = reader.list(dir) ?: return@withContext FetchResult.Failed(RestoreProblem.ListingFailed)
        val manifest = RestoreManifest.plan(entries.map(::listed), RestoreLeg.LOCAL)
            ?: return@withContext FetchResult.Failed(RestoreProblem.NotABackup)

        val byName = entries.associateBy { it.name }
        val total = manifest.items.size
        var done = 0
        for (item in manifest.items) {
            val entry = byName[item.name]
                ?: return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            val target = RestoreStaging.targetFor(staging, item)
            val ok = reader.open(entry.uri)?.use { input ->
                RestoreStaging.writeStaged(target, item.size) { out -> input.copyTo(out) }
            } ?: false
            // Any single file failing fails the whole fetch — a short set must never be committed
            // as a library. No cleanup here: the engine resets staging before the next attempt.
            if (!ok) return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            done++
            onProgress(done, total)
        }
        Slog.d(TAG) { "staged $done file(s), ${manifest.totalBytes} B planned" }
        FetchResult.Staged(manifest)
    }

    /** A backup row for [entries] when they are a backup folder's, else null. */
    private fun backupOf(
        entries: List<SafBackupReader.Entry>,
        name: String,
        dirUri: Uri,
    ): RestoreBackup? {
        val listed = entries.map(::listed)
        val manifest = RestoreManifest.plan(listed, RestoreLeg.LOCAL) ?: return null
        val indexEntry = entries.first { !it.isDir && it.name == RestoreManifest.INDEX_NAME }
        return RestoreBackup(
            name = name,
            notebookCount = manifest.notebookCount,
            indexModifiedAt = indexEntry.lastModified,
            totalBytes = manifest.totalBytes,
            handle = dirUri.toString(),
        )
    }

    private fun listed(entry: SafBackupReader.Entry): Listed =
        Listed(entry.name, entry.size, entry.isDir, entry.lastModified)

    private companion object {
        const val TAG = "SafRestoreSource"
        const val DEFAULT_NAME = "Backup"
    }
}
