package com.symmetricalpalmtree.notesproutsn.library

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.crypto.KeyScope
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookPassphrasePrompt
import com.symmetricalpalmtree.notesproutsn.crypto.ScopeChange
import com.symmetricalpalmtree.notesproutsn.crypto.SetPassphraseDialog
import com.symmetricalpalmtree.notesproutsn.encryption.EncryptionActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * **The library sheet's two key rows** (arc 26 / U5, D4) — *Change passphrase…* and *Change
 * encryption scope…*, and everything between the tap and the toast: the dialogs, the prompt, the
 * progress box, and the one call into [ScopeChange] that actually re-keys the file.
 *
 * The library long-press sheet is the **only** door onto a notebook's key (U5's phase-start answer
 * — the notebook's own bar carries none), and it is a door precisely because it is the one place a
 * notebook is guaranteed cold: [ScopeChange] refuses an open file, and from here nothing is open.
 *
 * What each row does is [ScopeChange.route]'s answer, not this class's: a `GLOBAL` notebook has no
 * passphrase of its own to change, so that row is a redirect to the Encryption screen rather than a
 * disabled row — nothing here is ever disabled. The re-key itself is 4–8 s on the Nomad, which on
 * e-ink is long enough to read as a hang, so it runs under a non-cancelable "Re-keying…" box.
 *
 * A failure is one sentence and no detail: the file is untouched (the re-key is atomic and the
 * index is written only after it succeeds), so "nothing was changed" is the whole truth. Only the
 * exception's class name is logged — never a passphrase, never a message that might carry one.
 */
