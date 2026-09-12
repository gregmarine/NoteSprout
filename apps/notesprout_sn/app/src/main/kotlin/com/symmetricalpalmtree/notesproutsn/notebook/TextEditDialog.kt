package com.symmetricalpalmtree.notesproutsn.notebook

import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * Write a text object's words: one multi-line field, Save, Cancel (arc 28 / H2) —
 * [HeadingEditDialog]'s shape grown a few lines tall, because typing into a page is the same
 * interaction whether the result is a title or a paragraph.
 *
 * **Raw Markdown, shown as it is stored.** Unlike a heading there is no prefix to strip and no level
 * button to fight: what the field holds is exactly the `text` column, `#`s, `-`s and all, and the
 * renderer is what turns it into headings, lists and rules on the page. There is no format bar for
 * the same reason the notebook has no pen panel — the source *is* the control.
 *
 * **An empty Save is a real answer**, not a validation failure: clearing the words is how a text
 * object is taken back off the page (D1 — a blank one never exists), and this dialog does not
 * second-guess it. [onSave] gets [TextLines.typed]'s tidy of the field — trailing whitespace off
 * each line, blank lines above and below dropped, the interior untouched because two spaces before a
 * newline are a Markdown line break and a run of blank lines is the author's spacing — and `""`
 * means delete. [onCancel] fires on the negative button **and** on a Back or an outside dismiss:
 * to a caller that started something on the way in, those three are one answer. Nothing here touches
 * the store.
 *
 * **Ratta: the IME is never hidden.** On Supernote a hardware keyboard only delivers keys while the
 * IME is shown, so a `hideSoftInputFromWindow` anywhere in this dialog would strand a keyboard user
 * mid-paragraph (the same rule as `UnlockActivity` and the heading dialog beside it). There is none,
 * and there must not be one — the only soft-input call here asks for the IME, on the way in. For the
 * same reason there is no `IME_ACTION_DONE`: in a multi-line box Enter is a newline, and a Done
 * action that closed the dialog would make paragraphs impossible to type.
 */
object TextEditDialog {

    /** Lines of field before it scrolls — tall enough to see a paragraph, short enough to leave the
     *  page visible behind the dialog on the Nomad. */
    private const val MIN_LINES = 4
    private const val MAX_LINES = 12

    fun show(
        activity: AppCompatActivity,
        initial: String,
        onSave: (String) -> Unit,
        onCancel: () -> Unit = {},
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val input = AppCompatEditText(activity).apply {
            setText(initial)
            // Caret at the end, nothing selected: the user came here to add to or fix what is
            // there, and a full selection turns the first keystroke into a wipe.
            setSelection(initial.length)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            background = ContextCompat.getDrawable(activity, R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = MIN_LINES
            maxLines = MAX_LINES
            gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
        }

        var answered = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.text_edit_title)
                .setView(wrapper)
                .setPositiveButton(R.string.heading_edit_save) { _, _ ->
                    answered = true
                    onSave(TextLines.typed(input.text?.toString().orEmpty()))
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    answered = true
                    onCancel()
                }
                .create()
        )
        // Back and an outside touch are the Cancel button by another route. A button press
        // dismisses rather than cancels, so this cannot double up on it — the latch says so out
        // loud rather than leaving the reader to know that.
        dialog.setOnCancelListener { if (!answered) { answered = true; onCancel() } }
        // Ask for the IME with the window rather than poking InputMethodManager after the fact: on
        // Supernote the panel has to be up for a hardware keyboard to type at all.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
