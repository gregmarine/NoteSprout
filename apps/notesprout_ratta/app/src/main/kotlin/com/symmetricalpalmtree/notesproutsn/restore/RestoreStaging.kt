package com.symmetricalpalmtree.notesproutsn.restore

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupPredicates
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Where a restore's fetched files wait before the commit installs them (arc 27 / L1, D2).
 *
 * **On the library's own volume, a sibling of `Garden/` and `notesprout.db`** — `getExternalFilesDir(null)/restore_staging/`
 * (R1; og stages in `cacheDir` and copies, and this plan first copied og). Staging beside the live
 * files makes L2's commit **renames only**: peak disk drops from old + staged + a new copy to just
 * old + staged, the kill window shrinks from a multi-hundred-megabyte copy to milliseconds, and the
 * free-space gate measures the one volume everything sits on. Nothing enumerates that directory —
 * `extensionStoreFiles` and `SoilRekey.recoverGarden` read `Garden/` only — so a leftover is
 * invisible to the library.
 *
 * The layout mirrors the live one, which is what [Item.relativePath] carries:
 * `restore_staging/notesprout.db` (+ `-wal`) and `restore_staging/Garden/…`.
 *
 * Every file streams to a `.part` name and renames on completion ([writeStaged]), so a dropped read
 * never leaves a truncated file under a name the commit would install. The directory is wiped and
 * recreated at the top of every attempt. **Nothing in here touches the live library.**
 */
object RestoreStaging {

    /** The directory name under the library volume. */
    const val DIR_NAME = "restore_staging"

    /**
     * og's 64 MB — the slack the commit and the reopened index want on the volume after the staged
     * copy has landed. An L2 phase-start question (the honest post-stage re-measure lives there).
     */
    const val HEADROOM_BYTES = 64L shl 20

    /** The staging directory under a library [root] (`getExternalFilesDir(null)`). */
    fun dir(root: File): File = File(root, DIR_NAME)

    /** The staging directory for this device's library volume. */
    fun dir(context: Context): File = dir(libraryRoot(context))

