package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.export.ExportNaming
import com.symmetricalpalmtree.notesproutsn.library.NameRules

/**
 * What **Save as template** offers as the new paper's name (arc 31 / HV2) — a seed, never a
 * decision: the name dialog opens with it selected, and [com.symmetricalpalmtree.notesproutsn.library.NameRules]
 * has the last word on whatever the user leaves there.
 *
 * The rule is the page-export filename's, deliberately ([ExportNaming.pageStem]): the page's
 * topmost heading by the Contents rule, else its 1-based position. A page the user has already
 * titled is called that in both places, and a page they have not is "page 7" in both — one page,
 * one name, wherever the app has to write it down.
 *
 * Pure and JVM-tested, because it is a rule rather than a screen.
 */
object TemplateSeedName {

    /**
     * A heading's text — reduced to the name charset (every character outside
     * [NameRules.CHARSET] dropped, runs of spaces collapsed, trimmed, capped at
     * [ExportNaming.MAX_TITLE_CHARS]) — else "page N" (N ≥ 1); N < 1 → "page". Never empty, and
     * never a seed [NameRules.validate] would refuse: a heading that reads "Q3: plan?" seeds
     * "Q3 plan", and one that strips to nothing seeds the page number instead.
     */
    fun of(pageTitle: String?, pageNumber: Int): String {
        val title = pageTitle
            ?.let { OUTSIDE_CHARSET.replace(it, "") }
            ?.let { SPACES.replace(it, " ") }
            ?.trim()?.take(ExportNaming.MAX_TITLE_CHARS)?.trim()
        if (!title.isNullOrEmpty() && NameRules.validate(title) == null) return title
        // A page that could not be placed in the list still needs a word: "page 0" would be a lie
        // and an empty name is not a name at all.
        return if (pageNumber >= 1) "page $pageNumber" else "page"
    }

    /** The complement of [NameRules.CHARSET]'s class — the one place the charset is written is
     *  still that regex; this only inverts it. */
    private val OUTSIDE_CHARSET = Regex("[^a-zA-Z0-9_\\-. ]")
    private val SPACES = Regex(" {2,}")
}
