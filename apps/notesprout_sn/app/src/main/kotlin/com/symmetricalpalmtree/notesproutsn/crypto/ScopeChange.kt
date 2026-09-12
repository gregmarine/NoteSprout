package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soilFile

/**
 * **The scope-change core** (arc 26 / U5, D4) — the three steps behind every door that moves a
 * notebook between this device's key and its own passphrase, or replaces its own passphrase.
 * U4's debug item was this exact sequence; the library sheet's two rows and the New Notebook
 * screen now call it here and the debug item is gone.
 *
 * Both directions and the passphrase change are the same three steps in the same order, and the
 * order is the safety: **re-key the file first** ([SoilRekey.rekeyInPlace] — atomic, the original
 * untouched on any failure), **then record the scope in the index**
 * ([IndexRepository.setEncryptionState] — cover nulled for `NOTEBOOK`, both backup stamps cleared,
 * the process unlock forgotten, `updatedAt` never bumped), **then park the new passphrase** for the
 * very next open ([PassphraseCache.storeOnce], taken only by the notebook screen's open). An index
 * that disagrees with its file is the one state worth avoiding, and a re-key that failed never
 * reaches step two.
 *
 * **The downgrade rule** (decision 3, og's): a notebook whose "own" passphrase is the device's
 * global one *is* a `GLOBAL` notebook — [scopeFor] answers `GLOBAL` for it, the file is re-keyed
 * to the same key (a no-op copy, which is still the only path that restamps the meta honestly)
 * and nothing is parked, because a `GLOBAL` open never asks. The New Notebook screen and the
 * import chooser apply the same rule through the same function; the sheet's set-passphrase dialog
 * refuses the global outright via [PassphraseRules] (`current = global`) so it is never reached
 * there, but the rule holds even if it were.
 *
 * Every function refuses a notebook that is open in this process ([SoilOpenFiles]): one file, one
 * connection, and a re-key under a live writer is not a thing to find out about later. From the
 * library a notebook never is, so [Refused.OPEN] is a belt over braces, not a user state.
 *
 * Passphrases arrive as parameters and leave only into [SoilRekey] and [PassphraseCache]; none is
 * logged, none is in a message.
 */
object ScopeChange {

    /** Which key a notebook ends up under when the person types [typed] as "its own" passphrase
     *  while the device's global is [global] — og's downgrade rule, pure. */
    fun scopeFor(typed: String, global: String): KeyScope =
        if (typed == global) KeyScope.GLOBAL else KeyScope.NOTEBOOK

    /** What a sheet row does for a notebook of a given scope — the pure half of the two rows. */
    enum class Row { CHANGE_PASSPHRASE, CHANGE_SCOPE }

    enum class Route {
        /** "Global notebooks share this device's key — use Encryption": the redirect dialog. */
        REDIRECT_TO_ENCRYPTION,
        /** Prompt the current passphrase, collect a new one, [changePassphrase]. */
        NOTEBOOK_PASSPHRASE,
        /** Collect a new passphrase, [toNotebook] from the session's global. */
        GLOBAL_TO_NOTEBOOK,
        /** Prompt the current passphrase, [toGlobal] onto the session's global. */
        NOTEBOOK_TO_GLOBAL,
    }

    fun route(row: Row, scope: KeyScope): Route = when (row) {
        Row.CHANGE_PASSPHRASE -> when (scope) {
            KeyScope.GLOBAL -> Route.REDIRECT_TO_ENCRYPTION
            KeyScope.NOTEBOOK -> Route.NOTEBOOK_PASSPHRASE
        }
        Row.CHANGE_SCOPE -> when (scope) {
            KeyScope.GLOBAL -> Route.GLOBAL_TO_NOTEBOOK
            KeyScope.NOTEBOOK -> Route.NOTEBOOK_TO_GLOBAL
        }
    }

    /** Thrown before anything is touched when the notebook is open in this process. */
    class OpenInProcess : IllegalStateException("that notebook is open — close it first")

    /**
     * `GLOBAL` → the person's own passphrase. [global] is the session's (the key the file is
     * under now), [typed] the new one. Honours the downgrade rule: `typed == global` leaves the
     * notebook `GLOBAL` and parks nothing. Returns the scope the notebook is now under.
     */
    suspend fun toNotebook(context: Context, notebookId: String, global: String, typed: String): KeyScope {
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) throw OpenInProcess()
        val scope = scopeFor(typed, global)
        SoilRekey.rekeyInPlace(
            context, file, notebookId,
            oldPassphrase = global, newPassphrase = typed, keyScope = scope.column,
        )
        IndexRepository().setEncryptionState(notebookId, scope)
        if (scope == KeyScope.NOTEBOOK) PassphraseCache.storeOnce(notebookId, typed)
        return scope
    }

    /**
     * `NOTEBOOK` → this device's key. [current] is the notebook's own passphrase — collected by
     * [NotebookPassphrasePrompt], which verified it against the file — and [global] the session's.
     * The cover stays null until the next seal paints one.
     */
    suspend fun toGlobal(context: Context, notebookId: String, current: String, global: String) {
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) throw OpenInProcess()
        SoilRekey.rekeyInPlace(
            context, file, notebookId,
            oldPassphrase = current, newPassphrase = global, keyScope = KeyScope.GLOBAL.column,
        )
        IndexRepository().setEncryptionState(notebookId, KeyScope.GLOBAL)
    }

    /**
     * A `NOTEBOOK` notebook's own passphrase replaced. [current] verified by the prompt, [typed]
     * checked by [PassphraseRules] with `current` so it is never the same. The scope does not
     * change, but [IndexRepository.setEncryptionState] still runs: it is what clears the backup
     * stamps (the file's bytes changed under an untouched `updatedAt`) and forgets the unlock.
     * Honours the downgrade rule too: a new passphrase equal to the global makes it `GLOBAL`.
     */
    suspend fun changePassphrase(
        context: Context, notebookId: String, current: String, typed: String, global: String,
    ): KeyScope {
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) throw OpenInProcess()
        val scope = scopeFor(typed, global)
        SoilRekey.rekeyInPlace(
            context, file, notebookId,
            oldPassphrase = current, newPassphrase = typed, keyScope = scope.column,
        )
        IndexRepository().setEncryptionState(notebookId, scope)
        if (scope == KeyScope.NOTEBOOK) PassphraseCache.storeOnce(notebookId, typed)
        return scope
    }
}
