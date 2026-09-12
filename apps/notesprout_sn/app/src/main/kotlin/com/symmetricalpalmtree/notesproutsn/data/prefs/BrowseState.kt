package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

/**
 * Library browse modes: the folder tree ([NORMAL]) or one of the flat shelves that cut across it.
 * The mode persists, so the shelf the user left is the shelf they come back to — **except
 * [SEARCH]**, which never does: a query is a moment, not a view, and it lives in memory only
 * (prefs hold ids and enum names, never a display name — and a query is one).
 */
enum class BrowseMode { NORMAL, PINNED, RECENTS, SEARCH }

/**
 * `SharedPreferences("sn_view_state")` — where the library was when the user left it, so a cold
 * launch reopens the same shelf instead of dumping them at the root.
 *
 * **Ids and enum names only, never a display name.** [folderId] is validated against the index on
 * restore (a folder deleted since falls back to root); nothing here is trusted as still existing.
 *
 * The screens that were open live beside this in the same file as [SurfaceStack] (arc 32 — the
 * pre-arc `lastOpenNotebookId` / `lastOpenViaLink` keys were retired into it).
 */
class BrowseState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last browse folder id; null = root. */
    var folderId: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) { prefs.edit().putString(KEY_FOLDER, value).apply() }

    /**
     * The shelf to come back to. [BrowseMode.SEARCH] is refused in **both** directions — writing it
     * is a no-op and reading one back (a value from a build that did persist it, or a hand-edited
     * file) answers [BrowseMode.NORMAL]: a cold launch onto a search shelf with no query would be a
     * screen saying "type to search" where the library should be.
     */
    var mode: BrowseMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { BrowseMode.valueOf(it) }.getOrNull() }
            ?.takeIf { it != BrowseMode.SEARCH }
            ?: BrowseMode.NORMAL
        set(value) {
            if (value == BrowseMode.SEARCH) return
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    private companion object {
        const val FILE = "sn_view_state"
        const val KEY_FOLDER = "folderId"
        const val KEY_MODE = "mode"
    }
}
