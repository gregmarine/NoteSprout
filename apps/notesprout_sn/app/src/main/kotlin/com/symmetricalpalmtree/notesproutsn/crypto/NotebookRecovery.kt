package com.symmetricalpalmtree.notesproutsn.crypto

import android.app.Activity
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * **The last door for a notebook the ordinary open could not key** (arc 26 / U6, D5) — og's
 * `NotebookRecovery`, rebuilt on this app's seams.
 *
 * The rule it enforces: **holding a passphrase that opens the file must always be enough.**
 * Without it, an intact `.soil` whose key no longer matches what the app knows — restored from a
 * backup taken before a rotation, re-keyed on another device, quarantined by a rotation that could
 * not open it, or an index scope that drifted from the file — sends the person back to the library
 * with no way to even try.
 *
 * The notebook screen calls [offer] once per launch when [KeyFailure] says the open failed on the
 * key. The dialog explains ("Can't open <name>") and offers **Try a passphrase** / **Back to
 * library**. Try first tries, silently, the keys this device already holds — the cached global,
 * then a rotation in flight's new key — and only then puts up [NotebookPassphrasePrompt] (bucket =
 * the notebook id, so this is no softer a target than the front door). A key that works drops the
 * stale raw key ([KeyMaterial.invalidate]) and then [Plan.decide] says what makes the *next* open
 * ordinary:
 *
 *  - `GLOBAL` and the working key is not the global: **Repair and open** — [ScopeChange.toGlobal]
 *    re-keys the file to this device's key (the index already says `GLOBAL`; the call restamps the
 *    meta and clears the backup stamps). Declined = back to the library, nothing changed.
 *  - `NOTEBOOK`: the key is parked ([PassphraseCache.storeOnce]) so the reopen does not ask for
 *    what was just typed.
 *  - `GLOBAL` and the global itself worked: the raw key was stale and is now gone — reopen.
 *
 * Passphrases arrive from the store or the prompt and leave into a verify, a re-key or the park;
 * none is logged, none is in a message.
 */
object NotebookRecovery {

    private const val TAG = "NotebookRecovery"

    enum class Outcome {
        /** A key was found (and the file repaired if it needed to be) — reopen. */
        RETRY,
        /** Declined, cancelled, refused, or nothing fit — leave for the library. */
        DECLINED,
    }

    /** The pure half: whether to offer at all, the silent candidates, and what a working key means. */
    object Plan {
        /** Offer only for a key failure, and only once per launch. */
        fun shouldOffer(keyed: Boolean, isKeyFailure: Boolean, attempted: Boolean): Boolean =
            keyed && isKeyFailure && !attempted

        /** The keys to try before asking: the cached global, then a rotation's new one. */
        fun silentCandidates(global: String?, markerNew: String?): List<String> =
            listOfNotNull(global, markerNew).distinct()

        enum class Action { REPAIR_TO_GLOBAL, PARK_FOR_REOPEN, REOPEN }

        /** What a key that opened the file means for the next open. [keyIsGlobal] is whether the
         *  working key equals the cached global; [hasGlobal] whether there is one at all. */
        fun decide(scope: KeyScope, keyIsGlobal: Boolean, hasGlobal: Boolean): Action = when (scope) {
            KeyScope.NOTEBOOK -> Action.PARK_FOR_REOPEN
            KeyScope.GLOBAL -> if (hasGlobal && !keyIsGlobal) Action.REPAIR_TO_GLOBAL else Action.REOPEN
        }
    }

