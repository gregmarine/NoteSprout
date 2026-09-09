package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

/**
 * `SharedPreferences("sn_chrome")` — whether the paper screens' chrome is hidden (arc 33 / F1).
 * Default **shown**.
 *
 * One flag, deliberately global rather than per-notebook or per-screen: "give me the whole page" is
 * a way of working, not a property of a page — [SnapPrefs]' argument exactly. All four paper
 * screens (notebook, sticky editor, scratch pad, calendar) read and write this one value; the two
 * extension screens receive it as a launch extra and echo their final state on the result Intent,
 * and the host writes it here. Device-local: never backed up, never restored.
 */
class ChromePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var hidden: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN, false)
        set(value) { prefs.edit().putBoolean(KEY_HIDDEN, value).apply() }

    private companion object {
        const val FILE = "sn_chrome"
        const val KEY_HIDDEN = "hidden"
    }
}
