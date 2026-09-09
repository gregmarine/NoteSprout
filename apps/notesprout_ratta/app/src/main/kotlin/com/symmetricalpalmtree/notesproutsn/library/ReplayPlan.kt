package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry

/**
 * What a cold launch does with the surface stack it found (arc 32 "Resume") — a pure function of
 * the stack's shape, so the library's replay is one `when` over an answer the JVM tests own.
 *
 * Only two shapes exist in SN: a notebook at the bottom with the chain above it handed down as
 * `EXTRA_RESUME_ABOVE`, or an extension screen the library itself opened. The validity gates
 * (alive row · type NOTEBOOK · `.soil` on disk · a trusted service) are the caller's — they are
 * IO, and a failed one drops the entry by the rules in `RESUME_PLAN.md`.
 *
 * **RS1 carries the notebook arm only**; [Notebook.above] is recorded so RS2 can hand it down,
 * and [LibraryLevel] is shaped but not yet acted on.
 */
sealed class ReplayPlan {

    /** A notebook was at the bottom: reopen it, with the surfaces that were above it. */
    data class Notebook(val id: String, val viaLink: Boolean, val above: List<Surface>) : ReplayPlan()

    /** An extension screen was open over the library itself, with nothing beneath it. */
    data class LibraryLevel(val top: Surface, val calendarBeneath: Boolean) : ReplayPlan()

    /** An empty stack, or one whose bottom cannot be stood on. */
    object Nothing : ReplayPlan()

    companion object {
        fun of(stack: List<SurfaceEntry>): ReplayPlan {
            val bottom = stack.firstOrNull() ?: return Nothing
            if (bottom.surface == Surface.NOTEBOOK) {
                val id = bottom.notebookId ?: return Nothing
                // Anything above the notebook is an extension screen; a second NOTEBOOK entry above
                // (a link followed into another notebook mid-switch) cannot be replayed and ends the
                // chain there.
                val above = stack.drop(1).map { it.surface }.takeWhile { it != Surface.NOTEBOOK }
                return Notebook(id, bottom.viaLink, above)
            }
            // Library level: the calendar's pad door is a CALENDAR beneath a SCRATCH_PAD; the top
            // entry is what is reopened, the one beneath is the latch.
            val top = stack.last().surface
            if (top == Surface.NOTEBOOK) return Nothing
            val calendarBeneath = top == Surface.SCRATCH_PAD && stack.size >= 2 &&
                stack[stack.size - 2].surface == Surface.CALENDAR
            return LibraryLevel(top, calendarBeneath)
        }
    }
}
