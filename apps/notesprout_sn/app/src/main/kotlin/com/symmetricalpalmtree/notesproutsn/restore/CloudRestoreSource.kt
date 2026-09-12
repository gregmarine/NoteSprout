package com.symmetricalpalmtree.notesproutsn.restore

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupPredicates
import com.symmetricalpalmtree.notesproutsn.extension.CloudClient
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudNetworkException
import com.symmetricalpalmtree.notesproutsn.extension.CloudNotConnectedException
import com.symmetricalpalmtree.notesproutsn.extension.CloudTimeouts
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.ProviderRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The cloud leg's [RestoreSource] (arc 27 / L4, D6): `Backups/<device folder>/` under the provider's
 * own root, read back through [CloudClient.list] and [CloudClient.download] and nothing else. It is
 * the read twin of `CloudBackupLeg`, and it addresses the same folders the same way.
 *
 * What is worth knowing before changing anything here:
 *
 *  - **The handle is the device folder's *name*, not the provider's entry id.** The fetch re-lists
 *    `Backups/<name>` from scratch rather than reusing the enumeration's listing (the L1 rule: a
 *    folder can change under a person who is reading a chooser, and what gets staged must be what
 *    is actually there), and a path is what `list` takes. An id would have to be carried, could go
 *    stale on a re-created folder, and would buy nothing.
 *  - **No `-wal` is ever fetched** (R3). Every manifest here is planned on [RestoreLeg.CLOUD]:
 *    `SelfContainedSnapshot` makes every uploaded main file complete, so a `-wal` sitting in a cloud
 *    folder is stale by construction, and pairing it with a fresh main file is exactly the
 *    corruption `CloudBackupLeg`'s stale-sidecar delete exists to prevent.
 *  - **One `list` per folder, and the `Backups/` listing is one call.** A `list` costs most of a
 *    second on this seam, so the enumeration is `1 + n` calls for n device folders and never more.
 *  - **A mid-fetch failure aborts the whole restore.** There is no partial staging: a short set
 *    committed as a library is the one way this arc could silently destroy data, so the first file
 *    that does not land ends the fetch. Nothing is cleaned up here — the engine resets staging
 *    before the next attempt and discards it on a refusal.
 *  - **The four failures are `CloudBackupLeg.problemFor`'s, exactly** ([problemFor] below): the two
 *    typed refusals say themselves, and a provider that did not answer at all is asked about once —
 *    if discovery no longer finds it, *gone* is the truthful word; otherwise nothing is known and
 *    the honest answer is that it did not answer.
 *
 * Nothing here logs a folder name, a file name, an account, an entry id or a URL — counts,
 * booleans and durations only. A `.soil` UUID or a store package reaches the screen inside
 * [RestoreProblem.FetchFailed], which is the one name that is safe to show.
 */
