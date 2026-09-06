package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * What the lasso caught, decided (D5) — the `when` that used to sit inside
 * `NotebookActivity.showSelectionToolbar`, pulled out here so the table the bar renders is a table a
 * test can read. [TagSelection] is its neighbour: that one says what the Tag button does with a
 * mode, this one says which mode a selection *is*.
 *
 * The rule, in order:
 *  1. **exactly one** content object and no ink → its own kind's lone mode ([SelectionMode.HEADING],
 *     [SelectionMode.TEXT], [SelectionMode.LINK]); a kind with no lone mode yet falls through;
 *  2. a link anywhere else in the set → [SelectionMode.MIXED_WITH_LINK] (which is what keeps the
 *     arc-6 no-nesting rule: Link is offered on every link-free selection and on none that already
 *     holds one);
 *  3. ink alone → [SelectionMode.STROKES];
 *  4. anything else → [SelectionMode.MIXED].
 *
 * A lone **shape** or **sticky** is [SelectionMode.MIXED] until H4 and H5 give it something of its
 * own to offer — deliberately, and pinned by a test: MIXED's row is Snap / Copy / Cut / Delete plus
 * a link-free Link, which is the honest set for a kind whose own verbs do not exist yet.
 *
 * The three predicates are read off the screen's working copies rather than trusted from the
 * engine's id set — the same rule the old `when` followed.
 */
object SelectionModes {

    fun classify(
        strokeCount: Int,
        contentIds: Collection<String>,
        isHeading: (String) -> Boolean,
        isLink: (String) -> Boolean,
        isText: (String) -> Boolean,
    ): SelectionMode {
        val lone = if (strokeCount == 0 && contentIds.size == 1) contentIds.first() else null
        val hasLink = contentIds.any(isLink)
        return when {
            lone != null && isHeading(lone) -> SelectionMode.HEADING
            lone != null && isText(lone) -> SelectionMode.TEXT
            lone != null && hasLink -> SelectionMode.LINK
            hasLink -> SelectionMode.MIXED_WITH_LINK
            contentIds.isEmpty() && strokeCount > 0 -> SelectionMode.STROKES
            else -> SelectionMode.MIXED
        }
    }
}