class ScopeChangeFlow(
    private val activity: AppCompatActivity,
    /** Called when the flow is over however it ended — the caller drops its launch latch, and
     *  rebuilds the grid when [changed] (a lock appears or goes, and a cover with it). */
    private val onDone: (changed: Boolean) -> Unit,
) {

    /** *Change passphrase…* — the row, from the tap. [scope] is the card's own. */
    fun changePassphrase(notebookId: String, name: String, scope: KeyScope) =
        run(ScopeChange.Row.CHANGE_PASSPHRASE, notebookId, name, scope)

    /** *Change encryption scope…* — the row, from the tap. */
    fun changeScope(notebookId: String, name: String, scope: KeyScope) =
        run(ScopeChange.Row.CHANGE_SCOPE, notebookId, name, scope)

    private fun run(row: ScopeChange.Row, notebookId: String, name: String, scope: KeyScope) {
        val title = activity.getString(
            if (row == ScopeChange.Row.CHANGE_PASSPHRASE) R.string.change_passphrase_title
            else R.string.change_scope_title
        )
        activity.lifecycleScope.launch {
            var changed = false
            try {
                changed = when (ScopeChange.route(row, scope)) {
                    ScopeChange.Route.REDIRECT_TO_ENCRYPTION -> { redirect(); false }
                    ScopeChange.Route.NOTEBOOK_PASSPHRASE -> newPassphrase(notebookId, name, title)
                    ScopeChange.Route.GLOBAL_TO_NOTEBOOK -> toNotebook(notebookId, name, title)
                    ScopeChange.Route.NOTEBOOK_TO_GLOBAL -> toGlobal(notebookId, name, title)
                }
            } finally {
                onDone(changed)
            }
        }
    }

    /** The `GLOBAL` half of *Change passphrase…*: there is nothing notebook-shaped to change, and
     *  the thing the user actually means lives one screen away. */
    private fun redirect() {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.change_passphrase_title)
                .setMessage(R.string.change_passphrase_global_body)
                .setPositiveButton(R.string.change_passphrase_open_encryption) { _, _ ->
                    activity.startActivity(EncryptionActivity.intent(activity))
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /** A notebook-scoped notebook's own passphrase, replaced. The current one is collected by the
     *  one prompt (which verifies it against the file), the new one by the one dialog. */
    private suspend fun newPassphrase(notebookId: String, name: String, title: String): Boolean {
        val global = session(title) ?: return false
        val current = NotebookPassphrasePrompt.ask(activity, notebookId, name) ?: return false
        val typed = SetPassphraseDialog.ask(
            activity,
            title = activity.getString(R.string.set_passphrase_title, name),
            helperText = activity.getString(R.string.set_passphrase_helper),
            current = current,
        ) ?: return false
        return rekey(title) {
            // The scope can still come back GLOBAL — the downgrade rule, if the new passphrase IS
            // this device's key. The passphrase changed either way, which is what the toast says.
            ScopeChange.changePassphrase(activity, notebookId, current, typed, global)
        }
            .also { if (it) toast(R.string.change_passphrase_done_toast) }
    }

    /** `GLOBAL` → its own passphrase. The current key is the session's, so nothing is prompted for
     *  it; the new one is refused if it is that key (`current = global`), which is why the
     *  downgrade rule is never reached from this door. */
    private suspend fun toNotebook(notebookId: String, name: String, title: String): Boolean {
        val global = session(title) ?: return false
        if (!confirm(
                title,
                activity.getString(R.string.change_scope_to_notebook_body, name),
                activity.getString(R.string.change_scope_to_notebook_confirm),
            )
        ) return false
        val typed = SetPassphraseDialog.ask(
            activity,
            title = activity.getString(R.string.set_passphrase_title, name),
            helperText = activity.getString(R.string.set_passphrase_helper),
            current = global,
            sameMessage = activity.getString(R.string.passphrase_rule_same_global),
        ) ?: return false
        return rekey(title) { ScopeChange.toNotebook(activity, notebookId, global, typed) }
            .also { if (it) toast(R.string.change_scope_to_notebook_done) }
    }

    /** Its own passphrase → `GLOBAL`. The current one is prompted for and verified before the
     *  re-key; the cover stays absent until the next seal paints one. */
    private suspend fun toGlobal(notebookId: String, name: String, title: String): Boolean {
        val global = session(title) ?: return false
        if (!confirm(
                title,
                activity.getString(R.string.change_scope_to_global_body, name),
                activity.getString(R.string.change_scope_to_global_confirm),
            )
        ) return false
        val current = NotebookPassphrasePrompt.ask(activity, notebookId, name) ?: return false
        return rekey(title) { ScopeChange.toGlobal(activity, notebookId, current, global) }
            .also { if (it) toast(R.string.change_scope_to_global_done) }
    }

    /** The device's key, or the dialog that says there is none. Behind `IndexGuard` there always
     *  is one — this is the belt over the braces, never a user state. */
    private fun session(title: String): String? {
        val global = KeySession.get()
        if (global == null) Dialogs.problem(activity, title, activity.getString(R.string.change_key_no_session))
        return global
    }

    private suspend fun confirm(title: String, message: String, positive: String): Boolean =
        suspendCancellableCoroutine { cont ->
            if (activity.isFinishing || activity.isDestroyed) { cont.resume(false); return@suspendCancellableCoroutine }
            var yes = false
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positive) { _, _ -> yes = true }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            )
            dialog.setOnDismissListener { if (cont.isActive) cont.resume(yes) }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
        }

    /** The re-key under a box that cannot be dismissed — 4–8 s on the Nomad, and a screen that says
     *  nothing for that long reads as a hang on e-ink. True when the work finished. */
    private suspend fun rekey(title: String, work: suspend () -> Unit): Boolean {
        val progress = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(R.string.change_key_progress)
                .setCancelable(false)
                .create()
        ).also { it.show() }
        val error = try {
            work()
            null
        } catch (e: Exception) {
            e
        } finally {
            runCatching { progress.dismiss() }
        }
        if (error != null) {
            // The class name and nothing else: an exception message from this path could carry a
            // file name or, worse, something a passphrase was passed through.
            Log.w(TAG, "re-key failed: ${error.javaClass.simpleName}")
            Dialogs.problem(activity, title, activity.getString(R.string.change_key_failed_body))
            return false
        }
        return true
    }

    private fun toast(messageRes: Int) =
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "ScopeChange"
    }
}
