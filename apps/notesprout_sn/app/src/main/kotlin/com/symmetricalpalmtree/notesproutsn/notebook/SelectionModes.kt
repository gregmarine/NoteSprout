package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * What the lasso caught, decided (D5) — the `when` that used to sit inside
 * `NotebookActivity.showSelectionToolbar`, pulled out here so the table the bar renders is a table a
 * test can read. [TagSelection] is its neighbour: that one says what the Tag button does with a
 * mode, this one says which mode a selection *is*.
 *
 * The rule, in order:
 *  1. **exactly one** content object and no ink → its own kind's lone mode ([SelectionMode.HEADING],
 *     [SelectionMode.TEXT], [SelectionMode.SHAPE], [SelectionMode.LINK]); a kind with no lone mode
 *     yet falls through;
 *  2. a link anywhere else in the set → [SelectionMode.MIXED_WITH_LINK] (which is what keeps the
 *     arc-6 no-nesting rule: Link is offered on every link-free selection and on none that already
 *     holds one);
 *  3. ink alone → [SelectionMode.STROKES];
 *  4. anything else → [SelectionMode.MIXED].
 *
 * A lone **shape** got its own mode at H4, whose one addition to the base row is **Transform**;
 * a lone **sticky** got [SelectionMode.STICKY] at H5 — the base row (Snap / Copy / Cut / Delete
 * plus a link-free Link) with no button of its own, because its verb is a finger tap on the icon,
 * not a bar button (D5).
 *
 * The five predicates are read off the screen's working copies rather than trusted from the
 * engine's id set — the same rule the old `when` followed.
 */
object SelectionModes {

    fun classify(
        strokeCount: Int,
        contentIds: Collection<String>,
        isHeading: (String) -> Boolean,
        isLink: (String) -> Boolean,
        isText: (String) -> Boolean,
        isShape: (String) -> Boolean,
        isSticky: (String) -> Boolean = { false },
    ): SelectionMode {
        val lone = if (strokeCount == 0 && contentIds.size == 1) contentIds.first() else null
        val hasLink = contentIds.any(isLink)
        return when {
            lone != null && isHeading(lone) -> SelectionMode.HEADING
            lone != null && isText(lone) -> SelectionMode.TEXT
            lone != null && isShape(lone) -> SelectionMode.SHAPE
            lone != null && isSticky(lone) -> SelectionMode.STICKY
            lone != null && hasLink -> SelectionMode.LINK
            hasLink -> SelectionMode.MIXED_WITH_LINK
            contentIds.isEmpty() && strokeCount > 0 -> SelectionMode.STROKES
            else -> SelectionMode.MIXED
        }
    }
}