    /**
     * Wipe whatever a previous attempt left and recreate the tree, `Garden/` included, so a fetch
     * never has to create a directory mid-stream. Returns the directory.
     */
    fun reset(root: File): File {
        val dir = dir(root)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, GARDEN).mkdirs()
        return dir
    }

    /** [reset] for this device's library volume. */
    fun reset(context: Context): File = reset(libraryRoot(context))

    /** Best effort — a leftover is invisible to the library and the next attempt resets anyway. */
    fun discard(root: File) {
        if (!dir(root).deleteRecursively()) Log.w(TAG, "staging discard was incomplete")
    }

    /** [discard] for this device's library volume. */
    fun discard(context: Context) = discard(libraryRoot(context))

    /**
     * Where [item] lands under [stagingDir]. The relative path comes from the manifest, which only
     * ever mints the six shapes D1 allows — the `require` is the belt on that braces: a path that
     * canonicalises outside the staging directory would be a file written into the live library by
     * a restore that has not been committed yet.
     */
    fun targetFor(stagingDir: File, item: Item): File {
        val target = File(stagingDir, item.relativePath)
        val root = stagingDir.canonicalFile.path
        val resolved = target.canonicalFile.path
        require(resolved.startsWith(root + File.separator)) { "staged path escapes the staging directory" }
        return target
    }

    /**
     * Stream one file into place: [write] fills the `.part` and returns how many bytes it wrote,
     * the count is checked against [expectedSize] (and against what the part actually weighs) when
     * the source would say a size at all, and only then does the part take the real name.
     *
     * False for any failure — a short write, an unwritable path, an exception out of [write]. The
     * part is deleted on every failing path, and **nothing here throws**: the fetch turns a false
     * into `RestoreProblem.FetchFailed` and abandons the whole attempt.
     */
    fun writeStaged(target: File, expectedSize: Long, write: (OutputStream) -> Long): Boolean {
        val part = File(target.path + BackupPredicates.PART_SUFFIX)
        try {
            target.parentFile?.mkdirs()
            if (part.exists()) part.delete()
            val written = FileOutputStream(part).use { out ->
                val n = write(out)
                out.flush()
                out.fd.sync()
                n
            }
            val landed = part.length()
            if (expectedSize >= 0L && (written != expectedSize || landed != expectedSize)) {
                Log.w(TAG, "short staged write ($written written, $landed landed, $expectedSize expected)")
                part.delete()
                return false
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return false
            }
            if (!part.renameTo(target)) {
                part.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "staged write failed", e)
            part.delete()
            return false
        }
    }

    /**
     * [writeStaged]'s twin for a source that will not hand over an [OutputStream] (arc 27 / L4):
     * the cloud leg's `download` takes a **file descriptor** the provider streams into itself, so
     * there is no stream here for a caller to fill.
     *
     * The contract is deliberately identical to [writeStaged]'s, one word at a time: a `.part`
     * sibling is prepared and any stale one removed, [fill] is handed **that file** and answers how
     * many bytes it believes were written (a negative for "it failed", which is how a caller
     * reports its own typed failure without throwing), the count is checked against [expectedSize]
     * *and* against what the part actually weighs, and only then does the part take the real name.
     * The one difference is the fsync: the provider fsyncs the descriptor before it answers, so
     * there is no `fd` on this side to sync.
     *
     * False for any failure, the part deleted on every failing path, and **nothing here throws**
     * except a cancellation, which is always passed on: the fetch turns a false into
     * `RestoreProblem.FetchFailed` and abandons the whole attempt.
     */
    suspend fun writeStagedVia(
        target: File,
        expectedSize: Long,
        fill: suspend (part: File) -> Long,
    ): Boolean {
        val part = File(target.path + BackupPredicates.PART_SUFFIX)
        try {
            target.parentFile?.mkdirs()
            if (part.exists()) part.delete()
            val written = fill(part)
            val landed = part.length()
            if (written < 0L) {
                part.delete()
                return false
            }
            if (expectedSize >= 0L && (written != expectedSize || landed != expectedSize)) {
                Log.w(TAG, "short staged write ($written written, $landed landed, $expectedSize expected)")
                part.delete()
                return false
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return false
            }
            if (!part.renameTo(target)) {
                part.delete()
                return false
            }
            return true
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "staged write failed", e)
            part.delete()
            return false
        }
    }

    /**
     * The pre-flight gate (D2 / R1): does a backup of [totalBytes] fit in [usableBytes] with
     * [headroom] left over? Pure. An unknown size on either side (< 0) never fits — see
     * [usableBytes].
     */
    fun fits(totalBytes: Long, usableBytes: Long, headroom: Long = HEADROOM_BYTES): Boolean =
        totalBytes >= 0L && usableBytes >= 0L && usableBytes - headroom >= totalBytes

    /**
     * What the library volume has free, or **-1 for "unknown"** when the platform will not say.
     * This is only the primitive; treating unknown as *refuse* is the caller's call, and [fits]
     * answers false for it so the safe reading is the default one.
     */
    fun usableBytes(root: File): Long = try {
        StatFs(root.path).availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "usable-space query failed", e)
        -1L
    }

    /** [usableBytes] for this device's library volume. */
    fun usableBytes(context: Context): Long =
        usableBytes(libraryRoot(context))

    /** The library volume — the parent of `Garden/` and `notesprout.db` (`data/SoilFile.kt`'s
     *  own root), which is exactly what makes the commit's renames same-volume. */
    private fun libraryRoot(context: Context): File =
        checkNotNull(context.getExternalFilesDir(null)) { "no external files dir" }

    private const val GARDEN = "Garden"
    private const val TAG = "RestoreStaging"
}
