package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * **What an export covers** (arc 30 / PE2) — the whole notebook, or one page of it.
 *
 * Scope is a **host-side page-id filter and nothing more**: [ExportRender], [DocumentPdfRender] and
 * [ExportText] each take the `pageIds` this yields and filter the `TYPE_PAGE` rows they already read
 * *before* their existing plan runs, so every exporter — the two installed today and item 6's
 * future image exporter alike — inherits page scope without knowing it exists. `ExportSpec` has no
 * scope field and gains none; no exporter descriptor changes; the seam is untouched.
 *
 * Pure, JVM-tested. The screen owns the value; this owns the rules:
 *
 *  - [pageIds] — null at [Whole] (every render reads "no filter" as "all rows"), the one id at
 *    [Page].
 *  - [pagesInScope] — the filter itself, applied to the DAO's already-ordered rows so display order
 *    is untouched; a page id the rows do not carry (the page vanished between the sheet and the
 *    run) yields an empty list, which each render already refuses as its own *nothing to export*.
 *  - [lists] — the one exporter rule scope adds: a [ExporterContract.SOURCE_SOIL] exporter streams
 *    the whole `.soil`, and a one-page `.soil` would be a new copy-with-filter engine og does not
 *    offer either (decision 4) — so at page scope it is **hidden, never disabled**. Page-bundle and
 *    document exporters serve both scopes.
 *  - [offerable] — whether page scope can be offered at all: only when at least one installed
 *    exporter [lists] at it. A door with nothing behind it is GONE, not a latch that leads to an
 *    empty chooser.
 */
sealed class ExportScope {

    /** Every page — what the library door has always exported. */
    object Whole : ExportScope()

    /** One page, by id — the page-sheet door's default. */
    data class Page(val pageId: String) : ExportScope()

    /**
     * **One calendar page** (arc 31 / HV4) — the third scope, and the only one whose pages are not
     * in a `.soil` at all. Nothing about the filter above applies to it: the pages are drawn by the
     * calendar extension into a bundle ([CalendarRender]), so no notebook is opened, no page row is
     * read, and [pageIds] is null and unused rather than null and meaning *all*.
     *
     * A Day target here still exports **both** halves — that is [CalendarRenderPlan]'s rule, not
     * this one; a scope names the thing, and the plan says how many pages it is.
     */
    data class Calendar(val target: CalendarTarget) : ExportScope()

    /** The filter the renders take: null means *all* — and, at [Calendar], means *nothing reads
     *  this*: no render of a `.soil` runs in calendar mode at all. */
    val pageIds: Set<String>?
        get() = when (this) {
            Whole -> null
            is Page -> setOf(pageId)
            is Calendar -> null
        }

    companion object {

        /** The screen's seed: a page id from the Intent means [Page], none means [Whole]. */
        fun seeded(pageId: String?): ExportScope =
            if (pageId.isNullOrEmpty()) Whole else Page(pageId)

        /** The rows the renders bake — [rows] in the order the DAO gave them, kept whole when
         *  [pageIds] is null and narrowed to the named pages otherwise. */
        fun pagesInScope(rows: List<SoilObjectEntity>, pageIds: Set<String>?): List<SoilObjectEntity> =
            if (pageIds == null) rows else rows.filter { it.id in pageIds }

        /**
         * Whether an exporter of [sourceKind] is listed at [scope] — Soil only at [Whole], and at
         * [Calendar] **only** an exporter that takes a bundle of pages (arc 31 / HV4).
         *
         * The calendar rule is structural rather than a taste: a `.soil` exporter streams a
         * notebook file, and there is no notebook here; a document exporter assembles what was
         * *written*, and a calendar page is ink, not text. Both are hidden, never disabled.
         */
        fun lists(sourceKind: Int, scope: ExportScope): Boolean = when (scope) {
            Whole -> true
            is Page -> sourceKind != ExporterContract.SOURCE_SOIL
            is Calendar -> sourceKind == ExporterContract.SOURCE_PAGES
        }

        /** Whether page scope can be offered over exporters of [sourceKinds] at all. */
        fun offerable(sourceKinds: Collection<Int>): Boolean =
            sourceKinds.any { lists(it, Page("")) }
    }
}
