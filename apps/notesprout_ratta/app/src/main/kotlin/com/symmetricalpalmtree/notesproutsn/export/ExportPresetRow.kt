package com.symmetricalpalmtree.notesproutsn.export

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import kotlinx.coroutines.launch

/**
 * **The Preset row** (arc 31 / HV3) — the whole screen half of saved export settings: the radios,
 * the *Save preset…* action, the long-press sheet and the three dialogs behind it.
 *
 * It lives here rather than in [ExportActivity] for one reason and it is the file's own: the
 * activity is over the ~800-line rule with a written justification, and every line of that
 * justification is about the export **flow** — the guard order, the keying lifecycle, the deletion
 * rule. None of this is that flow. What the activity keeps is only its side of the contract: the
 * screen state a preset reads and writes ([Host]).
 *
 * The rules the row keeps, each one the family's:
 *
 *  - **Only what can be applied is listed** ([ExportPresets.listable] over [Host.listedPackages]):
 *    a preset whose exporter is not installed — or is not listed at this scope — is GONE, never a
 *    radio that would refuse. It comes back the moment the exporter does, which is why the whole
 *    list is re-read at every discovery.
 *  - **The row is absent with nothing to list; the Save action is not.** Saving is available
 *    whenever there is a format chosen to save, and that is the only thing that hides it.
 *  - **Tapping the checked radio is a no-op** (the chooser's rule): on e-ink a grazed tap must not
 *    re-apply a preset over a half-typed password.
 *  - **None means "no preset is armed"**, not "reset the form": tapping it changes the tick and
 *    nothing else, exactly as a hand change does.
 *  - **A rename or a delete ends in a re-read**, because the pick may have been the row that went.
 *
 * Every database call runs on the activity's own scope and re-checks the screen after it, because a
 * suspend point is where a screen goes away.
 */
