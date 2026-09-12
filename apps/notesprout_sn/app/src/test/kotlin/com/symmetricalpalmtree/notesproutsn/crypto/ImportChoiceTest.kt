package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.crypto.ImportChoice.Choice
import com.symmetricalpalmtree.notesproutsn.crypto.ImportKeying.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportChoiceTest {
    private val global = "walkpass1"
    private val foreign = Opening.Encrypted("theirpass")

    @Test fun chooserOnlyForAForeignKey() {
        assertFalse(ImportChoice.needsChooser(Opening.Plaintext, global))
        assertFalse(ImportChoice.needsChooser(Opening.Encrypted(global), global))
        assertTrue(ImportChoice.needsChooser(foreign, global))
    }

    @Test fun withoutChooserLandsGlobal() {
        val o = ImportChoice.withoutChooser(global)
        assertEquals(global, o.passphrase)
        assertEquals(KeyScope.GLOBAL, o.scope)
        assertFalse(o.parkForFirstOpen)
    }

    @Test fun keepIsNotebookScope() {
        val o = ImportChoice.decide(foreign, Choice.KEEP, global)
        assertEquals("theirpass", o.passphrase)
        assertEquals(KeyScope.NOTEBOOK, o.scope)
        assertTrue(o.parkForFirstOpen)
    }

    @Test fun keepOfTheGlobalDowngradesToGlobal() {
        val o = ImportChoice.decide(Opening.Encrypted(global), Choice.KEEP, global)
        assertEquals(global, o.passphrase)
        assertEquals(KeyScope.GLOBAL, o.scope)
        assertFalse(o.parkForFirstOpen)
    }

    @Test fun deviceKeyIsArc16sBranch() {
        val o = ImportChoice.decide(foreign, Choice.DEVICE_KEY, global)
        assertEquals(global, o.passphrase)
        assertEquals(KeyScope.GLOBAL, o.scope)
        assertFalse(o.parkForFirstOpen)
    }

    @Test fun newPassphraseIsNotebookScope() {
        val o = ImportChoice.decide(foreign, Choice.NEW_PASSPHRASE, global, newPassphrase = "mynewone")
        assertEquals("mynewone", o.passphrase)
        assertEquals(KeyScope.NOTEBOOK, o.scope)
        assertTrue(o.parkForFirstOpen)
    }

    @Test fun newPassphraseEqualToGlobalDowngrades() {
        val o = ImportChoice.decide(foreign, Choice.NEW_PASSPHRASE, global, newPassphrase = global)
        assertEquals(KeyScope.GLOBAL, o.scope)
        assertFalse(o.parkForFirstOpen)
    }

    @Test fun hostBugsThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportChoice.decide(Opening.Plaintext, Choice.KEEP, global)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportChoice.decide(foreign, Choice.NEW_PASSPHRASE, global, newPassphrase = null)
        }
    }
}
