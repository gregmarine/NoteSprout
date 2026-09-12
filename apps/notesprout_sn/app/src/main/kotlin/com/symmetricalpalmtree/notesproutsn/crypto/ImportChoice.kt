package com.symmetricalpalmtree.notesproutsn.crypto

/**
 * **The import keying chooser's outcome table** (arc 26 / U5, decision 3) — pure. og's chooser
 * returns for a file that needed a *foreign* passphrase; this object decides when it is asked and
 * what each answer means for the file that lands in the Garden. `ImportFlow` asks the question
 * and `ImportKeying.toScope` runs the transform; neither decides anything.
 *
 *  - **Asked only for a foreign key.** Plaintext has nothing to keep; a file this device's key
 *    already opens is a same-device export coming home. Both land `GLOBAL` without a question,
 *    exactly as arc 16 did.
 *  - *Keep this passphrase* → the file stays under the typed one and the notebook is `NOTEBOOK`
 *    scope — **unless the typed passphrase is the device's global**, in which case the notebook
 *    is `GLOBAL` (og's downgrade rule, [ScopeChange.scopeFor]). Unreachable through the flow as
 *    built (the global is tried first), but the rule is the table's, not the caller's.
 *  - *Use this device's key* → re-keyed to the global, `GLOBAL` — arc 16's unconditional branch.
 *  - *Set a new notebook passphrase* → re-keyed to the new one, `NOTEBOOK` (the downgrade rule
 *    applies to it as well).
 *
 * A `NOTEBOOK` outcome parks its passphrase ([PassphraseCache]) so the first open after the
 * import does not ask for what the person typed a moment ago (decision 12).
 */
object ImportChoice {

    enum class Choice { KEEP, DEVICE_KEY, NEW_PASSPHRASE }

    /** Where the imported file ends up: the passphrase it opens under, the scope the index and
     *  meta record, and whether that passphrase is parked for the first open. */
    data class Outcome(val passphrase: String, val scope: KeyScope) {
        val parkForFirstOpen: Boolean get() = scope == KeyScope.NOTEBOOK
    }

    /** True when the chooser is put to the person: an encrypted file that only a foreign
     *  passphrase opened. */
    fun needsChooser(opening: ImportKeying.Opening, global: String): Boolean =
        opening is ImportKeying.Opening.Encrypted && opening.passphrase != global

    /** The outcome for a file that did **not** need the chooser — plaintext or same-device. */
    fun withoutChooser(global: String): Outcome = Outcome(global, KeyScope.GLOBAL)

    /**
     * The outcome for [choice] over a foreign [opening]. [newPassphrase] is required for
     * [Choice.NEW_PASSPHRASE] (already through `PassphraseRules`) and ignored otherwise.
     *
     * @throws IllegalArgumentException for a plaintext opening (the chooser is never asked for
     *   one) or a NEW choice with nothing typed — host bugs, not user states.
     */
    fun decide(
        opening: ImportKeying.Opening,
        choice: Choice,
        global: String,
        newPassphrase: String? = null,
    ): Outcome {
        require(opening is ImportKeying.Opening.Encrypted) { "chooser over a plaintext file" }
        return when (choice) {
            Choice.KEEP -> Outcome(opening.passphrase, ScopeChange.scopeFor(opening.passphrase, global))
            Choice.DEVICE_KEY -> Outcome(global, KeyScope.GLOBAL)
            Choice.NEW_PASSPHRASE -> {
                val typed = requireNotNull(newPassphrase) { "new passphrase chosen with nothing typed" }
                Outcome(typed, ScopeChange.scopeFor(typed, global))
            }
        }
    }
}
