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
import com.symmetricalpalmtree.notesproutsn.data.extensionStorePackage
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
 *  4b. [pruneOrphans] (L5) — with the key proven, the staged index says which notebooks the
 *     backup *is*; a staged `<uuid>.soil` it has no alive row for is an orphan the writer never
 *     deleted (og's leftover, a stale copy under a key long rotated away) and is dropped from
 *     staging and named in the ending, never installed. A staged store that does not open under
 *     the proven key is dead the same way (a store has no other key) and is left out too. The screen
 *     validates the index and stores BEFORE the key and the notebooks AFTER the prune, so a
 *     plaintext orphan is a name in the ending rather than a refusal of the whole restore.
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
        data class Committed(val notebooks: Int, val stores: Int, val leftOut: List<String> = emptyList()) : Outcome()

        /** Refused before the point of no return; the live library was never touched. */
        data class Refused(val problem: Problem) : Outcome()

        /** The swap failed and was renamed back; the live library is whole; the process is blind
         *  (the index is closed) and must relaunch. */
        data class RolledBack(val problem: Problem) : Outcome()

        /** The restored index **landed** (the commit marker is live) but something threw before
         *  the key state finished. The aside is discarded — there is no going back — and the
         *  relaunch may stop at Unlock, where the backup's key opens it and the parked destination
         *  is applied (the L2 kill-after-8e path). The caller relaunches. */
        data class Interrupted(val problem: Problem) : Outcome()
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

    /**
     * After a failed fetch, pure: [Problem.NotEnoughSpace] when what is still to come plus the
     * headroom no longer fits (an unknown total counts as nothing still to come — the pre-fetch
     * gate already refused an unknown total that mattered), else [sourceProblem] unchanged.
     */
    fun fetchFailureProblem(
        sourceProblem: Problem,
        totalBytes: Long,
        stagedBytes: Long,
        usableBytes: Long,
        headroom: Long = RestoreStaging.HEADROOM_BYTES,
    ): Problem {
        if (usableBytes < 0L) return sourceProblem // cannot measure — do not guess
        val remaining = if (totalBytes < 0L) 0L else (totalBytes - stagedBytes).coerceAtLeast(0L)
        return spaceProblem(remaining, usableBytes, headroom) ?: sourceProblem
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
                    // L5: a disk that filled mid-fetch reaches here as the source's failure (the
                    // cloud extension reports its write error as NETWORK; a SAF copy as a failed
                    // file). Measured while the staged bytes still sit on the volume, the disk
                    // is named when it is the disk — the source's problem otherwise.
                    val problem = fetchFailureProblem(
                        Problem.Source(r.problem), backup.totalBytes,
                        RestoreStaging.stagedBytes(staging), RestoreStaging.usableBytes(context),
                    )
                    RestoreStaging.discard(context)
                    StageResult.Failed(problem)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stage failed", e)
            runCatching { RestoreStaging.discard(context) }
            StageResult.Failed(Problem.Unexpected(e.javaClass.simpleName))
        }
    }

    // ── 3. Validate ─────────────────────────────────────────────────────────

    /** Every kind — [validate]'s default. */
    val ALL_KINDS: Set<ItemKind> = ItemKind.values().toSet()

    /** The index — what the screen validates before the key is proven (a bad index is "not a
     *  backup", and nothing else can be judged without its key). */
    val INDEX_ONLY: Set<ItemKind> = setOf(ItemKind.INDEX, ItemKind.INDEX_WAL)

    /** The notebooks — validated after [pruneOrphans], so an orphan is never probed. */
    val NOTEBOOKS: Set<ItemKind> = setOf(ItemKind.SOIL, ItemKind.SOIL_WAL)

    /** The stores — judged inside [pruneOrphans] (probe + key), never by [validate]'s fail-whole. */
    val STORES: Set<ItemKind> = setOf(ItemKind.STORE, ItemKind.STORE_WAL)

    /** Probe every staged main file of a kind in [only]. Null when all are encrypted SQLite. IO. */
    suspend fun validate(context: Context, manifest: RestoreManifest, only: Set<ItemKind> = ALL_KINDS): Problem? = withContext(Dispatchers.IO) {
        try {
            validationProblem(RestoreStaging.dir(context), manifest, SoilCrypto::probe, only)
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
    fun validationProblem(
        stagingDir: File,
        manifest: RestoreManifest,
        probe: (File) -> SoilFileKind,
        only: Set<ItemKind> = ALL_KINDS,
    ): Problem? {
        for (item in manifest.items) {
            if (item.kind !in only) continue
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

    // ── 4b. Orphans (L5) ────────────────────────────────────────────────────

    /** What [pruneOrphans] answers: the manifest to commit, and the names left out of it. */
    sealed class PruneResult {
        data class Pruned(val manifest: RestoreManifest, val leftOut: List<String>) : PruneResult()
        data class Failed(val problem: Problem) : PruneResult()
    }

    /**
     * Read the staged index's alive notebook ids under [proven] (one raw open, one query — the
     * same rows `BackupEngine` builds its work list from), drop every staged `.soil` (and its
     * WAL) the index does not name, delete those files from staging, and answer the manifest
     * that is left. A backup folder is an accretion — the writer never deletes, so a notebook
     * trashed or re-keyed on the source device leaves a file behind that the index does not own;
     * installing it would put an invisible file under a foreign key into `Garden/`, where the
     * next rotation stops on it (the L4 walk). IO.
     */
    suspend fun pruneOrphans(
        context: Context,
        manifest: RestoreManifest,
        proven: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): PruneResult = withContext(Dispatchers.IO) {
        try {
            val staging = RestoreStaging.dir(context)
            val alive = aliveNotebookIds(stagedIndex(context), proven)
            // The stores: a store opens under the global key or under nothing (there is no
            // per-store passphrase), so one that does not open under [proven] is dead weight that
            // the next rotation would stop on (the L4 and L5 walks, both times); one that is not
            // an encrypted SQLite file at all is a foreign `.db` with a package-shaped name, or a
            // damaged copy — the same dead weight. Left out and named, never a refusal: nothing
            // names a store the way the index names a notebook, so nothing can vouch for one.
            // One KDF each — a handful of files, unlike the notebooks, which a rotation
            // quarantines instead. The verify is READ-ONLY (H1): the SAF leg stages a store's
            // `-wal` beside it, and a read-write open's close would checkpoint that WAL into the
            // main file and unlink it — the store then weighs more than its manifest row and the
            // STORE_WAL row's file is gone, so commit's Step 0 refused every intact backup that
            // carried one. Read-only, the staged bytes stay what the manifest measured; the one
            // thing the open leaves behind is a `-shm`, which is not a manifest item and is
            // deleted here so the staged Garden holds exactly what was fetched.
            val stores = manifest.items.filter { it.kind == ItemKind.STORE }
            val deadStores = HashSet<String>()
            stores.forEachIndexed { i, item ->
                onProgress(i, stores.size)
                val f = RestoreStaging.targetFor(staging, item)
                val dead = !f.isFile || SoilCrypto.probe(f) != SoilFileKind.Encrypted || !SoilCrypto.verifyPassphraseReadOnly(f, proven)
                File(f.path + SHM).delete()
                if (dead) deadStores.add(item.name)
            }
            onProgress(stores.size, stores.size)
            val (kept, leftOut) = orphanRule(manifest, alive, deadStores)
            for (item in manifest.items) {
                if (item in kept.items) continue
                val f = RestoreStaging.targetFor(staging, item)
                if (f.exists() && !f.delete()) Log.w(TAG, "orphan ${item.name} could not be deleted from staging")
            }
            if (leftOut.isNotEmpty()) Log.w(TAG, "left out ${leftOut.size} orphan(s): $leftOut")
            PruneResult.Pruned(kept, leftOut)
        } catch (e: Exception) {
            Log.w(TAG, "orphan prune failed", e)
            PruneResult.Failed(Problem.Unexpected(e.javaClass.simpleName))
        }
    }

    /**
     * The rule, pure: every `SOIL` whose stem is not in [aliveIds] — and its `SOIL_WAL` — is left
     * out, and so is every `STORE` named in [deadStores] (one that did not open under the proven
     * key, or is not an encrypted SQLite file) with its `STORE_WAL`; every other item is kept in
     * its order. The second value is the left-out main files' names, sorted. The index is never
     * an orphan.
     */
    fun orphanRule(
        manifest: RestoreManifest,
        aliveIds: Set<String>,
        deadStores: Set<String> = emptySet(),
    ): Pair<RestoreManifest, List<String>> {
        val leftOut = ArrayList<String>()
        val kept = manifest.items.filter { item ->
            when (item.kind) {
                ItemKind.SOIL -> (soilStem(item.name) in aliveIds).also { if (!it) leftOut.add(item.name) }
                ItemKind.SOIL_WAL -> soilStem(item.name.removeSuffix(WAL)) in aliveIds
                ItemKind.STORE -> (item.name !in deadStores).also { if (!it) leftOut.add(item.name) }
                ItemKind.STORE_WAL -> item.name.removeSuffix(WAL) !in deadStores
                else -> true
            }
        }
        return RestoreManifest(kept) to leftOut.sorted()
    }

    private const val WAL = "-wal"
    private const val SHM = "-shm"

    private fun soilStem(name: String): String = name.removeSuffix(".soil")

    /** `SELECT id FROM objects WHERE type = 'notebook' AND deletedAt IS NULL` on a raw open. */
    private fun aliveNotebookIds(index: File, proven: String): Set<String> {
        val db = SoilCrypto.openRaw(index, proven)
        try {
            val ids = HashSet<String>()
            db.rawQuery("SELECT id FROM objects WHERE type = 'notebook' AND deletedAt IS NULL", null).use { c ->
                while (c.moveToNext()) ids.add(c.getString(0))
            }
            return ids
        } finally {
            runCatching { db.close() }
        }
    }

    // ── 5. Commit ───────────────────────────────────────────────────────────

    /**
     * D3 steps 5–10. [proven] must have opened the staged index; [leftOut] is what
     * [pruneOrphans] dropped, carried into [Outcome.Committed] for the ending to name. Runs whole under [NonCancellable]
     * on IO: a dying screen must not leave the library half-swapped. After a [Outcome.Committed],
     * [Outcome.RolledBack] or [Outcome.Interrupted] the index is closed and the caller's only way out is
     * `BootstrapActivity.relaunchIntent` + `finishAffinity()`.
     */
    suspend fun commit(context: Context, manifest: RestoreManifest, proven: String, leftOut: List<String> = emptyList()): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            val app = context.applicationContext
            try {
                commitInner(app, manifest, proven, leftOut)
            } catch (e: Exception) {
                // Only the pre-close half can throw to here (the post-close half has its own catch
                // below), so the index is still open and nothing live was touched. A park written
                // in step 5 is taken back, as every other pre-close refusal does.
                Log.e(TAG, "commit threw before the index closed", e)
                runCatching { RestoreDestination.clearPark(app) }
                runCatching { RestoreStaging.discard(app) }
                Outcome.Refused(Problem.Unexpected(e.javaClass.simpleName))
            }
        }

    private suspend fun commitInner(app: Context, manifest: RestoreManifest, proven: String, leftOut: List<String>): Outcome {
        val root = checkNotNull(app.getExternalFilesDir(null)) { "no external files dir" }
        val staging = RestoreStaging.dir(app)
        val hooks = faultHooks(app, staging, manifest)
        RestoreFaults.at(RestoreFaults.Seam.COMMIT_TOP, hooks) // L5: TEAR_STAGING fires here

        // Step 0 — a torn staging set (a file deleted between validate and now) must be caught
        // here, not by a rename that fails half-way through the install. The index is exempt
        // from the size rule: the key proof opened it, and SQLite's close checkpoints a WAL into
        // the main file (and deletes the sidecar), so its listed size is no longer its size. The
        // stores are NOT exempt: [pruneOrphans] verifies them read-only, which leaves a staged
        // store and its `-wal` byte-for-byte what the manifest measured (H1).
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

        val live = Live(indexFile(app), gardenDir(app))
        val aside = File(root, ASIDE_DIR)
        val marks = Marks()
        return try {
            // Step 8 — close and swap, renames only.
            ExtensionStores.closeAll()
            SnIndex.closeForRotation() // checkpoints, closes, forgets the instance; IndexGuard bounces every screen from here
            afterClose(app, root, live, aside, staging, manifest, proven, oldPassphrase, marks, hooks, leftOut)
        } catch (e: Exception) {
            // The session is cleared and the index may be closed, so `Refused` would be a lie from
            // here: the caller relaunches. Whether the swap began, and whether the restored index
            // then landed (the commit marker is live), decides what the person is told; D5's
            // idempotent plan settles the files either way, and Bootstrap re-runs it on launch.
            Log.e(TAG, "commit threw after the session was cleared (swap begun: ${marks.swapBegun})", e)
            val landed = marks.swapBegun && live.index.isFile
            runCatching { executeRecovery(root, live, aside, staging) }
                .onFailure { Log.e(TAG, "in-process recovery threw; the next launch finishes it", it) }
            val problem = Problem.Unexpected(e.javaClass.simpleName)
            if (landed) {
                Outcome.Interrupted(problem)
            } else {
                runCatching { RestoreDestination.clearPark(app) }
                oldPassphrase?.let { KeySession.set(it) }
                Outcome.RolledBack(problem)
            }
        }
    }

    /** What the post-close half has done so far — read by its catch. */
    private class Marks(var swapBegun: Boolean = false)

    /** D3 steps 8 (the swap) to 10 — everything that runs with the index closed. */
    private fun afterClose(
        app: Context,
        root: File,
        live: Live,
        aside: File,
        staging: File,
        manifest: RestoreManifest,
        proven: String,
        oldPassphrase: String?,
        marks: Marks,
        hooks: RestoreFaults.Hooks,
        leftOut: List<String>,
    ): Outcome {
        marks.swapBegun = true
        val failedStep = swap(live, aside, staging, hooks)
        if (failedStep != null) {
            Log.e(TAG, "swap failed at step $failedStep; renaming the aside back")
            executeRecovery(root, live, aside, staging)
            RestoreDestination.clearPark(app) // the old library is back; its row already holds its destination
            oldPassphrase?.let { KeySession.set(it) }
            return Outcome.RolledBack(Problem.SwapFailed(failedStep))
        }

        RestoreFaults.at(RestoreFaults.Seam.AFTER_E, hooks) // L5: KILL_AFTER_E / THROW_BEFORE_KEY_STATE

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
        return Outcome.Committed(manifest.notebookCount, manifest.storeCount, leftOut)
    }

    internal class Live(val index: File, val garden: File)

    /** L5 — what the armed plant / tear / store faults do. Inert unless a fault is armed. */
    private fun faultHooks(app: Context, staging: File, manifest: RestoreManifest): RestoreFaults.Hooks = object : RestoreFaults.Hooks {
        override fun plantAtGarden(): String {
            val garden = gardenDir(app)
            return if (garden.exists()) "FAIL — live Garden still present, nothing planted"
            else { garden.writeText("planted by RestoreFaults"); "planted a file at ${garden.name}" }
        }

        override fun tearStaging(): String {
            val victim = manifest.items.firstOrNull { it.kind == ItemKind.SOIL }
                ?: return "FAIL — no staged .soil to tear"
            val f = RestoreStaging.targetFor(staging, victim)
            return if (f.delete()) "deleted staged ${victim.name}" else "FAIL — could not delete ${victim.name}"
        }

        override fun storeCall(): String {
            val pkg = manifest.items.firstOrNull { it.kind == ItemKind.STORE }?.let { extensionStorePackage(it.name) }
                ?: "probe.restore"
            return try {
                ExtensionStores.open(app, pkg)
                "FAIL — ExtensionStores.open($pkg) succeeded mid-swap (R2 broken)"
            } catch (e: Exception) {
                "refused: ${e.javaClass.simpleName} (want SoilLockedException)"
            }
        }
    }

    /**
     * D3 step 8 (a)–(e). Returns the letter of the rename that failed, or null when every one
     * landed. Each rename is same-volume and atomic; a directory fsync after each group shortens
     * the window before it persists. A sidecar that does not exist is simply skipped.
     */
    private fun swap(live: Live, aside: File, staging: File, hooks: RestoreFaults.Hooks): Char? {
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
        RestoreFaults.at(RestoreFaults.Seam.AFTER_A, hooks)

        // (b) live Garden → aside/Garden (a missing live Garden is a fresh device — nothing to move).
        if (live.garden.exists() && !fs.rename(live.garden, File(aside, RestoreRecovery.GARDEN_NAME))) return 'b'
        fs.fsyncDir(root)
        RestoreFaults.at(RestoreFaults.Seam.AFTER_B, hooks) // KILL_AFTER_B / PLANT_AT_C / STORE_CALL_MID_SWAP

        // (c) staged Garden → live.
        if (!fs.rename(File(staging, RestoreRecovery.GARDEN_NAME), live.garden)) return 'c'
        fs.fsyncDir(root)
        RestoreFaults.at(RestoreFaults.Seam.AFTER_C, hooks)

        // (d) the staged index's WAL (if the backup had one, or the proof left one) beside the live
        // name; a `-shm` is rebuilt on open and is deleted rather than moved.
        val stagedIndex = File(staging, live.index.name)
        for (sidecar in sidecarsOf(stagedIndex)) {
            if (!sidecar.exists()) continue
            if (sidecar.name.endsWith("-shm")) { sidecar.delete(); continue }
            if (!fs.rename(sidecar, File(root, sidecar.name))) return 'd'
        }
        RestoreFaults.at(RestoreFaults.Seam.AFTER_D, hooks)
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

    internal fun executeRecovery(root: File, live: Live, aside: File, staging: File) {
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
        // L5: when the OLD index is about to be renamed back, a sidecar sitting at the live name
        // is the NEW index's (8(d) landed, 8(e) did not) — a WAL from another database beside
        // the old file is exactly what SQLite would replay into it on the next open. Nothing of
        // the old index's is at the live name (8(a) moved it all aside), so clearing is safe.
        if (!state.liveIndex && state.asideIndex) {
            for (sidecar in sidecarsOf(live.index)) {
                if (sidecar.exists() && !sidecar.delete()) Log.e(TAG, "stray ${sidecar.name} could not be cleared")
            }
        }
        for (action in actions) {
            val ok = when (action) {
                RestoreRecovery.Action.DeleteAside -> !aside.exists() || aside.deleteRecursively()
                RestoreRecovery.Action.DeleteStaging -> !staging.exists() || staging.deleteRecursively()
                RestoreRecovery.Action.DeleteLiveGarden -> live.garden.deleteRecursively()
                is RestoreRecovery.Action.RenameBack -> {
                    val from = File(aside, action.name)
                    val to = File(root, action.name)
                    // L5: a plain file squatting on a directory's name (the PLANT_AT_C injection —
                    // and the one shape a failed 8(c) can leave) would block the rename back
                    // forever, launch after launch. A file where the Garden goes is never the
                    // library's; a directory where the index goes is never the index. Clear it.
                    if (from.exists() && to.exists() && from.isDirectory != to.isDirectory) {
                        if (!to.deleteRecursively()) Log.e(TAG, "obstruction at ${to.name} could not be cleared")
                    }
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
