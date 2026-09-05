package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeChangeTest {
    @Test fun downgradeRule() {
        assertEquals(KeyScope.GLOBAL, ScopeChange.scopeFor("walkpass1", "walkpass1"))
        assertEquals(KeyScope.NOTEBOOK, ScopeChange.scopeFor("notebook1", "walkpass1"))
        // Exact match only — the rule is about the same key, not a similar one.
        assertEquals(KeyScope.NOTEBOOK, ScopeChange.scopeFor("walkpass1 ", "walkpass1"))
    }

    @Test fun sheetRoutesByScope() {
        assertEquals(
            ScopeChange.Route.REDIRECT_TO_ENCRYPTION,
            ScopeChange.route(ScopeChange.Row.CHANGE_PASSPHRASE, KeyScope.GLOBAL),
        )
        assertEquals(
            ScopeChange.Route.NOTEBOOK_PASSPHRASE,
            ScopeChange.route(ScopeChange.Row.CHANGE_PASSPHRASE, KeyScope.NOTEBOOK),
        )
        assertEquals(
            ScopeChange.Route.GLOBAL_TO_NOTEBOOK,
            ScopeChange.route(ScopeChange.Row.CHANGE_SCOPE, KeyScope.GLOBAL),
        )
        assertEquals(
            ScopeChange.Route.NOTEBOOK_TO_GLOBAL,
            ScopeChange.route(ScopeChange.Row.CHANGE_SCOPE, KeyScope.NOTEBOOK),
        )
    }
}
