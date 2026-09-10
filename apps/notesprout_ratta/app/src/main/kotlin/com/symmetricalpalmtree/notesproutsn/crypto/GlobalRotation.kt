package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFile
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFiles
import com.symmetricalpalmtree.notesproutsn.data.extensionStorePackage
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.data.indexFile
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * **The global rotation** (arc 26 / U3, D2): re-key every file the device's global key opens —
 * `GLOBAL`-scope notebooks, then every `Garden/<pkg>.db` extension store, then the index
 * **last** — from the cached global passphrase to a new one, journaled so that a death anywhere
 * is resumable and nothing is ever in a state only a lost key opens.
 *
 * **Journal first.** [start] writes the [RotationMarker] to [PassphraseStore] before any file is
 * touched and rewrites it after every file. The cached global stays the OLD passphrase until
 * [commit]: between start and commit the library is in two keys, and three resume paths cover a
 * death in that window — the Encryption screen's banner ([resume]), Bootstrap forwarding there
 * while a marker exists, and `SnIndex.ensureReady` trying the marker's new passphrase for an
 * index that no longer opens under the old one and committing itself (it calls [commit]).
 *
 * **Per file** ([RotationPlan.beforeRekey] → [RotationPlan.decide]): on a `start` the cached raw
 * key is tried first — a raw key that still opens the file says "under the old key" for free,
 * since every rekey invalidates it and nothing can have been derived under the new passphrase
 * yet; otherwise one KDF verify answers. On a `resume` the new key is verified **first**: between
 * a Cancel and the Resume the library is reachable and `KeyResolver`'s two-candidate open warms
 * the cache under the NEW passphrase for a file already re-keyed (a Cancel invalidates nothing),
 * so a hit there proves only "under one of the two" (arc 34 / M2). A file
 * under the old key goes through [SoilRekey.rekeyInPlace], the only thing in SN that changes the
 * key a file on disk is under. A notebook under **neither** key is quarantined: `keyScope` set to
 * `NOTEBOOK` (the lock card from U4 on), its backup stamps cleared, dropped from pending, the
 * rotation carries on, and the count is reported at the end; U6's recovery is the way back. A
 * store or the index under neither stops the rotation with [Result.Failed] — nothing to
 * quarantine, nothing deleted, hand recovery. A rekey that throws is re-read
 * ([RotationPlan.afterThrow]): an original that is **missing** (a commit that kept both copies)
 * is put back by [SoilRekey.recoverOne] first and, if still missing, kept pending as transient —
 * never judged against a file that is not there; then under the new key → done (the commit
 * landed late); still under the old key → transient, keep pending, stop with Failed (the person
 * resumes); otherwise the same neither-key rule.
 *
 * **The rituals**: `ExtensionStores.closeAll()` before the first store, and before the index:
 * the backup stamps cleared in **both** maps while the index is still open (decision 4 — a rekey
 * leaves `updatedAt` untouched, so a forgotten stamp keeps an old-key copy in every backup
 * forever), then `SnIndex.closeForRotation()`. After that this process touches no index row; the
 * caller shows dialogs and relaunches through Bootstrap.
 *
 * **Cancel** ([AtomicBoolean]) is honoured between files; the current file always finishes. Each
 * file runs under [NonCancellable] so a dying activity scope can only land between files too.
 * `NOTEBOOK`-scope notebooks are never in the list. No passphrase is logged, ever.
 */
object GlobalRotation {

    private const val TAG = "GlobalRotation"

    /** What the progress dialog names for the file in hand. Notebook names are user content —
     *  they reach the dialog and nothing else. */
    sealed class Label {
        data class Notebook(val name: String) : Label()
        object Stores : Label()
        object Index : Label()
    }

    /** [done] of [total] finished; [label] is the one about to be re-keyed. */
    data class Progress(val done: Int, val total: Int, val label: Label)