class CloudRestoreSource(
    private val app: Context,
    private val ref: ProviderRef,
) : RestoreSource {

    /**
     * Every device folder under `Backups/` that holds a backup, by name.
     *
     * A missing `Backups/` lists **empty** rather than failing — to the host a folder that was never
     * created and an empty one look the same — and an empty enumeration is [RestoreProblem.NotABackup],
     * the same answer a picked local folder with nothing in it gets.
     *
     * One subfolder that will not list is **skipped**, not fatal, exactly as [SafRestoreSource]
     * skips an unreadable subfolder: the rest of the account may still hold the backup the person
     * came for. The two typed refusals are the exception — a lost account or a dead link is gone for
     * every folder, so they end the enumeration where it stands. And when *nothing* was found but
     * something did fail, the failure is what is reported: a listing that failed is never confused
     * with an empty one.
     */
    override suspend fun listBackups(): ListResult = withContext(Dispatchers.IO) {
        val folders = try {
            CloudClient.list(app, ref, arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ListResult.Failed(problemFor(e))
        }

        val found = ArrayList<RestoreBackup>()
        var skipped = 0
        var lastProblem: RestoreProblem? = null
        for (folder in RestoreRows.deviceFolders(folders.map(::listed))) {
            val entries = try {
                CloudClient.list(app, ref, arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, folder.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNotConnectedException) {
                return@withContext ListResult.Failed(RestoreProblem.CloudNotConnected)
            } catch (e: CloudNetworkException) {
                return@withContext ListResult.Failed(RestoreProblem.CloudNetwork)
            } catch (e: Exception) {
                skipped++
                lastProblem = problemFor(e)
                continue
            }
            RestoreRows.rowFor(folder.name, entries.map(::listed), RestoreLeg.CLOUD, folder.name)?.let(found::add)
        }

        Slog.d(TAG) { "enumerated ${found.size} backup(s) in the cloud, $skipped folder(s) skipped" }
        when {
            found.isNotEmpty() -> ListResult.Backups(found)
            lastProblem != null -> ListResult.Failed(lastProblem)
            else -> ListResult.Failed(RestoreProblem.NotABackup)
        }
    }

    /**
     * Stage every file the fresh listing of `Backups/<handle>` plans, in manifest order.
     *
     * The listing is re-taken here and the manifest re-planned from it (the L1 rule), so the sizes
     * every check below is made against are the ones the provider is reporting *now*. Each file is
     * downloaded into a `.part` sibling and renamed on completion ([RestoreStaging.writeStagedVia]),
     * and three accounts must agree before the rename: what the provider says it wrote, what landed
     * on disk, and what the listing said the file weighs. A disagreement is
     * [RestoreProblem.FetchFailed] — a restore has no "check the file" ending to offer the way an
     * export does; it stages exactly what was listed or it refuses.
     */
    override suspend fun fetchInto(
        backup: RestoreBackup,
        staging: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): FetchResult = withContext(Dispatchers.IO) {
        val path = arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, backup.handle)
        val entries = try {
            CloudClient.list(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext FetchResult.Failed(problemFor(e))
        }
        val manifest = RestoreManifest.plan(entries.map(::listed), RestoreLeg.CLOUD)
            ?: return@withContext FetchResult.Failed(RestoreProblem.NotABackup)

        val byName = entries.filter { !it.isFolder }.associateBy { it.name }
        val total = manifest.items.size
        var done = 0
        for (item in manifest.items) {
            val entry = byName[item.name]
                ?: return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            val target = RestoreStaging.targetFor(staging, item)
            // A typed cloud failure is carried out of the fill rather than thrown through it, so
            // the staging helper keeps its "nothing here throws" contract and the screen still gets
            // the named problem instead of a bare "couldn't copy".
            var failure: RestoreProblem? = null
            val ok = RestoreStaging.writeStagedVia(target, item.size) { part ->
                val pfd = runCatching {
                    ParcelFileDescriptor.open(
                        part,
                        ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                }.getOrNull()
                if (pfd == null) {
                    Log.w(TAG, "could not open a staging part for writing")
                    return@writeStagedVia -1L
                }
                // The client owns the descriptor from here and closes it on every path, refusals
                // included. The budget is the read-side rate, scaled by what the listing said.
                try {
                    CloudClient.download(app, ref, entry.id, pfd, CloudTimeouts.downloadBudgetMs(item.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = problemFor(e)
                    -1L
                }
            }
            if (!ok) {
                // Any single file failing fails the whole fetch — a short set must never be
                // committed as a library. No cleanup: the engine resets staging before the next
                // attempt and discards it on a refusal.
                return@withContext FetchResult.Failed(failure ?: RestoreProblem.FetchFailed(item.name))
            }
            done++
            onProgress(done, total)
        }
        Slog.d(TAG) { "staged $done file(s) from the cloud, ${manifest.totalBytes} B planned" }
        FetchResult.Staged(manifest)
    }

    private fun listed(entry: CloudEntry): Listed =
        Listed(entry.name, entry.sizeBytes, entry.isFolder, entry.modifiedAt)

    /**
     * Which of the four cloud problems [e] is — the one mapping, used by both the listing and the
     * fetch, and the same table `CloudBackupLeg.problemFor` keeps. Anything that is not one of the
     * three known shapes is logged at `Log.w` (it survives into release) and reported as "did not
     * answer", because that is all that is actually known about it.
     */
    private suspend fun problemFor(e: Exception): RestoreProblem {
        val problem = when (e) {
            is CloudNotConnectedException -> RestoreProblem.CloudNotConnected
            is CloudNetworkException -> RestoreProblem.CloudNetwork
            is ExtensionCallException ->
                if (ExtensionRegistry.cloud(app) == null) RestoreProblem.CloudGone
                else RestoreProblem.CloudUnanswered

            else -> {
                Log.w(TAG, "cloud restore source failed unexpectedly", e)
                RestoreProblem.CloudUnanswered
            }
        }
        Slog.d(TAG) { "cloud source problem: ${problem.javaClass.simpleName}" }
        return problem
    }

    private companion object {
        const val TAG = "CloudRestoreSource"
    }
}
