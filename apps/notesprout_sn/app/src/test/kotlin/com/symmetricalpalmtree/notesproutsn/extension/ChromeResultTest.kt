package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one rule: present → its value, absent → null (the host writes nothing). */
class ChromeResultTest {

    @Test
    fun presentTrueIsHidden() {
        assertEquals(true, ChromeResult.decode(present = true, value = true))
    }

    @Test
    fun presentFalseIsShown() {
        assertEquals(false, ChromeResult.decode(present = true, value = false))
    }

    @Test
    fun absentIsNullWhateverTheValueSlotSays() {
        // A killed process (null data) and an extension that predates the extra both land here;
        // the value slot is what `getBooleanExtra`'s default would be, and it must not be trusted.
        assertNull(ChromeResult.decode(present = false, value = false))
        assertNull(ChromeResult.decode(present = false, value = true))
    }

    @Test
    fun noIntentIsNull() {
        // `read(null)` under the JVM stubs: no Intent at all is the killed-process case.
        assertNull(ChromeResult.read(null))
    }
}
