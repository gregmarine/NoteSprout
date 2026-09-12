package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateFit
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateImport
import com.symmetricalpalmtree.notesproutsn.library.FolderPickerActivity
import com.symmetricalpalmtree.notesproutsn.library.NameDialog
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import com.symmetricalpalmtree.notesproutsn.templates.TemplateLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * **Save as template** (arc 31 / HV2) — the page sheet's last row: the page on the glass becomes a
 * sheet of paper in the template library, ready to be laid under any other page.
 *
 * It is a *library* write, not a notebook one, and that is the whole shape of it: nothing is
 * written to the `.soil`, no undo entry is recorded, no recents row is touched, the cover is not
 * re-taken and the page itself does not change by a pixel. What crosses is one picture and one
 * name.
 *
 * The order, and each step's reason:
 *
 *  1. **Raster**, inside `runPageOp` and after a `drain()`, so the picture is the page as the last
 *     stroke left it rather than the page one commit ago. [PageRaster] is the export bake's own
 *     recipe — a template made from a page must be the same picture the page would export as.
 *  2. **The cap first** ([TemplateImport.overCap]) — the import's rule, and the import's reason:
 *     asking for a name and *then* refusing the picture would waste the only decision the user
 *     makes.
 *  3. **Name**, then **folder**. Two questions, in the order the library asks them, with the name
 *     seeded from the page itself ([TemplateSeedName]).
 *  4. **The refusals re-ask the name, keeping the folder** — a reserved name or one already taken
 *     in the folder just chosen sends the user back one step, never back to the top of the tree.
 *     Walking the same folders twice to fix a word is the kind of small cruelty an e-ink screen
 *     makes large.
 *
 * **The bytes are fields, never instance state** ([pendingBytes] / [pendingName], latched and
 * cleared at the top of the result callback — the S2 rule). A page's worth of pixels has no
 * business in a `Bundle`, and a screen Android rebuilt behind the folder picker has no page to
 * raster any more: it says so and stops, exactly as the export secret does. That is also why the
 * picker is the *last* thing asked — everything before it is one uninterrupted showing.
 *
 * Fit is pinned to [TemplateFit.FIT] and no fit sheet is offered: the picture *is* a page of this
 * library, authored at a page's size and aspect, so the one mode that cannot crop or distort it is
 * the only honest answer. Re-fitting it later is the browser's own long-press row.
 */
