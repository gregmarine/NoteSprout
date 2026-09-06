package com.symmetricalpalmtree.notesproutsn.restore

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.AttemptLimiter
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalKey
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalRotation
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookUnlocks
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseCache
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.crypto.RealRekeyFs
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.gardenDir
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.data.indexFile
import com.symmetricalpalmtree.notesproutsn.data.sidecarsOf
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The commit engine** (arc 27 / L2, D3 / D5) — the inverse of the backup run: stage a chosen
 * backup, prove it, replace the library with it. Five doors in the order a caller (L3's screen)
 * walks them, and **nothing live is touched before [commit]**:
 *
 *  1. [preflight] — refuses by name while a rotation marker stands (it names files the restore is
 *     about to delete), while any `.soil` is held open, or when the listing's bytes plus headroom
 *     will not fit the library volume (D2 / R1). Nothing staged.
 *  2. [stage] — the source fetches every manifest item into `restore_staging/` beside the live
 *     library (D2); any single failure fails the whole fetch.
 *  3. [validate] — `SoilCrypto.probe` on every staged main file; anything that is not an encrypted
 *     SQLite file fails the restore by name (L2's phase-start answer: a bad notebook is a library
 *     that lies — fail whole, never skip). Nothing is read deeper without a key.
 *  4. [proveCached] / [proveTyped] — decision 6: the *staged* index must open before anything is
 *     committed. The cached global is tried silently (a same-device backup just works); otherwise
 *     the screen prompts and [proveTyped] verifies **as typed first, then normalized** (R6 — a
 *     library may carry a typed passphrase) under `AttemptLimiter("RESTORE")`. No key → no commit.
 *  5. [commit] — the point of no return is step 8's first rename. Blind the process
 *     (`KeySession.clear()`, R2) → close every store and the index → aside by rename → install by
 *     rename, the index **last** as the commit marker → key state → discard the aside (decision 5,
 *     no undo). A failure inside the swap renames the aside back in-process (D5's plan) and
 *     answers [Outcome.RolledBack]; the caller relaunches either way (R4 — `SnIndex` has no reopen
 *     door but Bootstrap's).
 *
 * Every door catches at its top and answers a [Problem]; none throws to the caller. No passphrase
 * is logged, put in an Intent, or written anywhere but `PassphraseStore`.
 *
 * [recoverInterrupted] is the launch-time twin: the first thing `BootstrapActivity.boot()` does.
 */
object RestoreEngine {

    /** `restore_replaced/` — where the live library waits during the swap, beside staging. */
    const val ASIDE_DIR = "restore_replaced"

    /** The `AttemptLimiter` bucket for decision 6's prompt. */
    const val LIMITER_KEY = AttemptLimiter.RESTORE_KEY

    /** Why a door refused. Every one is a message in L3, never a throw. */
    sealed class Problem {
        /** A global rotation is mid-flight; its marker names files this restore would delete. */
        object RotationPending : Problem()

        /** A notebook is open in this process — never swap under a live writer. */
        object NotebookHeld : Problem()

        /** The volume cannot take the backup plus headroom. [shortfallBytes] is what is missing. */
        data class NotEnoughSpace(val shortfallBytes: Long) : Problem()

        /** The source could not list or fetch — the L1 kinds, passed through. */
        data class Source(val problem: RestoreProblem) : Problem()

        /** A staged file is not an encrypted SQLite database (or went missing before the commit).
         *  [fileName] is a UUID or a store package — safe to show. */
        data class InvalidFile(val fileName: String) : Problem()

        /** The staged index opens under no offered key (the screen's prompt loop ended). */
        object NoKey : Problem()

        /** This device's destination could not be parked; the swap was never started. */
        object ParkFailed : Problem()

        /** A rename inside the swap failed at [step] (`a`–`e` of D3 step 8). The aside was renamed
         *  back and the old library is whole — the caller still relaunches. */
        data class SwapFailed(val step: Char) : Problem()

        /** Something threw where nothing should; [what] is the exception's class name. */
        data class Unexpected(val what: String) : Problem()
    }

    /** What [stage] answers. */
    sealed class StageResult {
        data class Staged(val manifest: RestoreManifest) : StageResult()
        data class Failed(val problem: Problem) : StageResult()
    }

    /** What [commit] answers. Exactly one of these, and the caller relaunches on every one. */
    sealed class Outcome {
        /** The restored library is installed and its key is this device's global. */
        data class Committed(val notebooks: Int, val stores: Int) : Outcome()

        /** Refused before the point of no return; the live library was never touched. */
        data class Refused(val problem: Problem) : Outcome()

        /** The swap failed and was renamed back; the live library is whole; the process is blind
         *  (the index is closed) and must relaunch. */
        data class RolledBack(val problem: Problem) : Outcome()
    }

    // ── 1. Pre-flight ───────────────────────────────────────────────────────

    /** Null when the restore may proceed to staging. IO. */
    suspend fun preflight(context: Context, backup: RestoreBackup): Problem? = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            when {
                GlobalRotation.hasMarker(app) -> Problem.RotationPending
                SoilOpenFiles.anyOpen() -> Problem.NotebookHeld
                else -> spaceProblem(backup.totalBytes, RestoreStaging.usableBytes(app))
            }
        } catch (e: Exception) {
            Log.w(TAG, "preflight failed", e)
            Problem.Unexpected(e.javaClass.simpleName)
        }
    }

    /** The pre-fetch gate, pure: null when [totalBytes] + headroom fits [usableBytes]. */
    fun spaceProblem(totalBytes: Long, usableBytes: Long, headroom: Long = RestoreStaging.HEADROOM_BYTES): Problem? {
        if (RestoreStaging.fits(totalBytes, usableBytes, headroom)) return null
        val need = (if (totalBytes < 0L) 0L else totalBytes) + headroom
        val have = if (usableBytes < 0L) 0L else usableBytes
        return Problem.NotEnoughSpace(shortfallBytes = (need - have).coerceAtLeast(1L))
    }

    /** The post-stage re-measure, pure: the staged bytes already sit on the volume, so only the
     *  headroom itself must still fit. */
    fun headroomProblem(usableBytes: Long, headroom: Long = RestoreStaging.HEADROOM_BYTES): Problem? =
        spaceProblem(0L, usableBytes, headroom)

    // ── 2. Stage ────────────────────────────────────────────────────────────

    /** Reset staging and let [source] fill it. IO. */
    suspend fun stage(
        context: Context,
        source: RestoreSource,
        backup: RestoreBackup,
        onProgress: (done: Int, total: Int) -> Unit,
    ): StageResult = withContext(Dispatchers.IO) {
        try {
            val staging = RestoreStaging.reset(context)
            when (val r = source.fetchInto(backup, staging, onProgress)) {
                is FetchResult.Staged -> StageResult.Staged(r.manifest)
                is FetchResult.Failed -> {
                    RestoreStaging.discard(context)
                    StageResult.Failed(Problem.Source(r.problem))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stage failed", e)
            runCatching { RestoreStaging.discard(context) }
            StageResult.Failed(Problem.Unexpected(e.javaClass.simpleName))
        }
    }

    // ── 3. Validate ─────────────────────────────────────────────────────────

    /** Probe every staged main file. Null when all are encrypted SQLite. IO. */
    suspend fun validate(context: Context, manifest: RestoreManifest): Problem? = withContext(Dispatchers.IO) {
        try {
            validationProblem(RestoreStaging.dir(context), manifest, SoilCrypto::probe)
        } catch (e: Exception) {
            Log.w(TAG, "validate failed", e)
            Problem.Unexpected(e.javaClass.simpleName)
        }
    }

    /**
     * The rule, pure over a [probe]: every `INDEX` / `SOIL` / `STORE` item must exist, weigh what
     * the listing said (when it said), and probe `Encrypted`. `Plaintext` is refused too — SN has
     * no plaintext mode, so a plaintext file is either foreign or damaged and would never open.
     * WAL items are not probed (a WAL has no header of its own) but must be present.
     */
    fun validationProblem(stagingDir: File, manifest: RestoreManifest, probe: (File) -> SoilFileKind): Problem? {
        for (item in manifest.items) {
            val file = RestoreStaging.targetFor(stagingDir, item)
            if (!file.isFile) return Problem.InvalidFile(item.name)
            if (item.size >= 0L && file.length() != item.size) return Problem.InvalidFile(item.name)
            when (item.kind) {
                ItemKind.INDEX, ItemKind.SOIL, ItemKind.STORE ->
                    if (probe(file) != SoilFileKind.Encrypted) return Problem.InvalidFile(item.name)
                ItemKind.INDEX_WAL, ItemKind.SOIL_WAL, ItemKind.STORE_WAL -> Unit
            }
        }
        return null
    }

    // ── 4. Prove the key ────────────────────────────────────────────────────

    /** The staged index, once staged. */
    fun stagedIndex(context: Context): File = File(RestoreStaging.dir(context), RestoreManifest.INDEX_NAME)

    /**
     * Try this device's cached global against the staged index, silently. The passphrase that
     * opens it, or null (no cached global, or it does not fit — the screen prompts next). One KDF
     * through the platform door, never a hand loop. IO.
     */
    suspend fun proveCached(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val cached = PassphraseStore.getGlobalPassphrase(context.applicationContext) ?: return@withContext null
            if (SoilCrypto.verifyPassphrase(stagedIndex(context), cached)) cached else null
        } catch (e: Exception) {
            Log.w(TAG, "cached-key proof failed", e)
            null
        }
    }

    /**
     * Try what the person typed: **as typed first, then `GlobalKey.normalize`d** (R6 — exactly as
     * `UnlockActivity` does; a typed passphrase must never be corrupted by the recovery-key
     * normalizer). Records success or failure in the `RESTORE` limiter bucket; the caller checks
     * `AttemptLimiter.check(context, LIMITER_KEY)` before prompting. The passphrase that opens the
     * staged index, or null. IO.
     */
    suspend fun proveTyped(context: Context, typed: String): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val file = stagedIndex(context)
            val proven = when {
                SoilCrypto.verifyPassphrase(file, typed) -> typed
                else -> GlobalKey.normalize(typed).takeIf { it != typed && SoilCrypto.verifyPassphrase(file, it) }
            }
            if (proven != null) AttemptLimiter.recordSuccess(app, LIMITER_KEY)
            else AttemptLimiter.recordFailure(app, LIMITER_KEY)
            proven
        } catch (e: Exception) {
            Log.w(TAG, "typed-key proof failed", e)
            AttemptLimiter.recordFailure(app, LIMITER_KEY)
            null
        }
    }

    // ── 5. Commit ───────────────────────────────────────────────────────────

    /**
     * D3 steps 5–10. [proven] must have opened the staged index. Runs whole under [NonCancellable]
     * on IO: a dying screen must not leave the library half-swapped. After a [Outcome.Committed] or
     * [Outcome.RolledBack] the index is closed and the caller's only way out is
     * `BootstrapActivity.relaunchIntent` + `finishAffinity()`.
     */
    suspend fun commit(context: Context, manifest: RestoreManifest, proven: String): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            val app = context.applicationContext
            try {
                commitInner(app, manifest, proven)
            } catch (e: Exception) {
                Log.e(TAG, "commit threw", e)
                Outcome.Refused(Problem.Unexpected(e.javaClass.simpleName))
            }
        }

    private suspend fun commitInner(app: Context, manifest: RestoreManifest, proven: String): Outcome {
        val root = checkNotNull(app.getExternalFilesDir(null)) { "no external files dir" }
        val staging = RestoreStaging.dir(app)

        // Step 0 — a torn staging set (a file deleted between validate and now) must be caught
        // here, not by a rename that fails half-way through the install. The index is exempt
        // from the size rule: the key proof opened it, and SQLite's close checkpoints a WAL into
        // the main file (and deletes the sidecar), so its listed size is no longer its size.
        for (item in manifest.items) {
            val f = RestoreStaging.targetFor(staging, item)
            val torn = when (item.kind) {
                ItemKind.INDEX -> !f.isFile
                ItemKind.INDEX_WAL -> false
                else -> !f.isFile || (item.size >= 0L && f.length() != item.size)
            }
            if (torn) { RestoreStaging.discard(root); return Outcome.Refused(Problem.InvalidFile(item.name)) }
        }
        // The one rule re-checked at the last moment: nothing may be held open.
        if (GlobalRotation.hasMarker(app)) { RestoreStaging.discard(root); return Outcome.Refused(Problem.RotationPending) }
        if (SoilOpenFiles.anyOpen()) { RestoreStaging.discard(root); return Outcome.Refused(Problem.NotebookHeld) }

        // Step 5 — this device's destination out of the live index, parked device-locally.
        val parked = RestoreDestination.parkedFrom(BackupStore().read())
        if (!RestoreDestination.park(app, parked)) return Outcome.Refused(Problem.ParkFailed)

        // Step 6 — the honest re-measure: only the headroom is left to fit. A refusal here
        // un-parks: the live row is untouched, and a park applied over it would only clear the
        // stamps of a library this device HAS backed up.
        headroomProblem(RestoreStaging.usableBytes(root))?.let {
            RestoreDestination.clearPark(app)
            RestoreStaging.discard(root)
            return Outcome.Refused(it)
        }

        // Step 7 — blind the process (R2) before anything closes: an extension calling back into
        // its store during the window now meets SoilLockedException instead of minting a store.
        val oldPassphrase = KeySession.get()
        KeySession.clear()

        // Step 8 — close and swap, renames only.
        ExtensionStores.closeAll()
        SnIndex.closeForRotation() // checkpoints, closes, forgets the instance; IndexGuard bounces every screen from here

        val live = Live(indexFile(app), gardenDir(app))
        val aside = File(root, ASIDE_DIR)
        val failedStep = swap(live, aside, staging)
        if (failedStep != null) {
            Log.e(TAG, "swap failed at step $failedStep; renaming the aside back")
            executeRecovery(root, live, aside, staging)
            RestoreDestination.clearPark(app) // the old library is back; its row already holds its destination
            oldPassphrase?.let { KeySession.set(it) }
            return Outcome.RolledBack(Problem.SwapFailed(failedStep))
        }

        // Step 9 — key state. The installed index is the restored library's; its key becomes this
        // device's global, acknowledged unconditionally (R7 — the person demonstrably has it).
        PassphraseStore.setGlobalPassphrase(app, proven)
        PassphraseStore.setRecoveryKeyAcknowledged(app)
        KeyMaterial.clearAll(app)
        KeySession.set(proven)
        NotebookUnlocks.clear()
        PassphraseCache.clear()
        PassphraseStore.clearRotationMarker(app)

        // Step 10 — discard the aside (decision 5: no undo) and whatever staging has left.
        if (!aside.deleteRecursively()) Log.w(TAG, "aside discard was incomplete; the next launch finishes it")
        RestoreStaging.discard(root)
        RealRekeyFs.fsyncDir(root)

        Slog.d(TAG) { "restore committed: ${manifest.notebookCount} notebooks, ${manifest.storeCount} stores" }
        return Outcome.Committed(manifest.notebookCount, manifest.storeCount)
    }

    private class Live(val index: File, val garden: File)

    /**
     * D3 step 8 (a)–(e). Returns the letter of the rename that failed, or null when every one
     * landed. Each rename is same-volume and atomic; a directory fsync after each group shortens
     * the window before it persists. A sidecar that does not exist is simply skipped.
     */
    private fun swap(live: Live, aside: File, staging: File): Char? {
        val fs = RealRekeyFs
        if (aside.exists() && !aside.deleteRecursively()) return 'a'
        if (!aside.mkdirs()) return 'a'
        val root = live.index.parentFile ?: return 'a'

        // (a) live index + sidecars → aside. The index goes first: it is the marker in both
        // directions (its absence live is "swap in flight", its presence aside is "renames back").
        if (!fs.rename(live.index, File(aside, live.index.name))) return 'a'
        for (sidecar in sidecarsOf(live.index)) {
            if (sidecar.exists() && !fs.rename(sidecar, File(aside, sidecar.name))) return 'a'
        }
        fs.fsyncDir(root)

        // (b) live Garden → aside/Garden (a missing live Garden is a fresh device — nothing to move).
        if (live.garden.exists() && !fs.rename(live.garden, File(aside, RestoreRecovery.GARDEN_NAME))) return 'b'
        fs.fsyncDir(root)

        // (c) staged Garden → live.
        if (!fs.rename(File(staging, RestoreRecovery.GARDEN_NAME), live.garden)) return 'c'
        fs.fsyncDir(root)

        // (d) the staged index's WAL (if the backup had one, or the proof left one) beside the live
        // name; a `-shm` is rebuilt on open and is deleted rather than moved.
        val stagedIndex = File(staging, live.index.name)
        for (sidecar in sidecarsOf(stagedIndex)) {
            if (!sidecar.exists()) continue
            if (sidecar.name.endsWith("-shm")) { sidecar.delete(); continue }
            if (!fs.rename(sidecar, File(root, sidecar.name))) return 'd'
        }
        // (e) the staged index → live, last: the commit marker.
        if (!fs.rename(stagedIndex, live.index)) return 'e'
        fs.fsyncDir(root)
        return null
    }

    // ── D5 — interrupted-commit recovery ────────────────────────────────────

    /**
     * The launch-time repair, **first thing in `BootstrapActivity.boot()`** — before
     * `SnIndex.ensureReady` and therefore before `SoilRekey.recoverGarden`. Reads the files that
     * exist, asks [RestoreRecovery.plan], executes it. Idempotent, never throws. IO.
     */
    suspend fun recoverInterrupted(context: Context) = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val root = app.getExternalFilesDir(null) ?: return@withContext
            val aside = File(root, ASIDE_DIR)
            val staging = RestoreStaging.dir(root)
            if (!aside.exists() && !staging.exists()) return@withContext // the ordinary launch: two stats
            executeRecovery(root, Live(indexFile(app), gardenDir(app)), aside, staging)
        } catch (e: Exception) {
            Log.e(TAG, "interrupted-restore recovery failed", e)
        }
    }

    private fun executeRecovery(root: File, live: Live, aside: File, staging: File) {
        val asideIndex = File(aside, live.index.name)
        val asideGarden = File(aside, RestoreRecovery.GARDEN_NAME)
        val state = RestoreRecovery.State(
            liveIndex = live.index.isFile,
            asideIndex = asideIndex.isFile,
            liveGarden = live.garden.isDirectory,
            asideGarden = asideGarden.isDirectory,
            asideSidecars = sidecarsOf(asideIndex).filter { it.exists() }.map { it.name },
        )
        val actions = RestoreRecovery.plan(state)
        Log.w(TAG, "restore recovery: $state → $actions")
        for (action in actions) {
            val ok = when (action) {
                RestoreRecovery.Action.DeleteAside -> !aside.exists() || aside.deleteRecursively()
                RestoreRecovery.Action.DeleteStaging -> !staging.exists() || staging.deleteRecursively()
                RestoreRecovery.Action.DeleteLiveGarden -> live.garden.deleteRecursively()
                is RestoreRecovery.Action.RenameBack -> {
                    val from = File(aside, action.name)
                    val to = File(root, action.name)
                    !from.exists() || RealRekeyFs.rename(from, to)
                }
            }
            if (!ok) Log.e(TAG, "restore recovery action failed: $action")
        }
        RealRekeyFs.fsyncDir(root)
        // An aside that renamed back completely is an empty directory now; leave a non-empty one
        // (a failed rename) for a person to look at rather than delete what could not be restored.
        if (aside.isDirectory && aside.list().isNullOrEmpty()) aside.delete()
    }

    private const val TAG = "RestoreEngine"
}
