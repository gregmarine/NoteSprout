package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.crypto.NotebookRecovery.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookRecoveryTest {

    @Test fun offeredOnlyForAKeyFailureAndOnlyOnce() {
        assertTrue(Plan.shouldOffer(keyed = true, isKeyFailure = true, attempted = false))
        assertFalse(Plan.shouldOffer(keyed = true, isKeyFailure = true, attempted = true))
        assertFalse(Plan.shouldOffer(keyed = true, isKeyFailure = false, attempted = false))
        assertFalse(Plan.shouldOffer(keyed = false, isKeyFailure = true, attempted = false))
    }

    @Test fun silentCandidatesAreGlobalThenMarker() {
        assertEquals(listOf("g", "n"), Plan.silentCandidates("g", "n"))
        assertEquals(listOf("g"), Plan.silentCandidates("g", null))
        assertEquals(listOf("g"), Plan.silentCandidates("g", "g"))
        assertEquals(listOf("n"), Plan.silentCandidates(null, "n"))
        assertEquals(emptyList<String>(), Plan.silentCandidates(null, null))
    }

    @Test fun globalOnAForeignKeyIsRepaired() {
        assertEquals(Plan.Action.REPAIR_TO_GLOBAL, Plan.decide(KeyScope.GLOBAL, keyIsGlobal = false, hasGlobal = true))
    }

    @Test fun globalOnTheGlobalJustReopens() {
        // The cached raw key was stale; it has been dropped, so the ordinary open now works.
        assertEquals(Plan.Action.REOPEN, Plan.decide(KeyScope.GLOBAL, keyIsGlobal = true, hasGlobal = true))
    }

    @Test fun globalWithNoGlobalAtAllCannotRepair() {
        assertEquals(Plan.Action.REOPEN, Plan.decide(KeyScope.GLOBAL, keyIsGlobal = false, hasGlobal = false))
    }

    @Test fun notebookScopeParksWhateverWorked() {
        assertEquals(Plan.Action.PARK_FOR_REOPEN, Plan.decide(KeyScope.NOTEBOOK, keyIsGlobal = false, hasGlobal = true))
        // A quarantined notebook opened by the global: still parked — the scope row says NOTEBOOK,
        // and the sheet's NOTEBOOK → GLOBAL row is the manual way back.
        assertEquals(Plan.Action.PARK_FOR_REOPEN, Plan.decide(KeyScope.NOTEBOOK, keyIsGlobal = true, hasGlobal = true))
    }
}