class ExportPresetRow(
    private val activity: AppCompatActivity,
    private val panel: ExportPanel,
    /** The panel's `@id/presets` container — this class owns its contents and its visibility. */
    private val container: LinearLayout,
    private val repo: IndexRepository,
    private val host: Host,
) {

    /** The screen's side of the contract — the only things this row knows about the export. */
    interface Host {
        /** What a *Save preset…* would capture, or null when nothing is chosen yet (no candidate
         *  has been described): there is nothing to save, so the action is not drawn. */
        fun currentState(): ExportPresets.State?

        /** The packages the screen's candidate list holds — already cut by installation and by the
         *  current scope, which is what makes it the one question [ExportPresets.listable] asks. */
        fun listedPackages(): Set<String>

        /** Whether a cloud destination could actually be taken right now (installed **and**
         *  connected) — what decides [ExportPresets.Applied.cloudFallback]. */
        fun cloudAvailable(): Boolean

        /** Adopt [state]: the activity writes it under its own latch and re-renders the panel. */
        fun applyPreset(state: ExportPresets.State)

        /** The list changed (a save, a rename, a delete) — re-render the panel around it. */
        fun presetsChanged()
    }

    /** The armed preset's row id, or null for *None*. Saved and restored by the activity with the
     *  format pick, because a preset pick must survive a rebuild behind the picker exactly as the
     *  format does. */
    var selectedId: String? = null

    /** What [reload] last read — every alive preset, before the cut. */
    private var rows: List<ExportPresets.Row> = emptyList()

    /** [rows] cut to what can be applied right now ([recut]). */
    var listed: List<ExportPresets.Listed> = emptyList()
        private set

    /** Re-read the presets and cut them to what this screen lists. Called at every discovery: an
     *  exporter disabled under a standing screen must take its presets with it. */
    suspend fun reload() {
        rows = runCatching { repo.exportPresets() }
            .onFailure { Slog.d(TAG) { "presets unreadable: ${it.javaClass.simpleName}" } }
            .getOrDefault(emptyList())
            .map { (summary, preset) -> ExportPresets.Row(summary.id, summary.name, preset) }
        recut()
    }

    /**
     * Re-cut the last read against what the screen lists *now* — the Scope row's road, which
     * changes the candidate list without asking the extensions (or this row's store) again. The
     * scope is not a preset's question, so flipping it is not a hand change: an armed preset stays
     * armed for as long as its exporter is still listed, and goes to *None* the moment it is not —
     * a tick on a radio nobody can see would be the screen lying about its own state.
     */
    fun recut() {
        listed = ExportPresets.listable(rows, host.listedPackages())
        if (listed.none { it.id == selectedId }) selectedId = null
    }

    /** Rebuilt whole with the rest of the panel, one frame per deliberate act. */
    fun render() {
        container.removeAllViews()
        val rowVisible = ExportPresets.rowVisible(listed)
        val state = host.currentState()
        // GONE only when there is neither a preset to list nor a format to save: an empty container
        // would otherwise leave a gap above the Scope row that means nothing.
        container.visibility = if (rowVisible || state != null) View.VISIBLE else View.GONE
        if (rowVisible) {
            container.addView(panel.caption(activity.getString(R.string.export_preset_caption)))
            val none = selectedId == null
            container.addView(
                panel.choice(activity.getString(R.string.export_preset_none), none) {
                    // Nothing but the tick: None is "no preset is armed", never "start over".
                    if (!none) { selectedId = null; render() }
                }
            )
            for (item in listed) {
                val checked = item.id == selectedId
                container.addView(
                    panel.choice(item.name, checked, onLongPress = { sheet(item) }) {
                        if (!checked) pick(item)
                    }
                )
            }
        }
        if (state != null) container.addView(saveButton())
    }

    /** A hand-written answer anywhere on the panel: whatever is on screen is no longer the preset
     *  the tick claims it is. Called by the activity from every hand write, never from its own
     *  apply. */
    fun onHandChange() {
        if (selectedId == null) return
        selectedId = null
        render()
    }

    // ── Applying ─────────────────────────────────────────────────────────────

    private fun pick(item: ExportPresets.Listed) {
        selectedId = item.id
        val applied = ExportPresets.apply(item.preset, host.cloudAvailable())
        // The activity re-renders the panel — this row included, with the tick already set above.
        host.applyPreset(applied.state)
        // A toast, not a dialog: something happened and the screen is still under the user's hand
        // (the toast-vs-dialog rule). The preset itself is untouched — the account may come back.
        if (applied.cloudFallback) {
            Toast.makeText(activity, R.string.export_preset_cloud_fallback, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * *Save preset…* — `Widget.Notesprout.TextButton`'s look set field by field, because a style
     * cannot be applied to a view constructed in code ([ExportPanel]'s note) and this button's
     * presence is content, like every row above it.
     */
    private fun saveButton(): View = AppCompatButton(activity).apply {
        val d = activity.resources.displayMetrics.density
        text = activity.getString(R.string.export_preset_save_action)
        background = ColorDrawable(Color.TRANSPARENT)
        setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
        textSize = 14f
        isAllCaps = false
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        setOnClickListener { askSaveName(seed = "") }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** The name for a new preset. A refused name **keeps the dialog** — the typing is the user's,
     *  and a rejected character is a correction, not a restart (the library's rule). */
    private fun askSaveName(seed: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.export_preset_save_title,
            confirmRes = R.string.export_preset_save_confirm,
            initial = seed,
            hintRes = R.string.export_preset_name_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            NameRules.validate(name)?.let { problem ->
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            accepting = true
            activity.lifecycleScope.launch {
                // The state is read now rather than at the tap: the dialog crossed no screen, but
                // the read is a suspend hop away from the write and this is the one that counts.
                val state = host.currentState()
                if (state == null || activity.isFinishing || activity.isDestroyed) { dismiss(); return@launch }
                if (repo.nameTaken(null, ObjectType.EXPORT_PRESET, name)) {
                    accepting = false
                    Dialogs.problem(
                        activity, R.string.export_preset_exists_title, R.string.export_preset_exists_body,
                    )
                    return@launch
                }
                val row = repo.createExportPreset(name, ExportPresets.capture(state))
                dismiss()
                if (row == null) { Slog.d(TAG) { "the preset would not encode — nothing written" }; return@launch }
                // Saving arms what was saved: the screen is already showing exactly it.
                selectedId = row.id
                reload()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                host.presetsChanged()
                Toast.makeText(activity, R.string.export_preset_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Rename and delete ────────────────────────────────────────────────────

    /** The long-press sheet: the two things that can be done to a saved preset. */
    private fun sheet(item: ExportPresets.Listed) {
        if (activity.isFinishing || activity.isDestroyed) return
        ActionSheetDialog(activity)
            .title(item.name)
            .addAction(null, activity.getString(R.string.export_preset_rename)) { askRename(item, item.name) }
            .addAction(null, activity.getString(R.string.export_preset_delete)) { confirmDelete(item) }
            .show()
    }

    private fun askRename(item: ExportPresets.Listed, seed: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.export_preset_rename_title,
            confirmRes = R.string.action_rename,
            initial = seed,
            hintRes = R.string.export_preset_name_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            if (name == item.name) { dismiss(); return@show }
            NameRules.validate(name)?.let { problem ->
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            accepting = true
            activity.lifecycleScope.launch {
                if (repo.nameTaken(null, ObjectType.EXPORT_PRESET, name, excludeId = item.id)) {
                    accepting = false
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    Dialogs.problem(
                        activity, R.string.export_preset_exists_title, R.string.export_preset_exists_body,
                    )
                    return@launch
                }
                repo.renameExportPreset(item.id, name)
                dismiss()
                reload()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                host.presetsChanged()
            }
        }
    }

    private fun confirmDelete(item: ExportPresets.Listed) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.export_preset_delete_title, item.name))
                .setMessage(R.string.export_preset_delete_body)
                .setPositiveButton(R.string.export_preset_delete_confirm) { _, _ ->
                    activity.lifecycleScope.launch {
                        repo.deleteExportPreset(item.id)
                        // reload() drops the pick with the row when it was the armed one.
                        reload()
                        if (activity.isFinishing || activity.isDestroyed) return@launch
                        host.presetsChanged()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    private companion object {
        const val TAG = "ExportPresetRow"
    }
}
