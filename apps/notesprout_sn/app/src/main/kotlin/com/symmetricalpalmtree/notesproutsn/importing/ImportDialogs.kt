package com.symmetricalpalmtree.notesproutsn.importing

import android.app.Activity
import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.crypto.ImportChoice
import com.symmetricalpalmtree.notesproutsn.crypto.SetPassphraseDialog
import com.symmetricalpalmtree.notesproutsn.databinding.DialogImportKeyingBinding
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The import's questions, as **suspend functions** (arc 16 / I1) — so [ImportFlow] reads as the
 * pipeline it is (probe → unlock → collision → placement → name) instead of a ladder of callbacks
 * that each have to remember where they were.
 *
 * Every one of them answers `null` for "the user backed out", and every one of them treats a
 * *dismissal* as backing out: the dialog's `setOnDismissListener` is what resumes the caller, so a
 * back press, a tap outside and the Cancel button are one answer and none of them can strand the
 * flow waiting for a reply that will never come.
 *
 * They are the family's dialogs — [Dialogs.style], inkBlack, no ripple — and they explain rather
 * than confirm: nothing here is a toast, because every one of them is a question the user must
 * notice (the toast-vs-dialog rule).
 */
object ImportDialogs {

    /** Which button a choice dialog was answered with. */
    enum class Choice { PRIMARY, SECONDARY }

    /**
     * A two-way question with an honest third answer. [primaryRes] is the positive button,
     * [secondaryRes] the neutral one, and Cancel is the negative — so the two real choices sit
     * together and the way out is where every other dialog in the app puts it.
     */
    suspend fun choose(
        activity: Activity,
        @StringRes titleRes: Int,
        message: CharSequence,
        @StringRes primaryRes: Int,
        @StringRes secondaryRes: Int,
    ): Choice? = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
        var answer: Choice? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(primaryRes) { _, _ -> answer = Choice.PRIMARY }
                .setNeutralButton(secondaryRes) { _, _ -> answer = Choice.SECONDARY }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        // The dismiss listener is the single resume point: a back press and a tap outside answer
        // exactly as Cancel does, and a button's own listener has already recorded which.
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(answer) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    /** A list of labels, answered by index — the format chooser when more than one importer accepts
     *  the picked file. Dismiss is "no thanks", like every other question here. */
    suspend fun pickFromList(
        activity: Activity,
        @StringRes titleRes: Int,
        labels: List<String>,
    ): Int? = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
        var chosen: Int? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setItems(labels.toTypedArray()) { _, which -> chosen = which }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(chosen) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    /**
     * The foreign-file passphrase prompt. One attempt per showing — the flow loops, so a wrong
     * passphrase comes back with [errorRes] filled in above the field rather than a second dialog
     * stacked on the first.
     *
     * **The IME is never hidden** (the Ratta rule, the same one written into `UnlockActivity`): on
     * Supernote a hardware keyboard only delivers keys while the IME is shown, so hiding it would
     * strand a keyboard user after the first wrong try. The window asks for it instead.
     *
     * The typed text is returned and never stored: it goes to the caller, into a verify, and out of
     * scope with the flow that collected it. It is never logged, never put in an Intent, never
     * written anywhere.
     */
    suspend fun passphrase(
        activity: Activity,
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int,
        @StringRes errorRes: Int? = null,
    ): String? = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
        val d = activity.resources.displayMetrics.density
        val ink = ContextCompat.getColor(activity, R.color.inkBlack)
        val field = NameDialog.input(activity, R.string.import_passphrase_hint).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Never in a saved state: a secret that survives a process death is a secret on disk.
            isSaveEnabled = false
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(TextView(activity).apply {
                setText(bodyRes)
                setTextColor(ink)
                textSize = 14f
            })
            errorRes?.let { res ->
                addView(TextView(activity).apply {
                    setText(res)
                    setTextColor(ink)
                    textSize = 14f
                    setPadding(0, (8 * d).toInt(), 0, 0)
                })
            }
            addView(field, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (12 * d).toInt() })
        }
        var typed: String? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(wrapper)
                .setPositiveButton(R.string.import_unlock_action, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(typed) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
        // Wired after show(), the NameDialog way: an empty field must not close the dialog and
        // throw the user back to the start of the pipeline.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = field.text?.toString().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            typed = text
            dialog.dismiss()
        }
    }

    /**
     * **The keying chooser** (arc 26 / U5, decision 3) — asked only for a file a *foreign*
     * passphrase opened: keep that passphrase (the notebook gets its own), take this device's key,
     * or set a new notebook passphrase. og's three answers in og's order.
     *
     * Three answers do not fit an AlertDialog's button bar, so they are three rows in the body and
     * Cancel stays the negative button — where every other question here puts it. Dismissal is
     * backing out, exactly as above: nothing is written, and the import ends with the cache.
     */
    suspend fun keying(activity: Activity): ImportChoice.Choice? = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
        val view = DialogImportKeyingBinding.inflate(activity.layoutInflater)
        var answer: ImportChoice.Choice? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.import_keying_choose_title)
                .setView(view.root)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(answer) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        fun answerWith(choice: ImportChoice.Choice) {
            answer = choice
            dialog.dismiss()
        }
        view.choiceKeep.setOnClickListener { answerWith(ImportChoice.Choice.KEEP) }
        view.choiceDevice.setOnClickListener { answerWith(ImportChoice.Choice.DEVICE_KEY) }
        view.choiceNew.setOnClickListener { answerWith(ImportChoice.Choice.NEW_PASSPHRASE) }
        dialog.show()
    }

    /**
     * The new-passphrase dialog behind *Set a new notebook passphrase* — the app's one
     * set-a-notebook-passphrase dialog ([SetPassphraseDialog]), titled with the file being imported
     * and carrying og's sentence about being asked for it on every open. No `current` is passed:
     * choosing this device's key here is a legal answer, and [ImportChoice] answers it with the
     * downgrade rule (the notebook stays `GLOBAL`) rather than a refusal.
     *
     * Null = the person backed out, which backs the whole import out like every other cancelled
     * question. The passphrase is returned and nothing else — never logged, never in an Intent.
     */
    suspend fun newNotebookPassphrase(activity: Activity, name: String): String? =
        SetPassphraseDialog.ask(
            activity,
            title = activity.getString(R.string.set_passphrase_title, name),
            helperText = activity.getString(R.string.set_passphrase_helper),
        )
}
