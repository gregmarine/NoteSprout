package com.symmetricalpalmtree.notesproutsn.restore

import android.os.Process
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.BuildConfig

/**
 * Arc 27 / L5 — the one seam the failure-injection pass needs inside the engine. A debug build
 * arms **one** fault from the debug menu; the next restore's commit consults [at] at each seam
 * of D3 step 8 and, when the armed fault names that seam, does what a real failure there would
 * do (dies, throws, plants an obstruction, tears the staged set, or calls into a store). The
 * fault is consumed the moment it fires, so a relaunch never re-fires it.
 *
 * In a release build [at] is a constant `false` and [arm] is a no-op — the engine's calls compile
 * to nothing that can fire. Nothing here is reachable from a release entry point.
 */
object RestoreFaults {

    /** Where a fault fires. The letters are D3 step 8's. */
    enum class Fault(val label: String) {
        /** `Process.killProcess` after (a): the live index is aside, the live Garden still live. */
        KILL_AFTER_A("Kill after 8(a) — index aside"),
        /** After (b): index + Garden aside, nothing installed. */
        KILL_AFTER_B("Kill after 8(b) — Garden aside"),
        /** After (c): the staged Garden is live, the index is still aside. */
        KILL_AFTER_C("Kill after 8(c) — new Garden in"),
        /** After (d): sidecars beside the live name, the index still aside. */
        KILL_AFTER_D("Kill after 8(d) — index WAL in"),
        /** After (e), before step 9: the marker is live, the key state is the OLD library's. */
        KILL_AFTER_E("Kill after 8(e) — index in, before key state"),
        /** Throw after (e), before step 9 — the in-process `Interrupted` ending. */
        THROW_BEFORE_KEY_STATE("Throw before step 9 — the Interrupted ending"),
        /** Plant a plain file at the live `Garden/` name after (b), so (c) fails — the rollback. */
        PLANT_AT_C("Plant a file at Garden/ — 8(c) fails, rollback"),
        /** Delete the first staged `.soil` at the top of the commit — the torn-set refusal. */
        TEAR_STAGING("Tear staging — delete a staged .soil before the commit"),
        /** After (b), call `ExtensionStores.open` in-process — must meet `SoilLockedException` (R2). */
        STORE_CALL_MID_SWAP("Store call mid-swap — must be refused (R2)"),
    }

    /** Where the engine asks. One per seam that has a fault. */
    enum class Seam { COMMIT_TOP, AFTER_A, AFTER_B, AFTER_C, AFTER_D, AFTER_E }

    @Volatile
    private var armed: Fault? = null

    /** What is armed now (the debug menu shows it). */
    val current: Fault? get() = armed

    /** What the last fired fault reported (the debug menu shows it after the relaunch is not
     *  possible — so the screen's outcome is the report; this is for the in-process kinds). */
    @Volatile
    var lastReport: String? = null
        private set

    /** Arm [fault] for the next commit, or clear with null. No-op in release. */
    fun arm(fault: Fault?) {
        if (!BuildConfig.DEBUG) return
        armed = fault
        lastReport = null
        Log.w(TAG, "armed: ${fault?.name ?: "nothing"}")
    }

    /**
     * The engine's question at [seam]: consumes and fires the armed fault when it belongs here.
     * Returns the fault that fired (the engine acts on the plant / tear / store kinds itself,
     * through [Hooks]); a kill never returns; a throw throws.
     */
    fun at(seam: Seam, hooks: Hooks): Fault? {
        if (!BuildConfig.DEBUG) return null
        val fault = armed ?: return null
        val fires = when (fault) {
            Fault.KILL_AFTER_A -> seam == Seam.AFTER_A
            Fault.KILL_AFTER_B -> seam == Seam.AFTER_B
            Fault.KILL_AFTER_C -> seam == Seam.AFTER_C
            Fault.KILL_AFTER_D -> seam == Seam.AFTER_D
            Fault.KILL_AFTER_E, Fault.THROW_BEFORE_KEY_STATE -> seam == Seam.AFTER_E
            Fault.PLANT_AT_C, Fault.STORE_CALL_MID_SWAP -> seam == Seam.AFTER_B
            Fault.TEAR_STAGING -> seam == Seam.COMMIT_TOP
        }
        if (!fires) return null
        armed = null
        Log.w(TAG, "firing ${fault.name} at $seam")
        when (fault) {
            Fault.KILL_AFTER_A, Fault.KILL_AFTER_B, Fault.KILL_AFTER_C, Fault.KILL_AFTER_D, Fault.KILL_AFTER_E -> {
                // SIGKILL to self: no finally blocks, no flush — the kill `am force-stop` cannot time.
                Process.killProcess(Process.myPid())
            }
            Fault.THROW_BEFORE_KEY_STATE -> throw InjectedFailure("injected throw before step 9")
            Fault.PLANT_AT_C -> lastReport = hooks.plantAtGarden()
            Fault.TEAR_STAGING -> lastReport = hooks.tearStaging()
            Fault.STORE_CALL_MID_SWAP -> lastReport = hooks.storeCall()
        }
        return fault
    }

    /** What the engine lends the plant / tear / store kinds — each returns a one-line report. */
    interface Hooks {
        fun plantAtGarden(): String
        fun tearStaging(): String
        fun storeCall(): String
    }

    /** The injected exception — its simple name is what the ending dialog shows. */
    class InjectedFailure(message: String) : RuntimeException(message)

    private const val TAG = "RestoreFaults"
}