    /**
     * The whole flow, on the activity's scope. [scope] is what the index says — the same answer
     * the failed open was keyed on. Every dialog in it is dismissable; a cancel anywhere is
     * [Outcome.DECLINED].
     */
    suspend fun offer(activity: Activity, notebookId: String, name: String, scope: KeyScope): Outcome {
        if (activity.isFinishing || activity.isDestroyed) return Outcome.DECLINED
        val file = soilFile(activity, notebookId)
        if (!file.exists() || file.length() == 0L) return Outcome.DECLINED

        val start = confirm(
            activity,
            title = activity.getString(R.string.notebook_recovery_title, name),
            message = activity.getString(R.string.notebook_recovery_body),
            positive = activity.getString(R.string.notebook_recovery_try),
            negative = activity.getString(R.string.notebook_recovery_back),
        )
        if (!start) return Outcome.DECLINED

        val global = PassphraseStore.getGlobalPassphrase(activity)
        val markerNew = PassphraseStore.getRotationMarker(activity)?.newPassphrase
        val silent = Plan.silentCandidates(global, markerNew)
        var key: String? = withContext(Dispatchers.IO) {
            silent.firstOrNull { SoilCrypto.verifyPassphrase(file, it) }
        }
        if (key != null) Slog.d(TAG) { "a key this device holds opens $notebookId (candidate ${silent.indexOf(key)})" }
        if (key == null) {
            key = NotebookPassphrasePrompt.ask(
                activity, notebookId, name,
                body = activity.getString(R.string.notebook_recovery_prompt_body, name),
            )
        }
        val working = key ?: return Outcome.DECLINED

        // Whatever raw key was cached was derived against a salt that no longer applies.
        withContext(Dispatchers.IO) { KeyMaterial.invalidate(activity, notebookId) }

        return when (Plan.decide(scope, keyIsGlobal = working == global, hasGlobal = global != null)) {
            Plan.Action.REPAIR_TO_GLOBAL -> {
                val repair = confirm(
                    activity,
                    title = activity.getString(R.string.notebook_recovery_repair_title),
                    message = activity.getString(R.string.notebook_recovery_repair_body),
                    positive = activity.getString(R.string.notebook_recovery_repair_positive),
                    negative = activity.getString(R.string.notebook_recovery_back),
                )
                if (!repair) return Outcome.DECLINED
                if (rekeyToGlobal(activity, notebookId, working, global!!)) Outcome.RETRY else Outcome.DECLINED
            }
            Plan.Action.PARK_FOR_REOPEN -> {
                PassphraseCache.storeOnce(notebookId, working)
                Outcome.RETRY
            }
            Plan.Action.REOPEN -> Outcome.RETRY
        }
    }

    /** The re-key under a box that cannot be dismissed (ScopeChangeFlow's shape — 4–8 s on the
     *  Nomad). True when it finished; a failure explains and leaves the file exactly as it was. */
    private suspend fun rekeyToGlobal(activity: Activity, notebookId: String, current: String, global: String): Boolean {
        val progress = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.notebook_recovery_repair_title)
                .setMessage(R.string.change_key_progress)
                .setCancelable(false)
                .create()
        ).also { it.show() }
        val error = try {
            ScopeChange.toGlobal(activity, notebookId, current, global)
            null
        } catch (e: Exception) {
            e
        } finally {
            runCatching { progress.dismiss() }
        }
        if (error != null) {
            // The class name only: a message from this path could carry a file name.
            Log.w(TAG, "repair failed: ${error.javaClass.simpleName}")
            problem(activity, activity.getString(R.string.notebook_recovery_repair_failed_title), activity.getString(R.string.notebook_recovery_repair_failed_body))
            return false
        }
        Slog.d(TAG) { "repaired $notebookId to the global key" }
        return true
    }

    private suspend fun confirm(
        activity: Activity, title: CharSequence, message: CharSequence, positive: CharSequence, negative: CharSequence,
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(false); return@suspendCancellableCoroutine }
        var yes = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> yes = true }
                .setNegativeButton(negative, null)
                .create()
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(yes) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    /** [Dialogs.problem], awaited: the flow's answer must not race the dialog off the screen. */
    private suspend fun problem(activity: Activity, title: CharSequence, message: CharSequence): Unit =
        suspendCancellableCoroutine { cont ->
            if (activity.isFinishing || activity.isDestroyed) { cont.resume(Unit); return@suspendCancellableCoroutine }
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, null)
                    .create()
            )
            dialog.setOnDismissListener { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
        }
}
