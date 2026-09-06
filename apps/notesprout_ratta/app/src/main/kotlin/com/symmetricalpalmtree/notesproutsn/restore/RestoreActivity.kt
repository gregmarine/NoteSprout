package com.symmetricalpalmtree.notesproutsn.restore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.bootstrap.BootstrapActivity
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.AttemptLimiter
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityRestoreBinding
import com.symmetricalpalmtree.notesproutsn.databinding.DialogNotebookPassphraseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * **Restore** (arc 27 / L3, decision 7) — the one screen that puts a whole library back. It is its
 * own screen rather than a section of Backup because a restore is six steps of its own, and because
 * reading a backup and writing one are two different questions (decision 3).
 *
 * The screen owns no policy: [RestoreEngine] decides everything and never throws, and this screen
 * decides only what the person is told. Two states in one layout:
 *
 *  - **Sources.** Two rows — a folder on this device (the platform picker), and L4's cloud row,
 *    which is **GONE** until it has a source behind it. Never disabled: on e-ink that is invisible.
 *  - **The backups found.** One tappable row per backup, naming it, its notebook count, its size
 *    and when its index was last written — the derived rule "enough to tell two backups apart
 *    without opening either".
 *
 * The grant on the picked tree is **never persisted** (decision 3: a restore never *sets* a
 * destination — persisting the tree would be exactly that). It lives for this showing and no
 * longer, which is why the source is a field and not a stored URI.
 *
 * The run is one non-cancelable progress dialog through the engine's five doors —
 * `preflight` → `stage` → `validate` → `proveCached` (else the key prompt) → `commit` — and every
 * ending is a dialog, never a toast. Four of them:
 *
 *  - **Committed** — the counts, and one action: Restart. The index is closed by then, so
 *    `BootstrapActivity.relaunchIntent` + `finishAffinity()` is the only way off this screen.
 *  - **RolledBack** — the swap failed and renamed itself back. The library is whole but the index
 *    is closed (R4), so this ending also has exactly one action: Restart.
 *  - **Interrupted** — the restored index landed but the key step threw. Also one action,
 *    Restart; the relaunch may stop at Unlock, where the backup's own key opens it.
 *  - **Refused** — a named refusal, all of which happen before the index closes, so the screen
 *    simply says why and stays on the list.
 *
 * The proven passphrase lives in one local `val` between the proof and the commit — never a
 * Bundle, never a field, never an Intent, never a log line. Neither is a URI or a folder name.
 */
class RestoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRestoreBinding

    /** The picked tree, for this showing only. Null until a folder has listed at least one backup. */
    private var source: RestoreSource? = null

    /** What the last listing found, in the order the source gave them. */
    private var backups: List<RestoreBackup> = emptyList()

    /** The busy dialog while one is up — the folder listing's, then the run's. Non-cancelable:
     *  neither is something to half-leave, and the engine has no cancel to offer. */
    private var progress: AlertDialog? = null

    /** True from the confirm's Replace until the flow ends. A second tap in the e-ink feedback gap
     *  does nothing; the modal progress dialog covers the rest of the screen meanwhile. */
    private val running = AtomicBoolean(false)

    /**
     * The folder pick. **No persistable grant is taken** — see the class comment. A cancelled
     * picker changes nothing and explains nothing.
     */
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) { Slog.d(TAG) { "folder picker cancelled" }; return@registerForActivityResult }
        lifecycleScope.launch { adoptFolder(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The guard is 0 on Ratta — chrome sits flush at the top edge; the inset pass is still how
        // the screen clears a navigation bar if the device has one.
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnFromFolder.setOnClickListener { onPickFolderTap() }
        binding.btnChooseAnother.setOnClickListener { onPickFolderTap() }
        // btnFromCloud has no listener and stays GONE until L4 gives it a source.
    }

    override fun onDestroy() {
        // The guard bounce still runs this callback, and a `lateinit` teardown would crash on the
        // way out of a task Android rebuilt after a background process kill.
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        super.onDestroy()
    }

    // ── Choosing a folder ────────────────────────────────────────────────────

    private fun onPickFolderTap() {
        if (running.get()) { Slog.d(TAG) { "folder tap ignored: a restore is going" }; return }
        try {
            folderLauncher.launch(null)
        } catch (e: Exception) {
            Log.w(TAG, "no folder picker: $e")
            Dialogs.problem(this, R.string.restore_no_picker_title, R.string.restore_no_picker_body)
        }
    }

    /** List what the picked tree holds (one level deep — D1's `dev/` rule) and show it. */
    private suspend fun adoptFolder(uri: Uri) {
        val picked = SafRestoreSource(contentResolver, uri)
        showProgress(getString(R.string.restore_reading))
        val result = picked.listBackups()
        hideProgress()
        if (isFinishing || isDestroyed) return
        when (result) {
            is ListResult.Failed -> sourceProblem(result.problem)
            is ListResult.Backups -> {
                source = picked
                backups = result.backups
                binding.folderPath.text = folderLabel(uri)
                Slog.d(TAG) { "listed ${backups.size} backup(s)" }
                renderList()
            }
        }
    }

    /**
     * The picked folder, as readably as a tree URI honestly allows — Backup's own rule. A document
     * id is `<volume>:<relative path>`, so the volume prefix goes and the path the person picked is
     * what is left. The raw URI is never shown.
     */
    private fun folderLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return getString(R.string.restore_folder_caption)
        return id.substringAfter(':').ifEmpty { id }
    }

    // ── The list ─────────────────────────────────────────────────────────────

    private fun renderList() {
        binding.sourcesPane.visibility = View.GONE
        binding.listPane.visibility = View.VISIBLE
        binding.rows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (backup in backups) binding.rows.addView(buildRow(inflater, backup))
    }

    private fun buildRow(inflater: LayoutInflater, backup: RestoreBackup): View {
        val view = inflater.inflate(R.layout.item_restore_backup, binding.rows, false)
        view.findViewById<TextView>(R.id.restoreRowName).text = backup.name
        view.findViewById<TextView>(R.id.restoreRowDetail).text = getString(
            R.string.restore_row_detail,
            notebooksText(backup.notebookCount),
            Formatter.formatShortFileSize(this, backup.totalBytes.coerceAtLeast(0L)),
            stampText(backup.indexModifiedAt),
        )
        view.setOnClickListener { confirmReplace(backup) }
        return view
    }

    private fun notebooksText(count: Int): String =
        if (count == 1) getString(R.string.restore_notebooks_one)
        else getString(R.string.restore_notebooks_many, count)

    /** The index's last-modified time in the device's own formats — the date, then the time. */
    private fun stampText(at: Long): String {
        val date = Date(at)
        return android.text.format.DateFormat.getMediumDateFormat(this).format(date) + " " +
            android.text.format.DateFormat.getTimeFormat(this).format(date)
    }

    // ── The run ──────────────────────────────────────────────────────────────

    /** The one question asked before the point of no return, and the only cancelable dialog in the
     *  whole flow. It names the backup and says plainly what replace-all means (decision 2/5). */
    private fun confirmReplace(backup: RestoreBackup) {
        if (running.get()) { Slog.d(TAG) { "row tap ignored: a restore is going" }; return }
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(
                    getString(
                        R.string.restore_confirm_body,
                        backup.name,
                        notebooksText(backup.notebookCount),
                        stampText(backup.indexModifiedAt),
                    )
                )
                .setPositiveButton(R.string.restore_confirm_replace) { _, _ -> runRestore(backup) }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    private fun runRestore(backup: RestoreBackup) {
        val src = source ?: return
        if (!running.compareAndSet(false, true)) {
            Slog.d(TAG) { "restore tap ignored: a run is already going" }
            return
        }
        // A staged copy of a real library is minutes of IO; the screen stays on for it, exactly as
        // a rotation's does.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                restoreFlow(src, backup)
            } finally {
                running.set(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                hideProgress()
            }
        }
    }

    /**
     * The engine's five doors in order. Every refusal before the commit leaves the screen on the
     * list with a dialog saying why; only the commit's outcomes end the screen.
     */
    private suspend fun restoreFlow(src: RestoreSource, backup: RestoreBackup) {
        showProgress(getString(R.string.restore_progress_checking), getString(R.string.restore_progress_title))

        RestoreEngine.preflight(this, backup)?.let { hideProgress(); problemDialog(it); return }

        val manifest = when (
            val staged = RestoreEngine.stage(this, src, backup) { done, total ->
                // The progress callback arrives on the engine's IO thread.
                runOnUiThread { setProgress(getString(R.string.restore_progress_copying, done, total)) }
            }
        ) {
            is RestoreEngine.StageResult.Failed -> { hideProgress(); problemDialog(staged.problem); return }
            is RestoreEngine.StageResult.Staged -> staged.manifest
        }

        setProgress(getString(R.string.restore_progress_checking))
        RestoreEngine.validate(this, manifest)?.let {
            discardStaging()
            hideProgress()
            problemDialog(it)
            return
        }

        // Decision 6 — the staged index must open before anything live is touched. A same-device
        // backup just works and is never prompted for.
        setProgress(getString(R.string.restore_progress_unlocking))
        val proven = RestoreEngine.proveCached(this) ?: run {
            hideProgress()
            if (isFinishing || isDestroyed) { discardStaging(); return }
            val typed = askKey()
            if (typed == null) {
                // The loop was abandoned: the engine only discards on its own refusals (L2's
                // read-back note), so the staged copy goes from here.
                discardStaging()
                Slog.d(TAG) { "key prompt abandoned — staging discarded" }
                return
            }
            showProgress(getString(R.string.restore_progress_unlocking), getString(R.string.restore_progress_title))
            typed
        }

        setProgress(getString(R.string.restore_progress_installing))
        val outcome = RestoreEngine.commit(this, manifest, proven)
        hideProgress()
        Slog.d(TAG) { "restore outcome: ${outcome::class.simpleName}" }
        onOutcome(outcome, backup.name)
    }

    /** Staging is a directory tree on the library volume — never deleted on the UI thread. */
    private suspend fun discardStaging() = withContext(Dispatchers.IO) {
        runCatching { RestoreStaging.discard(applicationContext) }
            .onFailure { Log.w(TAG, "staging discard failed", it) }
        Unit
    }

    private fun onOutcome(outcome: RestoreEngine.Outcome, backupName: String) {
        when (outcome) {
            is RestoreEngine.Outcome.Committed -> endDialog(
                getString(R.string.restore_done_title),
                getString(doneBody(outcome.notebooks, outcome.stores), outcome.notebooks, outcome.stores, backupName),
            )

            // The swap failed and renamed itself back: the library is whole, but the index is
            // closed (R4), so the only way out of this process is Bootstrap.
            is RestoreEngine.Outcome.RolledBack -> endDialog(
                getString(R.string.restore_failed_title),
                getString(R.string.restore_failed_body),
            )

            // The restored index landed but the key step threw: no way back (the aside is gone),
            // and the relaunch may stop at Unlock, where the backup's own key opens it.
            is RestoreEngine.Outcome.Interrupted -> endDialog(
                getString(R.string.restore_interrupted_title),
                getString(R.string.restore_interrupted_body, (outcome.problem as? RestoreEngine.Problem.Unexpected)?.what ?: ""),
            )

            // Every refusal is answered before the index closes (the engine's contract, including
            // its pre-close catch), so the index is still open and the screen stays.
            is RestoreEngine.Outcome.Refused -> problemDialog(outcome.problem)
        }
    }

    private fun doneBody(notebooks: Int, stores: Int): Int = when {
        notebooks == 1 && stores == 1 -> R.string.restore_done_one_one
        notebooks == 1 -> R.string.restore_done_one_many
        stores == 1 -> R.string.restore_done_many_one
        else -> R.string.restore_done_many_many
    }

    /** The one ending with one action. Not cancelable: after a commit or a rollback this process
     *  has no open index and nothing else on this screen may run. */
    private fun endDialog(title: CharSequence, body: CharSequence) {
        if (isFinishing || isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton(R.string.restore_restart) { _, _ -> relaunch() }
                .setCancelable(false)
                .create()
        ).show()
    }

    private fun relaunch() {
        startActivity(BootstrapActivity.relaunchIntent(this, thenBackup = false))
        finishAffinity()
    }

    // ── The key prompt (decision 6 / R6) ─────────────────────────────────────

    /**
     * `NotebookPassphrasePrompt`'s shape, over the *staged* index: one dialog that keeps its typing
     * across a wrong entry, the entry row GONE while the `RESTORE` bucket is locked out with the
     * countdown ticking in its place, and the IME never hidden (on Ratta a hardware keyboard types
     * only while it is shown).
     *
     * [RestoreEngine.proveTyped] verifies **as typed, then normalized** (R6) and records the
     * attempt itself — nothing here touches [AttemptLimiter] but its lockout clock. Resumes with
     * the proven passphrase, or null when the person gave up.
     */
    private suspend fun askKey(): String? = suspendCancellableCoroutine { cont ->
        val app = applicationContext
        val view = DialogNotebookPassphraseBinding.inflate(layoutInflater)
        view.body.setText(R.string.restore_key_body)
        view.field.setHint(R.string.restore_key_hint)
        view.error.setText(R.string.restore_key_wrong)
        val handler = Handler(Looper.getMainLooper())
        val scope = CoroutineScope(Dispatchers.Main + Job())
        var accepted: String? = null
        var busy = false
        var lockedOut = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.restore_key_title)
                .setView(view.root)
                .setPositiveButton(R.string.restore_key_unlock, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        fun refreshLockout() {
            val remaining = AttemptLimiter.check(app, RestoreEngine.LIMITER_KEY) - System.currentTimeMillis()
            if (remaining > 0) {
                lockedOut = true
                view.entryRow.visibility = View.GONE
                view.lockoutText.visibility = View.VISIBLE
                view.lockoutText.text = getString(R.string.unlock_locked_out, formatSeconds(remaining))
                handler.postDelayed({ if (dialog.isShowing) refreshLockout() }, 1000L)
            } else {
                view.lockoutText.visibility = View.GONE
                view.entryRow.visibility = View.VISIBLE
                // A lockout that just lifted is a fresh start: the error that earned it goes, and
                // the field takes focus so the keyboard is up for the next try.
                if (lockedOut) { lockedOut = false; view.error.visibility = View.GONE; view.field.requestFocus() }
            }
        }

        fun attempt() {
            if (busy) return
            val typed = view.field.text?.toString()?.trim().orEmpty()
            if (typed.isEmpty()) return
            if (AttemptLimiter.check(app, RestoreEngine.LIMITER_KEY) > System.currentTimeMillis()) {
                refreshLockout()
                return
            }
            busy = true
            view.error.visibility = View.GONE
            view.progress.visibility = View.VISIBLE
            scope.launch {
                val proven = RestoreEngine.proveTyped(app, typed)
                busy = false
                if (!dialog.isShowing) return@launch
                view.progress.visibility = View.GONE
                if (proven != null) {
                    accepted = proven
                    dialog.dismiss()
                } else {
                    view.error.visibility = View.VISIBLE
                    view.field.text?.clear()
                    refreshLockout()
                }
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            scope.coroutineContext[Job]?.cancel()
            if (cont.isActive) cont.resume(accepted)
        }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
        // Wired after show(): the default listener dismisses before anything can object.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt() }
        view.field.setOnEditorActionListener { _, _, _ -> attempt(); true }
        refreshLockout()
        // Focus first, so the IME rises with the dialog instead of after a tap on the field.
        view.field.requestFocus()
    }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    // ── Progress ─────────────────────────────────────────────────────────────

    private fun showProgress(message: String, title: String? = null) {
        if (isFinishing || isDestroyed) return
        hideProgress()
        progress = Dialogs.style(
            AlertDialog.Builder(this)
                .apply { if (title != null) setTitle(title) }
                .setMessage(if (title == null) message else message + "\n\n" + getString(R.string.restore_progress_keep_open))
                .setCancelable(false)
                .create()
        ).also { it.show() }
    }

    /** The phase line, with the standing "keep the app open" tail the rotation's progress carries. */
    private fun setProgress(message: String) {
        if (isFinishing || isDestroyed) return
        progress?.setMessage(message + "\n\n" + getString(R.string.restore_progress_keep_open))
    }

    private fun hideProgress() {
        progress?.let { runCatching { it.dismiss() } }
        progress = null
    }

    // ── Every problem, by name ───────────────────────────────────────────────

    /** One dialog per [RestoreEngine.Problem] kind — never a toast, and never a URI or a folder
     *  name in the text. A file name (a notebook UUID, a store package) is safe to show. */
    private fun problemDialog(problem: RestoreEngine.Problem) {
        when (problem) {
            RestoreEngine.Problem.RotationPending ->
                Dialogs.problem(this, R.string.restore_problem_rotation_title, R.string.restore_problem_rotation_body)

            RestoreEngine.Problem.NotebookHeld ->
                Dialogs.problem(this, R.string.restore_problem_held_title, R.string.restore_problem_held_body)

            is RestoreEngine.Problem.NotEnoughSpace -> Dialogs.problem(
                this,
                R.string.restore_problem_space_title,
                getString(
                    R.string.restore_problem_space_body,
                    Formatter.formatShortFileSize(this, problem.shortfallBytes.coerceAtLeast(0L)),
                ),
            )

            is RestoreEngine.Problem.Source -> sourceProblem(problem.problem)

            is RestoreEngine.Problem.InvalidFile -> Dialogs.problem(
                this,
                R.string.restore_problem_invalid_title,
                getString(R.string.restore_problem_invalid_body, problem.fileName),
            )

            RestoreEngine.Problem.NoKey ->
                Dialogs.problem(this, R.string.restore_problem_no_key_title, R.string.restore_problem_no_key_body)

            RestoreEngine.Problem.ParkFailed ->
                Dialogs.problem(this, R.string.restore_problem_park_title, R.string.restore_problem_park_body)

            is RestoreEngine.Problem.SwapFailed -> Dialogs.problem(
                this,
                R.string.restore_problem_swap_title,
                getString(R.string.restore_problem_swap_body, problem.step.toString()),
            )

            is RestoreEngine.Problem.Unexpected -> Dialogs.problem(
                this,
                R.string.restore_problem_unexpected_title,
                getString(R.string.restore_problem_unexpected_body, problem.what),
            )
        }
    }

    /** The source's own kinds — a listing that failed is never confused with an empty folder. */
    private fun sourceProblem(problem: RestoreProblem) {
        when (problem) {
            RestoreProblem.SourceUnreachable -> Dialogs.problem(
                this, R.string.restore_problem_unreachable_title, R.string.restore_problem_unreachable_body,
            )

            RestoreProblem.ListingFailed -> Dialogs.problem(
                this, R.string.restore_problem_listing_title, R.string.restore_problem_listing_body,
            )

            RestoreProblem.NotABackup -> Dialogs.problem(
                this, R.string.restore_problem_not_backup_title, R.string.restore_problem_not_backup_body,
            )

            is RestoreProblem.FetchFailed -> Dialogs.problem(
                this,
                R.string.restore_problem_fetch_title,
                getString(R.string.restore_problem_fetch_body, problem.fileName),
            )
        }
    }

    companion object {
        private const val TAG = "RestoreActivity"

        fun intent(context: Context): Intent = Intent(context, RestoreActivity::class.java)
    }
}
