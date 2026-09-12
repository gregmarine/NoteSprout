package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 30 / PE2: scope is a host-side page filter, and the one exporter rule it adds is Soil's. */
class ExportScopeTest {

    private var next = 0L

    private fun page(id: String, order: Int) = SoilObjectEntity(
        id = id, parentId = "nb", type = SoilSchema.TYPE_PAGE, order = order,
        createdAt = ++next, updatedAt = next, width = 1404f, height = 1872f,
    )

    private val rows = listOf(page("p1", 0), page("p2", 1), page("p3", 2))

    @Test
    fun seedsPageFromAnIdAndWholeFromNone() {
        assertEquals(ExportScope.Whole, ExportScope.seeded(null))
        assertEquals(ExportScope.Whole, ExportScope.seeded(""))
        assertEquals(ExportScope.Page("p2"), ExportScope.seeded("p2"))
    }

    @Test
    fun wholeHasNoFilterAndPageHasOne() {
        assertNull(ExportScope.Whole.pageIds)
        assertEquals(setOf("p2"), ExportScope.Page("p2").pageIds)
    }

    @Test
    fun nullFilterKeepsEveryRowInOrder() {
        assertEquals(rows, ExportScope.pagesInScope(rows, null).map { it.row })
        // Numbered from 1 in the DAO's order — what the notebook calls each page.
        assertEquals(listOf(1, 2, 3), ExportScope.pagesInScope(rows, null).map { it.number })
    }

    @Test
    fun filterNarrowsToTheNamedPagesInDaoOrder() {
        assertEquals(listOf("p2"), ExportScope.pagesInScope(rows, setOf("p2")).map { it.row.id })
        // Order is the DAO's, never the set's.
        assertEquals(listOf("p1", "p3"), ExportScope.pagesInScope(rows, setOf("p3", "p1")).map { it.row.id })
        // And each kept page still knows the number the NOTEBOOK gives it (arc 34 / L15) — the
        // narrowed list renumbers from 1 only where the bundle is concerned.
        assertEquals(listOf(2), ExportScope.pagesInScope(rows, setOf("p2")).map { it.number })
        assertEquals(listOf(1, 3), ExportScope.pagesInScope(rows, setOf("p3", "p1")).map { it.number })
    }

    @Test
    fun aVanishedPageYieldsNothing() {
        // The page went between the sheet and the run: each render refuses its own EMPTY.
        assertTrue(ExportScope.pagesInScope(rows, setOf("gone")).isEmpty())
    }

    @Test
    fun soilIsListedOnlyAtWhole() {
        assertTrue(ExportScope.lists(ExporterContract.SOURCE_SOIL, ExportScope.Whole))
        assertFalse(ExportScope.lists(ExporterContract.SOURCE_SOIL, ExportScope.Page("p1")))
    }

    @Test
    fun pageAndDocumentExportersServeBothScopes() {
        for (kind in listOf(ExporterContract.SOURCE_PAGES, ExporterContract.SOURCE_DOCUMENT)) {
            assertTrue(ExportScope.lists(kind, ExportScope.Whole))
            assertTrue(ExportScope.lists(kind, ExportScope.Page("p1")))
        }
    }

    @Test
    fun pageScopeIsOfferedOnlyWhenSomethingServesIt() {
        assertFalse(ExportScope.offerable(emptyList()))
        assertFalse(ExportScope.offerable(listOf(ExporterContract.SOURCE_SOIL)))
        assertTrue(ExportScope.offerable(listOf(ExporterContract.SOURCE_SOIL, ExporterContract.SOURCE_PAGES)))
        assertTrue(ExportScope.offerable(listOf(ExporterContract.SOURCE_DOCUMENT)))
    }

    // ── The calendar scope (arc 31 / HV4) ────────────────────────────────────

    private val calendar =
        ExportScope.Calendar(CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0))

    @Test
    fun aCalendarScopeHasNoPageFilterBecauseNothingReadsOne() {
        // Null here does NOT mean "all pages of a notebook" — no notebook is opened at all.
        assertNull(calendar.pageIds)
    }

    @Test
    fun onlyAPageBundleExporterIsListedAtACalendar() {
        assertTrue(ExportScope.lists(ExporterContract.SOURCE_PAGES, calendar))
        assertFalse(ExportScope.lists(ExporterContract.SOURCE_SOIL, calendar))
        assertFalse(ExportScope.lists(ExporterContract.SOURCE_DOCUMENT, calendar))
    }

    @Test
    fun theCalendarScopeCarriesItsTarget() {
        val day = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-08", CalendarTarget.HALF_PM)
        assertEquals(day, ExportScope.Calendar(day).target)
        // Equality is the target's, which is what lets the screen compare scopes at all.
        assertEquals(ExportScope.Calendar(day), ExportScope.Calendar(CalendarTarget(2, "2026-09-08", 1)))
    }

    @Test
    fun pageScopeOfferabilityIsUntouchedByTheCalendarRule() {
        // `offerable` asks about the page-sheet door only; the calendar door has no Scope row.
        assertTrue(ExportScope.offerable(listOf(ExporterContract.SOURCE_PAGES)))
        assertFalse(ExportScope.offerable(listOf(ExporterContract.SOURCE_SOIL)))
    }
}
