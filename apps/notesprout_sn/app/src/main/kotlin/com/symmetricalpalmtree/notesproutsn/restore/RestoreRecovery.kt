package com.symmetricalpalmtree.notesproutsn.restore

/**
 * What a launch does about a restore that was killed mid-commit (arc 27 / L2, D5 / R5) — pure,
 * JVM-tested over every live/aside combination, and executed by [RestoreEngine.recoverInterrupted]
 * **first thing in `BootstrapActivity.boot()`**, before `SnIndex.ensureReady` could read a missing
 * index as a fresh install or `SoilRekey.recoverGarden` could judge rekey leftovers over a
 * half-swapped Garden.
 *
 * The commit (D3 step 8) moves the live library aside by two renames — the index (with its
 * sidecars) first, then `Garden/` — and installs the staged one by three: `Garden/`, the index's
 * sidecar, and the index **last**. **The installed index is the commit marker.** A kill between
 * any two of those five renames leaves a state this plan names by the files that exist, not by
 * which step died, so it is idempotent: running it twice, or over a state a previous run already
 * repaired, does nothing wrong.
 *
 * Three states, decided by the two index files:
 *  - **live index present** → the commit finished (or nothing was ever in flight). Whatever sits
 *    in the aside is the replaced library: delete it whole (decision 5, no undo). Delete the
 *    staging dir too — its files were renamed out; anything left is a partial of nothing.
 *  - **live index absent, aside index present** → the swap did not complete. If **both** a live
 *    `Garden/` and an aside one exist, the live one is the *staged* Garden renamed in at 8(c) —
 *    delete it, or the aside Garden could not rename back. Then rename every aside item back:
 *    Garden, the index's sidecars, and the index **last** (the mirror of how it left, so the
 *    marker is the last thing restored). The old library is whole again.
 *  - **neither index** → nothing of a restore is in flight (a fresh install, or a rekey leftover for
 *    `ensureReady` to judge). Touch nothing but a stray staging dir.
 *
 * An aside holding only a Garden and no index cannot arise from the commit's order (the index is
 * the first thing moved aside and the last thing moved back), so it is left where it is for a
 * person to look at — deleting it would be deleting notebooks nothing can account for.
 */
object RestoreRecovery {

    /** The files a launch can see, by presence. [asideSidecars] are the index sidecar names
     *  (`notesprout.db-wal` etc.) sitting in the aside — they rename back with the index. */
    data class State(
        val liveIndex: Boolean,
        val asideIndex: Boolean,
        val liveGarden: Boolean,
        val asideGarden: Boolean,
        val asideSidecars: List<String> = emptyList(),
    )

    /** One repair, in the order [plan] emits them. */
    sealed class Action {
        /** Delete `restore_replaced/` whole — the commit finished, this was the old library. */
        object DeleteAside : Action() { override fun toString() = "DeleteAside" }

        /** Delete `restore_staging/` whole. Always safe: nothing live ever lives there. */
        object DeleteStaging : Action() { override fun toString() = "DeleteStaging" }

        /** Delete the live `Garden/` — it is the *staged* one, renamed in before the kill, and the
         *  old one is still aside. Only ever emitted when the aside index is present. */
        object DeleteLiveGarden : Action() { override fun toString() = "DeleteLiveGarden" }

        /** Rename `restore_replaced/<name>` back to its live place. */
        data class RenameBack(val name: String) : Action()
    }

    /** The index file's name, and the aside's Garden entry — the two names the plan speaks. */
    const val INDEX_NAME = RestoreManifest.INDEX_NAME
    const val GARDEN_NAME = "Garden"

    fun plan(state: State): List<Action> {
        val out = ArrayList<Action>(6)
        when {
            state.liveIndex -> {
                // Commit finished: the aside is the discarded library, and staging is spent.
                // (Nothing in flight also lands here when there is no aside at all — both deletes
                // are no-ops over absent directories.)
                out += Action.DeleteAside
                out += Action.DeleteStaging
            }
            state.asideIndex -> {
                // Swap did not complete: put the old library back, index last.
                if (state.liveGarden && state.asideGarden) out += Action.DeleteLiveGarden
                if (state.asideGarden) out += Action.RenameBack(GARDEN_NAME)
                for (sidecar in state.asideSidecars) out += Action.RenameBack(sidecar)
                out += Action.RenameBack(INDEX_NAME)
                out += Action.DeleteStaging
            }
            else -> {
                // Nothing of a restore is in flight. A stray aside Garden (impossible by the
                // commit's order) is deliberately left for a person; staging is always spent.
                out += Action.DeleteStaging
            }
        }
        return out
    }
}