class SaveAsTemplateFlow(
    private val activity: AppCompatActivity,
    /** Lazy on purpose: an [IndexRepository] reads `SnIndex` in its constructor, and this flow is
     *  built at field-init — before the host's `IndexGuard` has had its say. */
    private val repo: () -> IndexRepository,
    private val session: () -> NotebookSession,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    /** Serialise the raster with every other page mutation (the host's `runPageOp`). */
    private val runPageOp: ((suspend () -> Unit) -> Unit),
) {

    /** The folder the picture is being saved into — boxed so that "the root" (a null id) is never
     *  confused with "nothing has been chosen yet". */
    private class Picked(val folderId: String?)

    /** True while the raster is on the page-op queue — a second tap on a sheet row that is already
     *  dismissing costs nothing. Set and cleared **inside** the queued block, so it can never be
     *  left standing by a block that was dropped. */
    private var rastering = false

    /** The page's pixels, held only across the folder picker. Cleared at the top of the result. */
    private var pendingBytes: ByteArray? = null
    private var pendingName: String? = null

    /**
     * The folder pick. Registered here, at construction, because a launcher must exist before the
     * host reaches STARTED — the same rule that puts the template picker's launcher at field-init
     * on [NotebookActivity].
     *
     * **The latch is the first thing in the callback** (the S2 rule): result callbacks run before
     * `onResume`, so the pending state is read and cleared before anything can look at it twice.
     */
    private val folderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val bytes = pendingBytes
        val name = pendingName
        pendingBytes = null
        pendingName = null
        when {
            result.resultCode != Activity.RESULT_OK -> Slog.d(TAG) { "folder pick cancelled — nothing written" }
            // The screen was rebuilt behind the picker: the pixels died with the old instance and
            // there is nothing honest left to write. Say so rather than saving blank paper.
            bytes == null || name == null -> Dialogs.problem(
                activity, R.string.template_save_interrupted_title, R.string.template_save_interrupted_body,
            )
            else -> place(
                bytes, name,
                Picked(result.data?.getStringExtra(FolderPickerActivity.EXTRA_PICKED_FOLDER_ID)),
            )
        }
    }

    /**
     * Whether [pageId] can be rastered at all — the row is **absent** when it cannot (GONE, never
     * disabled). A page row with no usable size is the same damaged data the export bake refuses;
     * offering a row that could only ever fail is worse than one row fewer.
     */
    fun canRaster(pageId: String): Boolean {
        if (!alive() || pageId.isEmpty()) return false
        val page = runCatching { session().pages.firstOrNull { it.id == pageId } }.getOrNull() ?: return false
        return page.width > 0 && page.height > 0
    }

    /** The row's tap. Everything after this is one showing until the folder picker. */
    fun start(pageId: String) {
        if (!alive() || rastering || pendingBytes != null || pageId.isEmpty()) return
        runPageOp {
            rastering = true
            try {
                raster(pageId)
            } finally {
                rastering = false
            }
        }
    }

    /** The picture and the seeded name, made together because the content read answers both. */
    private class Baked(val bytes: ByteArray, val seed: String)

    private suspend fun raster(pageId: String) {
        val session = session()
        // Downstream of every queued write: a stroke still on the writer would otherwise be on the
        // glass and missing from the paper.
        session.store.drain()
        // Gone under the sheet (a delete, a flip that took the page away) — nothing to say, nothing
        // was promised.
        val page = session.pages.firstOrNull { it.id == pageId } ?: return
        val pageNumber = session.pages.indexOf(page) + 1
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val scaledDensity = metrics.scaledDensity
        val baked = try {
            withContext(Dispatchers.IO) {
                val dao = session.db.dao()
                // Built where it draws, never shared with the engine's renderers (the Paints rule).
                val paints = PagePreview.Paints.of(
                    scaledDensity,
                    runCatching { AppCompatResources.getDrawable(activity, R.drawable.ic_sticker_2)?.mutate() }
                        .getOrNull(),
                )
                var template: Bitmap? = null
                try {
                    template = PageRaster.decodeTemplate(dao, page.templateId)
                    val content = PageReads.content(dao, page.id)
                    Baked(
                        PageRaster.toWebp(page.width, page.height, template, content, density, paints),
                        TemplateSeedName.of(PageLabels.titleOf(content), pageNumber),
                    )
                } finally {
                    template?.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only — a render failure's message ([IOException] from an allocation that
            // would not come, a read that threw) can carry a path.
            Log.w(TAG, "page raster failed: ${e.javaClass.simpleName}")
            failed()
            return
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "page raster ran out of memory")
            failed()
            return
        }
        if (!alive() || activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "rastered a ${page.width}x${page.height} page into ${baked.bytes.size} bytes" }
        // The import's refusal, verbatim: the cap is about what the library can hold, and it reads
        // the same whether the picture came from a file or from a page.
        if (TemplateImport.overCap(baked.bytes.size)) {
            Dialogs.problem(
                activity, R.string.template_import_too_big_title,
                activity.getString(
                    R.string.template_import_too_big_body,
                    TemplateImport.megabytes(baked.bytes.size),
                    TemplateImport.megabytes(TemplateImport.MAX_BLOB_BYTES),
                ),
            )
            return
        }
        askName(baked.bytes, baked.seed, folder = null)
    }

    private fun failed() {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, R.string.template_save_failed_title, R.string.template_save_failed_body)
    }

    /**
     * The name. [folder] null means the folder has not been chosen yet (accepting launches the
     * picker); non-null means it has, and this is the **re-ask** after a refusal — the same folder
     * is kept and the write is attempted again there.
     */
    private fun askName(bytes: ByteArray, seed: String, folder: Picked?) {
        if (!alive() || activity.isFinishing || activity.isDestroyed) return
        var accepting = false
        NameDialog.show(
            activity,
            titleRes = R.string.template_save_name_title,
            confirmRes = R.string.template_save_confirm,
            initial = seed,
            hintRes = R.string.template_import_name_hint,
        ) { name, dismiss ->
            if (accepting) return@show
            NameRules.validate(name)?.let { problem ->
                // The dialog stays: the typing is the user's, and a rejected character is a
                // correction, not a restart.
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            accepting = true
            dismiss()
            if (folder == null) {
                pendingBytes = bytes
                pendingName = name
                launchFolder()
            } else {
                place(bytes, name, folder)
            }
        }
    }

    /** The library's folder tree, in template mode, wearing this errand's words. */
    private fun launchFolder() {
        try {
            folderLauncher.launch(
                FolderPickerActivity.pickIntent(
                    activity,
                    browseFolderType = ObjectType.TEMPLATE_FOLDER,
                    rootLabel = activity.getString(R.string.templates_title),
                    verb = FolderPickerActivity.PickVerb.SAVE_TEMPLATE,
                )
            )
        } catch (e: Exception) {
            pendingBytes = null
            pendingName = null
            Log.w(TAG, "folder picker would not open: ${e.javaClass.simpleName}")
            failed()
        }
    }

    /**
     * The two questions the folder can answer — the reserved root name and a name already taken
     * here — then the row. Both refusals re-ask the name **in this folder**, seeded with the name
     * that was refused, so the correction is one word rather than another walk: the tree is walked
     * once per save, whatever the words cost.
     */
    private fun place(bytes: ByteArray, name: String, folder: Picked) {
        activity.lifecycleScope.launch {
            if (TemplateLibrary.isReservedName(folder.folderId, name)) {
                // The name dialog is raised FIRST and the refusal put on top of it: that is exactly
                // what the import looks like when it refuses a name (its dialog never closed), and
                // it means dismissing the sentence leaves the user in the field they must edit —
                // rather than in a dialog that then vanishes to reveal an explanation they have
                // already acted on.
                askName(bytes, name, folder)
                Dialogs.problem(
                    activity, R.string.name_problem_title,
                    activity.getString(R.string.template_name_reserved, TemplateLibrary.RESERVED_ROOT_NAME),
                )
                return@launch
            }
            if (repo().nameTaken(folder.folderId, ObjectType.TEMPLATE, name)) {
                askName(bytes, name, folder)
                Dialogs.problem(
                    activity, R.string.name_problem_title,
                    activity.getString(R.string.rename_duplicate_template, name),
                )
                return@launch
            }
            repo().createTemplate(
                name = name,
                parentId = folder.folderId,
                kind = TemplateLibrary.KIND_IMAGE,
                fit = TemplateFit.FIT,
                image = bytes,
            )
            Slog.d(TAG) { "saved a page as paper: ${bytes.size} bytes" }
            // A toast, not a dialog: something happened, it is in the library, and the notebook is
            // still under the user's pen (the toast-vs-dialog rule).
            Toast.makeText(activity, R.string.template_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "SaveAsTemplateFlow"
    }
}