    sealed class Result {
        /** Every file is under the new key and the commit is done. [notebooks] = re-keyed (or
         *  already-done) notebooks over the whole rotation; [quarantined] over the whole rotation. */
        data class Complete(val notebooks: Int, val quarantined: Int) : Result()
        /** Stopped between files on the person's Cancel; the marker keeps [remaining]. */
        data class Cancelled(val remaining: Int, val quarantined: Int) : Result()
        data class Failed(val reason: Reason, val remaining: Int, val quarantined: Int) : Result()
    }

    enum class Reason {
        /** The cached global passphrase is gone (Forget mid-rotation, a Keystore wipe) — nothing
         *  can be opened under the old key. The marker stays; Unlock puts the global back. */
        NO_CACHED_GLOBAL,
        /** A file still under the old key could not be re-keyed (disk, a WAL that would not
         *  absorb). Kept pending — Resume tries it again. */
        TRANSIENT,
        /** A store or the index opens under neither key. Kept pending; nothing deleted. */
        STUCK,
    }

    fun hasMarker(context: Context): Boolean = PassphraseStore.getRotationMarker(context) != null

    /**
     * The trusted-key test for `SoilRekey.recoverGarden` while a rotation may be in flight: the
     * cached global **or** the marker's new passphrase. Bootstrap uses it too — a `.rekey.tmp` the
     * rotation wrote verifies only under the new key, and a verifier that knew only the old one
     * would roll a finished rekey back (harmless, it would be redone — but pointless work).
     */
    fun trustedVerifier(context: Context): (File) -> Boolean {
        val app = context.applicationContext
        val global = PassphraseStore.getGlobalPassphrase(app)
        val marker = PassphraseStore.getRotationMarker(app)
        return { file ->
            (global != null && SoilCrypto.verifyPassphrase(file, global)) ||
                (marker != null && marker.newPassphrase != global && SoilCrypto.verifyPassphrase(file, marker.newPassphrase))
        }
    }

    /**
     * Begin a rotation to [newPassphrase]. The index must be open (the work list and the names come
     * from it). Writes the marker, then runs. IO.
     */
    suspend fun start(
        context: Context,
        newPassphrase: String,
        minted: Boolean,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val repository = IndexRepository()
        val notebookIds = repository.globalNotebookIds()
        val stores = extensionStoreFiles(app).mapNotNull { extensionStorePackage(it.name) }
        val ids = RotationPlan.order(notebookIds, stores)
        val marker = RotationMarker(
            pendingIds = ids,
            newPassphrase = newPassphrase,
            minted = minted,
            total = ids.size,
            notebookCount = notebookIds.size,
            startedAt = System.currentTimeMillis(),
        )
        PassphraseStore.setRotationMarker(app, marker)
        Slog.d(TAG) { "rotation started: ${notebookIds.size} notebooks, ${stores.size} stores, index" }
        run(app, marker, onProgress, cancel, resumed = false)
    }

    /** Continue the rotation the marker describes (the banner's Resume). IO. */
    suspend fun resume(
        context: Context,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val marker = PassphraseStore.getRotationMarker(app)
            ?: return@withContext Result.Complete(0, 0) // nothing to resume — already committed
        // A commit that died between its two renames: put the survivor in place under whichever
        // key it verifies with, before the loop can find an original missing.
        SoilRekey.recoverGarden(app, trustedVerifier(app))
        run(app, marker, onProgress, cancel, resumed = true)
    }

