package com.symmetricalpalmtree.notesproutsn.crypto

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.databinding.DialogPassphraseNewBinding
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * **The one "choose a passphrase for this notebook" dialog** (arc 26 / U5, D4) — new + confirm,
 * [PassphraseRules] on the way out, shared by every door that sets a notebook's own passphrase:
 * the New Notebook screen's *Its own passphrase* radio, the library sheet's *Change passphrase…*
 * and *Change encryption scope…*. (U4's debug prototype was this code; the debug item is gone.)
 *
 * It reuses the rotation screen's "new passphrase" layout with the mode radios taken away
 * ([DialogPassphraseNewBinding] — `modeGroup` GONE, `ownFields` VISIBLE): a notebook passphrase is
 * always chosen, never minted, so there is nothing to choose *between*. The helper line under the
 * fields is the caller's sentence, and every verdict lands in the layout's own error view rather
 * than a second dialog — a rejected entry keeps what was typed.
 *
 * The IME is never hidden and the window is `ADJUST_RESIZE`: on Ratta a hardware keyboard types
 * only while the on-screen keyboard is shown. The passphrase is returned to the caller and nothing
 * else — never logged, never in an Intent, never in a message.
 */
object SetPassphraseDialog {

    /**
     * The chosen passphrase (already [PassphraseRules.normalize]d), or null when the person
     * cancelled. [current] is the passphrase in force when the caller knows it, so re-choosing it
     * is refused with [sameMessage] — the sheet's GLOBAL → NOTEBOOK row passes the device's own key
     * there, which is what keeps og's downgrade rule off the glass entirely.
     */
    suspend fun ask(
        activity: Activity,
        title: String,
        helperText: String,
        current: String? = null,
        sameMessage: String = activity.getString(R.string.passphrase_rule_same),
    ): String? = suspendCancellableCoroutine { cont ->
        val view = DialogPassphraseNewBinding.inflate(activity.layoutInflater)
        view.modeGroup.visibility = View.GONE
        view.ownFields.visibility = View.VISIBLE
        view.helper.text = helperText
        var accepted: String? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(view.root)
                .setPositiveButton(R.string.set_passphrase_set, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(accepted) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
        // Wired after show(): the default listener dismisses before anything can object.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = view.newField.text?.toString().orEmpty()
            val confirm = view.confirmField.text?.toString().orEmpty()
            when (PassphraseRules.check(typed, confirm, current)) {
                PassphraseRules.Verdict.OK -> {
                    accepted = PassphraseRules.normalize(typed)
                    dialog.dismiss()
                }

                PassphraseRules.Verdict.TOO_SHORT ->
                    showError(view, activity.getString(R.string.passphrase_rule_too_short, PassphraseRules.MIN_LENGTH))

                PassphraseRules.Verdict.MISMATCH ->
                    showError(view, activity.getString(R.string.passphrase_rule_mismatch))

                PassphraseRules.Verdict.SAME_AS_CURRENT -> showError(view, sameMessage)
            }
        }
        view.newField.requestFocus()
    }

    private fun showError(view: DialogPassphraseNewBinding, message: String) {
        view.error.visibility = View.VISIBLE
        view.error.text = message
    }
}
