package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collision rule between the double-tap chrome toggle and the single-tap consumers (arc 33). */
class DoubleTapToggleRuleTest {

    @Test fun `two misses toggle`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(false); rule.tapped(false)
        assertTrue(rule.shouldToggle())
    }

    @Test fun `a hit on the first tap refuses`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(true); rule.tapped(false)
        assertFalse(rule.shouldToggle())
    }

    @Test fun `a hit on the second tap refuses`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(false); rule.tapped(true)
        assertFalse(rule.shouldToggle())
    }

    @Test fun `two hits refuse`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(true); rule.tapped(true)
        assertFalse(rule.shouldToggle())
    }

    @Test fun `only one tap recorded refuses`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(false)
        assertFalse(rule.shouldToggle())
    }

    @Test fun `nothing recorded refuses`() {
        assertFalse(DoubleTapToggleRule().shouldToggle())
    }

    @Test fun `a decision consumes the history`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(false); rule.tapped(false)
        assertTrue(rule.shouldToggle())
        assertFalse(rule.shouldToggle())
        rule.tapped(false)
        assertFalse(rule.shouldToggle())   // one fresh tap is not a pair
    }

    @Test fun `an old hit ages out under two later misses`() {
        val rule = DoubleTapToggleRule()
        rule.tapped(true)                  // a sticky opened earlier; the editor came back
        rule.tapped(false); rule.tapped(false)
        assertTrue(rule.shouldToggle())
    }
}