    private suspend fun run(
        app: Context,
        initial: RotationMarker,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
        resumed: Boolean,
    ): Result {
        val old = PassphraseStore.getGlobalPassphrase(app)
            ?: return Result.Failed(Reason.NO_CACHED_GLOBAL, initial.pendingIds.size, initial.quarantined.size)
        val new = initial.newPassphrase
        val repository = IndexRepository()
        // The library is reachable between a Cancel and a Resume, so the list is re-read: a
        // notebook created or imported since, and any store minted since, were made under the OLD
        // key and must not be left behind. Stores are cheap to re-check (a raw-key verify, or one
        // KDF each for the handful already done); notebooks join only by RotationPlan's rule. A
        // raw-key hit here may have been warmed under the NEW key since the Cancel (M2) — joining
        // costs that notebook one verify in the loop, which answers SKIP; nothing is re-keyed.
        val globalIds = repository.globalNotebookIds()
        val rows = repository.aliveNotebooks(globalIds)
        val extra = RotationPlan.resumeCandidates(
            globalNotebooks = globalIds.map { id -> id to (rows[id]?.let { maxOf(it.createdAt, it.updatedAt) } ?: 0L) },
            pendingIds = initial.pendingIds.toSet(),
            startedAt = initial.startedAt,
            rawKeyOpens = { id -> KeyMaterial.peekVerified(app, id, soilFile(app, id)) != null },
        )
        val stores = extensionStoreFiles(app).mapNotNull { extensionStorePackage(it.name) }
        var marker = initial.augmented(extra, stores)
        if (marker != initial) {
            Log.w(TAG, "resume found ${extra.size} notebook(s) and ${stores.size} store(s) to check beyond the marker")
            PassphraseStore.setRotationMarker(app, marker)
        }
        // Names for the dialog, read while the index is open. Never stored anywhere.
        val names = rows.mapValues { it.value.name }
        var storesClosed = false

        for (id in marker.pendingIds) {
            coroutineContext.ensureActive()
            if (cancel.get()) return Result.Cancelled(marker.pendingIds.size, marker.quarantined.size)
            val kind = RotationPlan.kindOf(id)
            onProgress(Progress(marker.completed, marker.total, labelFor(kind, id, names)))

            val outcome = withContext(NonCancellable) {
                when (kind) {
                    RotationPlan.Kind.NOTEBOOK -> rotateNotebook(app, id, old, new, repository, resumed)
                    RotationPlan.Kind.STORE -> {
                        if (!storesClosed) { ExtensionStores.closeAll(); storesClosed = true }
                        rotateFile(app, extensionStoreFile(app, RotationPlan.storePackage(id)!!), id, kind, old, new, null, resumed)
                    }
                    RotationPlan.Kind.INDEX -> {
                        // Decision 4, while the index is still open; then the one door that closes it.
                        BackupStore().clearAllStamps()
                        SnIndex.closeForRotation()
                        rotateFile(app, indexFile(app), id, kind, old, new, null, resumed)
                    }
                }
            }
            marker = when (outcome) {
                FileOutcome.DONE -> marker.without(id)
                FileOutcome.QUARANTINED -> marker.quarantine(id)
                FileOutcome.TRANSIENT -> return Result.Failed(Reason.TRANSIENT, marker.pendingIds.size, marker.quarantined.size)
                FileOutcome.STUCK -> return Result.Failed(Reason.STUCK, marker.pendingIds.size, marker.quarantined.size)
            }
            PassphraseStore.setRotationMarker(app, marker)
        }

        commit(app, marker)
        return Result.Complete(marker.notebookCount - marker.quarantined.size, marker.quarantined.size)
    }

    private fun labelFor(kind: RotationPlan.Kind, id: String, names: Map<String, String>): Label = when (kind) {
        RotationPlan.Kind.NOTEBOOK -> Label.Notebook(names[id] ?: "")
        RotationPlan.Kind.STORE -> Label.Stores
        RotationPlan.Kind.INDEX -> Label.Index
    }

    private enum class FileOutcome { DONE, QUARANTINED, TRANSIENT, STUCK }

    private suspend fun rotateNotebook(
        app: Context, id: String, old: String, new: String, repository: IndexRepository, resumed: Boolean,
    ): FileOutcome {
        val file = soilFile(app, id)
        if (!file.exists() || file.length() == 0L) {
            // An alive row with no file: nothing to re-key, nothing this rotation can put right.
            Log.w(TAG, "notebook file missing; skipped")
            return FileOutcome.DONE
        }
        val outcome = rotateFile(app, file, id, RotationPlan.Kind.NOTEBOOK, old, new, KEY_SCOPE_GLOBAL, resumed)
        if (outcome == FileOutcome.QUARANTINED) {
            repository.quarantine(id)   // setEncryptionState: scope, cover nulled, both stamps cleared
            Log.w(TAG, "notebook quarantined to NOTEBOOK scope (opens under neither key)")
        }
        return outcome
    }

    /** One file through [RotationPlan.beforeRekey] / [RotationPlan.afterThrow]. */
    private suspend fun rotateFile(
        app: Context, file: File, fileId: String, kind: RotationPlan.Kind, old: String, new: String, keyScope: String?,
        resumed: Boolean,
    ): FileOutcome {
        val step = RotationPlan.beforeRekey(
            kind,
            resumed = resumed,
            // Stale ones are dropped there — the V4 rule. A hit is "under the old key" only where
            // beforeRekey asks for it (a start, or a resume once the new key has failed).
            rawKeyOpens = { KeyMaterial.peekVerified(app, fileId, file) != null },
            opensUnderNew = { SoilCrypto.verifyPassphrase(file, new) },
            opensUnderOld = { SoilCrypto.verifyPassphrase(file, old) },
        )
        return when (step) {
            RotationPlan.Step.SKIP -> {
                KeyMaterial.invalidate(app, fileId) // derived against the old salt, if at all
                FileOutcome.DONE
            }
            RotationPlan.Step.REKEY -> try {
                SoilRekey.rekeyInPlace(app, file, fileId, old, new, keyScope)
                FileOutcome.DONE
            } catch (e: Exception) {
                Log.w(TAG, "rekey failed: ${e.message}")
                // M1: a commit that left `X.old.bak` + `X.rekey.tmp` and no `X` (BothKept) is
                // finished here — the tmp verifies under the new key, so recovery renames it back
                // — before anything reads the key question off a file that is not there.
                when (RotationPlan.afterThrow(
                    kind,
                    originalExists = { file.exists() },
                    recover = {
                        val result = SoilRekey.recoverOne(file) { f ->
                            SoilCrypto.verifyPassphrase(f, new) || SoilCrypto.verifyPassphrase(f, old)
                        }
                        Log.w(TAG, "original missing after the rekey threw; recovery: $result")
                    },
                    opensUnderNew = { SoilCrypto.verifyPassphrase(file, new) }, // the commit landed late, or recovery finished it
                    opensUnderOld = { SoilCrypto.verifyPassphrase(file, old) },
                )) {
                    RotationPlan.Aftermath.DONE -> { KeyMaterial.invalidate(app, fileId); FileOutcome.DONE }
                    RotationPlan.Aftermath.TRANSIENT -> FileOutcome.TRANSIENT
                    RotationPlan.Aftermath.QUARANTINE -> FileOutcome.QUARANTINED
                    RotationPlan.Aftermath.STOP -> FileOutcome.STUCK
                }
            }
            RotationPlan.Step.QUARANTINE -> FileOutcome.QUARANTINED
            RotationPlan.Step.STOP -> FileOutcome.STUCK
        }
    }

    /**
     * The commit — [RotationPlan.commitSteps] executed in order. Also the tail of resume path 3:
     * `SnIndex.ensureReady` calls this once the index has opened under the marker's new
     * passphrase. Idempotent. Nothing here touches the index.
     */
    fun commit(context: Context, marker: RotationMarker) {
        val app = context.applicationContext
        for (step in RotationPlan.commitSteps(marker.minted)) {
            when (step) {
                RotationPlan.CommitStep.SET_GLOBAL -> PassphraseStore.setGlobalPassphrase(app, marker.newPassphrase)
                RotationPlan.CommitStep.CLEAR_ACK -> PassphraseStore.clearRecoveryKeyAcknowledged(app)
                RotationPlan.CommitStep.CLEAR_RAW_KEYS -> KeyMaterial.clearAll(app)
                RotationPlan.CommitStep.SET_SESSION -> { KeySession.set(marker.newPassphrase); PassphraseCache.clear() }
                RotationPlan.CommitStep.CLEAR_MARKER -> PassphraseStore.clearRotationMarker(app)
            }
        }
        Slog.d(TAG) { "rotation committed (minted=${marker.minted}, quarantined=${marker.quarantined.size})" }
    }
}
