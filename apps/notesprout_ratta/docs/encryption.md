# Encryption (arc 26 "Keys")

Arc 26 "Keys" (U1–U7, 2026-09-05) closes SN's encryption surface to og parity: a screen for the
secret that opens the library, an in-place rekey that can change any Garden file's key without
ever leaving it in a state only a lost key opens, a journaled global rotation, per-notebook
passphrases, and a recovery path for a notebook whose saved key no longer fits. The full plan and
phase ledger — every design call, every on-device measurement, every trap — lives in the standalone
`ENCRYPTION_PLAN.md`; this document is the settled reference for the feature it left behind. og
Notesprout (`apps/notesprout_android`) was a reading reference throughout — no app code is copied
from it. **Do not load `RATTA_PLAN.md` for this arc.**

Before the arc, SN was encrypt-by-default under one auto-minted `NSPT-` recovery key with no
encryption UI beyond the two bootstrap screens: the key was shown once and never again in a
release build, could never be replaced, and every notebook was global-scope. The arc adds the
Encryption screen, `SoilRekey` (the only thing in SN allowed to change a file's key on disk),
`GlobalRotation`, `KeyScope`/`KeyResolver` and the per-notebook doors, and `NotebookRecovery`.
Three deliberate SN-isms mark where this diverges from og on purpose: rotation mints a new
`NSPT-` key by default (a typed passphrase is the option, not the default), the door onto the
feature is a library bottom-bar button (SN has no overflow menu to hang it from), and og's
`SoilMigrator` plaintext branch is not ported — SN was born encrypted, so a plaintext probe at
boot stays `FOREIGN_FILE`, never a conversion target.

**Not a point, not an `API_VERSION` bump, no extension touched.** Everything is host-only:
`crypto/`, the bootstrap, the library, the notebook open path, and the export/import/backup call
sites. App version stays `0.1.0-ratta`.

Cross-reference: Paper's `apps/notesprout_paper/docs/crypto.md` has the byte-level format —
stock SQLCipher 4 defaults, the format-compatibility law this arc never touches.

## Decisions (binding, wizard 2026-09-05)

| # | Decision |
|---|---|
| 1 | New passphrase form on rotation: **both** — mint a new `NSPT-` key by default, "Choose my own" as the option. A minted key is shown once through `RecoveryKeyActivity` (its acknowledgement cleared by the rotation); a typed one is never shown again. Unlock accepts anything typed and tries the Crockford confusable fold as a second attempt. |
| 2 | Per-notebook scope: **full og parity** in this arc — prompt on every open, lock card, library-sheet Change passphrase / Change scope, create-time scope choice, import chooser, backup copies uncompacted, recovery. |
| 3 | Import keying: og's chooser returns for a file that needed a *foreign* passphrase — *Keep this passphrase* (→ `NOTEBOOK`, downgraded to `GLOBAL` if it equals the device key) / *Use this device's key* (today's re-key) / *Set a new notebook passphrase*. Plaintext and same-device files never see the chooser. |
| 4 | Old backups after a rotation: **warn before, offer after.** The confirm dialog names the backups warning; rotation clears both stamp maps so the next run replaces every file; the completion dialog offers Back up now / Done. |
| 5 | The door: a new library bottom-left button after Backup — `[Backup] [Encryption] [Import]`, Tabler `lock`, hint "Encryption", shelf-hidden like its neighbours. |
| 6 | Forget on this device: ships in release, behind a confirm dialog; nothing is decrypted or modified. |
| 7 | Reveal recovery key: no re-authentication — the device PIN is the gate, as in og. |
| 8 | Code review: none in this arc, on any phase. |
| 9 | Walks: Sonnet drives adb walks; rotation and recovery are walked by Fable by hand, step by step, Nomad only. |
| 10 | Name / letter / file: Arc 26 "Keys", phases U1–U7, standalone `ENCRYPTION_PLAN.md`. |
| 11 | Lock card: a `NOTEBOOK`-scope notebook's card shows a lock glyph, no cover — the index stores no cover for it. |
| 12 | Prompt frequency: every open, like og. Create/import seed a single-use `PassphraseCache` so the first open right after does not re-ask. |
| 13 | Typed passphrase rules: ≥ 8 characters, confirm field, trimmed, no other rule — one rule set for both a chosen global passphrase and every notebook passphrase. |
| 14 | Debug menu: both "Show recovery key" / "Forget cached key" duplicates removed at U1. |
| 15 | App version stays `0.1.0-ratta`. |

Phase-start answers, binding the same as the wizard table: U3's Cancel stops after the current
file (no "finish now / later"), and the progress dialog reads "Re-keying n of t…" plus the current
name, with stores as "Extension data" and the index as "Library index"; U4's picker rule shows a
**lock row that prompts**, never hides, and the cloud leg has no new skip wording (a sealed
notebook has no WAL, so a backup of a locked notebook needs a key only to absorb a leftover one);
U5's New Notebook scope choice is a **radio row** under the type row, and the notebook's own bar
carries no key rows at all — the library long-press sheet is the only door onto a notebook's key.

## The key model

Three secrets exist, and each lives in exactly one place:

- **The GLOBAL passphrase** — a minted `NSPT-` Crockford key (`crypto/GlobalKey`) or a typed one,
  cached in `crypto/PassphraseStore` (`EncryptedSharedPreferences`, file `sn_secure`) and mirrored
  in RAM by `crypto/KeySession` for the process lifetime. `KeySession.get()` is for the GLOBAL
  passphrase only and never receives a notebook's.
- **A NOTEBOOK passphrase** — never stored. Typed at every open of the notebook screen (decision
  12), parked once for the very next open by `crypto/PassphraseCache` (single-use, 60 s TTL).
- **The derived raw key** — one per file (`crypto/KeyMaterial`), cached in RAM and, Keystore-wrapped,
  in `crypto/DerivedKeyStore`. Derivation runs through `crypto/RawKeyDerivation.deriveKey`, which
  now goes through the platform `SecretKeyFactory("PBKDF2WithHmacSHA512")` in one native call
  (~8.8 s on the Nomad) rather than the hand HMAC loop it falls back to when the platform provider
  is unavailable — see "What the Nomad walks proved" for why the loop must never return to the hot
  path.

`crypto/KeyScope` (`GLOBAL` / `NOTEBOOK`) is the typed face of the index's `keyScope` string
column (`KEY_SCOPE_GLOBAL` / `KEY_SCOPE_NOTEBOOK`, `data/soil/NotebookMeta.kt`) and
`notebook_meta.keyScope` — no schema change, no migration; every row has carried `GLOBAL` since
arc 1 and it means something now. `ObjectSummary.keyScope` rides every listing blob-free.

Under the global key: every `GLOBAL`-scope `.soil`, every `Garden/<pkg>.db` extension store, and
the index `notesprout.db`. Stores and the index are never notebook-scoped — they are not in any
rotation's or scope door's id list.

`crypto/SoilCrypto` is the one open door for every SQLCipher connection in the app — index or
`.soil`, Room or raw. Every Room factory is wrapped in `NonDestructiveOpenHelperFactory` (a wrong
key reports corruption without deleting), every open requires the file to exist and be non-empty
(`requireExisting`), and `crypto/KeyOpener.roomFactoryFor` verifies a cached raw key against the
file before every Room open and invalidates a stale one — this is SN's form of og's
`SelfHealingKeyFactory`, verify-first rather than heal-after-the-throw, which is why no factory
was ported.

## The Encryption screen and its door

The library toolbar gained a bottom-left button after Backup: `[Backup] [Encryption] [Import]`,
icon `ic_lock` (Tabler `lock`), long-press hint "Encryption", hidden in shelves along with its
neighbours.

`encryption/EncryptionActivity` (`IndexGuard.ready` first thing in `onCreate`) shows:

- **Status** — "Recovery key: set" / "Recovery key: not on this device", and a count line ("No
  notebooks use this device's key" / "1 notebook uses this device's key" / "N notebooks use this
  device's key") that reads `IndexRepository.countGlobalNotebooks()`, which queries the index's
  `keyScope` column — honest from U4 on, "every notebook" before it.
- **Reveal recovery key…** — a dialog with the key in monospace, selectable, and **Copy** / **Close**
  buttons; og's wording verbatim (`encryption_reveal_title` / `_body`). **No re-authentication**
  (decision 7) — the device PIN is the only gate, since this is the same key already cached on the
  device. Copy puts the key on the system clipboard via `ClipData.newPlainText`, labelled
  "Notesprout SN recovery key", and shows a toast; the clipboard entry survives a subsequent
  process kill (see "Standing traps").
- **Change passphrase…** (U3) — GONE until U3 built it; see "Rotation" below.
- **Forget on this device…** — a confirm dialog (`encryption_forget_body`: "The recovery key will
  be removed from this device. The next launch will ask for it before the library opens. No
  notebooks will be decrypted or modified.") then `forget()`: clears
  `PassphraseStore.clearGlobalPassphrase`, `KeyMaterial.clearAll`, `KeySession.clear`,
  `PassphraseCache.clear`, `NotebookUnlocks.clear`, calls `finishAffinity()`, and — 400 ms later —
  **kills the process** with `android.os.Process.killProcess`. The kill is deliberate: `SnIndex`
  has no close method, so a relaunch into the still-live process would find the index open and
  answer READY with no key cached anywhere. This is the same reasoning U3's relaunch-through-
  Bootstrap design leans on, so nobody "simplifies" the kill away later.
- **The resume banner** — while a rotation marker exists (`GlobalRotation.hasMarker`), a banner
  replaces the status render's normal reading and both **Change passphrase…** and **Forget on this
  device…** go `GONE`: neither is a safe thing to start on top of a library that is in two keys.
  Tapping **Resume** re-runs `GlobalRotation.resume`.

The debug menu's "Show recovery key" and "Forget cached key" were removed at U1 — every crypto
walk now starts at Reveal.

## The in-place rekey — `SoilRekey`

`crypto/SoilRekey` is **the only thing in SN allowed to change the key a file on disk is under** —
one recipe for a `.soil`, a `Garden/<pkg>.db` extension store, and the index alike. `GlobalRotation`,
the library sheet's `ScopeChange` doors, and `NotebookRecovery`'s repair all call
`SoilRekey.rekeyInPlace`; nothing else re-keys.

**`rekeyInPlace(context, file, fileId, oldPassphrase, newPassphrase, keyScope)`:**

1. **The file must be cold.** No connection in this process (`SoilOpenFiles.isOpen` for a `.soil`;
   the caller runs `ExtensionStores.closeAll()` for a store or `SnIndex.closeForRotation()` for the
   index before calling in). Its WAL is absorbed by `absorbWal`: a raw open under the *current* key,
   `PRAGMA wal_checkpoint(TRUNCATE)`, close, then `SoilCompactor.sweepSidecars`. A non-empty `-wal`
   left after that throws — it is never deleted, and the rekey stops before writing anything.
2. `ExportKeying.exportAndKeyToPrimary` exports the file into a sibling `<name>.rekey.tmp` under the
   new key — og's proven orientation, the destination as the primary connection, the source
   attached and read from. `copyUserVersion` carries `PRAGMA user_version` across by hand
   (`sqlcipher_export` drops it — the standing trap below); for a `.soil`, `restampMeta` writes
   `notebook_meta.keyScope` to the scope the caller says the file *becomes*. Acceptance is probe
   `Encrypted` + the new key opens it + `integrity_check` = `ok` + `user_version` unchanged. A
   failure at any step deletes only the tmp.
3. **`RekeyCommit.commitReplace`** — og's fsync'd rename order, over an injected `RekeyFs` so the
   order is provable on the JVM: fsync the tmp → refuse if the original's `-wal` is non-empty, else
   delete the empty `-wal`/`-shm` → clear a stale `X.old.bak` → rename the original to
   `X.old.bak` → rename the tmp to the original name; on failure rename the `.old.bak` straight
   back → fsync the directory → delete `.old.bak`. Every exit is one of five `Outcome`s
   (`Committed`, `RefusedLiveWal`, `OriginalNotMoved`, `RolledBack`, `BothKept`); nothing throws
   inside the state machine.
4. `KeyMaterial.invalidate(fileId)` — the salt changed, so any cached raw key is stale.

`PRAGMA rekey` is never used — og's own on-device finding, already the arc-15 law, and the reason
this whole recipe is export-and-key instead.

**Recovery of an interrupted commit** — `SoilRekey.recoverGarden(context, verifies)`, run by
Bootstrap once the index is open and by rotation's `resume` before its loop. It walks every
`*.rekey.tmp` / `*.old.bak` name in `Garden/` (`RekeyNames.leftoverOriginals`) and calls
`RekeyRecovery.recover` for each original. `RekeyRecovery.decide` is the pure table:

| original | tmp | bak | plan |
|---|---|---|---|
| verifies | any | any | drop the leftovers — the swap already finished (or never started) |
| present, unverified | any | any | leave everything — a present file no trusted key opens is never touched |
| absent | verifies | any | restore the tmp — the death was between the two renames |
| absent | not verified | verifies | restore the bak — the tmp is untrusted |
| absent | not verified | not verified | leave both, for a person to look at |

**Nothing is deleted unless the file that will survive verifies** under a key the caller trusts.
The index has no directory listing of its own, so `SnIndex.ensureReady` calls
`SoilRekey.recoverOne` for it directly before it could ever mistake a missing `notesprout.db`
with leftovers beside it for a fresh install.

Cost, measured on the Nomad: ~4.0 s per small (24 KiB) file each direction — two KDF verifies (the
absorb-WAL open and the acceptance open) plus the copy, the KDF dominating.

## Rotation — `GlobalRotation`

`crypto/GlobalRotation` re-keys everything the global key opens — `GLOBAL`-scope notebooks, then
every extension store, then the index last — from the cached global passphrase to a new one.

**Journal first.** `start` writes a `crypto/RotationMarker` to `PassphraseStore` (kotlinx JSON,
`commit()` not `apply()` — the journal must be on disk before any file is touched) before touching
anything, and rewrites it after every file. The marker carries `pendingIds` (in `RotationPlan`
order), `newPassphrase`, `minted`, `total`, `notebookCount`, `startedAt`, and `quarantined`. While
it exists the library is in two keys: files already re-keyed open under the new passphrase, the
rest under the cached global (still the old one until commit).

`crypto/RotationPlan` is the pure half:

- **`order`** — `GLOBAL` notebooks, then `ext:<pkg>` stores, then the index id
  (`KeyMaterial.INDEX_FILE_ID`) **last**, because the index holds the rows the loop reads (names for
  the progress dialog, the quarantine flag) and nothing may touch it in this process after its own
  rekey.
- **`decide(kind, opensUnderNew, opensUnderOld)`** — `SKIP` (already under the new key, idempotent
  after a resume), `REKEY` (still under the old), `QUARANTINE` (a notebook under neither key),
  `STOP` (a store or the index under neither — nothing to quarantine, the rotation stops with
  `Failed`).
- **`afterFailure`** — the same table re-read once a rekey throws: still under the old key is
  `TRANSIENT` (kept pending, `Failed` reported, the person resumes); a notebook under neither is
  `QUARANTINE`; a store or the index under neither is `STOP`.
- **`commitSteps(minted)`** — the ordered side-effect list the commit executes: `SET_GLOBAL` first
  (a death right after still resumes to a rotation where every file skips and the commit re-runs),
  `CLEAR_ACK` only for a minted key, `CLEAR_RAW_KEYS`, `SET_SESSION`, `CLEAR_MARKER` last (the
  journal outlives everything it guards).
- **`resumeCandidates`** — a resume re-lists the library: a `GLOBAL` notebook not already pending
  whose row is newer than `startedAt` (created or imported between a Cancel and a Resume — the
  library stays reachable in between) or whose cached raw key still opens the file (free to check,
  and a rekey always invalidates it) joins the pending list, so nothing is left behind under the
  old key.

**Per file** (`GlobalRotation.rotateFile`): the cached raw key is tried first (a hit means "under
the old key" for free, since every rekey invalidates it); a KDF verify under the new key answers
"already done" otherwise. A file under the old key goes through `SoilRekey.rekeyInPlace`. A
notebook that opens under **neither** key is **quarantined** — `IndexRepository.quarantine` sets
`keyScope = NOTEBOOK` (the lock card, U4 on), clears its backup stamps, drops it from pending, and
the rotation carries on; the count is reported at the end and U6's recovery is the way back. A
store or the index under neither is `STUCK` — nothing to quarantine, the rotation stops with
`Result.Failed(Reason.STUCK, …)`, nothing deleted, hand recovery only.

**The rituals, in order:** `ExtensionStores.closeAll()` before the first store; before the index,
`BackupStore().clearAllStamps()` (both maps, decision 4 — a rekey leaves `updatedAt` untouched, so
a forgotten stamp would keep an old-key copy in every backup forever) while the index is still
open, then `SnIndex.closeForRotation()` — the one door that closes the index (checkpoint, close,
`instance = null`, under the prepare mutex). After that, this process touches no index row until
the relaunch; the caller shows only dialogs.

**Commit** (`GlobalRotation.commit`, `RotationPlan.commitSteps`): `setGlobalPassphrase(new)` →
clear the recovery-key acknowledgement if minted → `KeyMaterial.clearAll` → `KeySession.set(new)` +
`PassphraseCache.clear()` → clear the marker. Idempotent — this is also the tail of resume path 3.

**Three resume paths, all needed:**

1. **The Encryption screen's banner** — while a marker exists, Change and Forget are `GONE` and
   **Resume** calls `GlobalRotation.resume`.
2. **`bootstrap/BootstrapRoute.afterOpen`** — the pure decision shared by `BootstrapActivity`,
   `UnlockActivity`, and `RecoveryKeyActivity`'s Continue: unacknowledged recovery key first (also
   how a *minted* rotation's new key gets shown once), then a marker → the Encryption screen (so
   the banner can never be missed), else the library.
3. **`SnIndex.ensureReady`'s marker path** — if the index cannot open under the cached global but
   the rotation marker's `newPassphrase` opens it (death after the index's own rekey, before
   commit), it opens under the marker's key and **commits the rotation itself**
   (`openUnderMarkerOrUnlock` → `finishOpen` → `GlobalRotation.commit`), then answers READY.

`GlobalRotation.trustedVerifier(context)` is the verifier both `SoilRekey.recoverGarden` and the
index-leftover guard in `ensureReady` use while a rotation may be in flight: the cached global
**or** the marker's new passphrase, whichever fits. While a marker exists, `crypto/KeyResolver`
offers the marker's new passphrase as a **second candidate** for a `GLOBAL` notebook the cached
global no longer opens — an already-rotated notebook stays openable mid-rotation without a
prompt.

**UI, verbatim strings.** *Change passphrase…* asks the current passphrase
(`encryption_current_title` / `_body`, string match plus the Crockford fold) → *New passphrase*
(`encryption_new_title`): **Generate a new recovery key** (default radio) / **Choose my own** (two
fields under `PassphraseRules`) → a confirm dialog carrying the backups warning
(`encryption_change_body`: "Every notebook, the extension data and the library index will be
re-keyed. Keep the app open — this can take a while.\n\nExisting backups, local and cloud, open
only with the current key until the next backup run replaces them. You can start one when this
finishes.", plus `encryption_change_minted` for a minted key) → a non-cancelable progress dialog
titled "Changing passphrase" (`encryption_progress_title`), body "Re-keying %1$d of %2$d…"
(`encryption_progress_count`) plus the current name on its own line — stores read "Extension data"
and the index "Library index" — and a footer "Keep the app open. This can take a while."; **Cancel**
swaps the footer to "Stopping after this file…" and goes `GONE` itself → completion: **Passphrase
changed** with **Back up now** / **Done** (`encryption_done_*`, plus a quarantine line when any
notebooks could not be re-keyed) · **Change paused** (Cancelled — the banner takes over) · **Change
interrupted** (Failed — relaunches through Bootstrap on dismiss, since the index may be closed).
"Back up now" carries `EXTRA_THEN_BACKUP` through `BootstrapActivity.relaunchIntent`, a boolean
that survives the recovery-key screen (the library follows right after) but not a resume through
the Encryption screen (nothing to back up yet).

The minted key is shown once by the existing `RecoveryKeyActivity` through the acknowledgement
gate — rotation only clears the flag, it does not add a new screen.

## Notebook scope — resolver, prompt, and the open-site table

`crypto/KeyResolver` is **pure decision; the prompts are UI.** Every open site asks
`KeyResolver.forOpen(context, id, scope)` (or its pure `decide` table directly, in tests) and acts
on the answer:

```
sealed interface Resolved {
    data class Passphrases(val candidates: List<String>) : Resolved   // GLOBAL
    class Unlocked(val rawKey: ByteArray) : Resolved                  // NOTEBOOK, unlocked + cached
    object NeedsPrompt : Resolved                                     // NOTEBOOK, else
    object NoKey : Resolved                                           // no global at all
}
```

`decide(scope, global, markerNew, unlocked, rawKey)`: a `GLOBAL` notebook with no cached global at
all answers `NoKey`; with a rotation marker whose new passphrase differs, `Passphrases([global,
markerNew])` (the marker as a second candidate); otherwise `Passphrases([global])`. A `NOTEBOOK`
notebook unlocked this process (`NotebookUnlocks.has`) with a raw key that still verifies against
the file answers `Unlocked(rawKey)` — `forOpen` calls `KeyMaterial.peekVerified`, never
`peekOrLoad`, so a stale key answers `NeedsPrompt` instead of handing back a key that would fail.

`crypto/NotebookUnlocks` is a per-process `ConcurrentHashMap`-backed id set — RAM only, cleared with
the process or by Forget. It is not a passphrase cache: the notebook screen still prompts on
**every** open (decision 12) regardless of what this set holds; the set exists so *silent* reads
that follow a deliberate unlock — recents, the export source, a picker drill — can proceed without
a second prompt.

`data/soil/SoilDatabase.resolve(context, notebookId)` reads the index's `keyScope` and calls
`KeyResolver.forOpen`; `SoilDatabase.open(context, notebookId, file, resolved)` is the `Resolved`
overload of the ordinary `open`. `KeyOpener.roomFactoryFor(context, fileId, file, resolved)` turns
a `Resolved` into a Room factory: `Passphrases` with one candidate is today's cold path; two
candidates are each verified in turn (a rotation in flight); `Unlocked` is verified and used, or
invalidated and refused if stale; `NeedsPrompt` / `NoKey` throw `SoilLockedException` — **this path
never prompts**, a caller that can must resolve (or ask) first. `SoilDatabase.readOnce` answers
**null** for a locked notebook — it never prompts — and has a `Resolved` overload for a caller that
just prompted: it passes `Passphrases(typed)` directly, because the raw-key warm from
`NotebookPassphrasePrompt` is ~9 s on the Nomad and an immediate read can never wait for
`Unlocked`.

`crypto/NotebookPassphrasePrompt` is **the one dialog** for a notebook's own passphrase — used by
the notebook screen, a link follow, the picker's lock row, and the Export screen. `ask(activity,
notebookId, name, body = null)` shows `dialog_notebook_passphrase`: verify-then-accept inside one
dialog (a wrong entry keeps the dialog and its typing, shows the inline error), `AttemptLimiter`
bucketed on **the notebook id** (entry row `GONE` + a live countdown while locked out, error
cleared when it lifts — Unlock's shape), the IME never hidden (the Ratta rule — a hardware keyboard
types only while it is shown). On success: `AttemptLimiter.recordSuccess`, `NotebookUnlocks.mark`,
`KeyOpener.warm`. `takeParked(activity, notebookId)` is the notebook screen's *first* question — it
alone consults `PassphraseCache.takeOnce`, re-verifies the parked value against the file, and
accepts it exactly like a typed one; `ask` itself never reads the cache (see "the parked-value
trap" below).

`IndexRepository.setEncryptionState(id, scope)` is the **only** scope writer: sets the `keyScope`
column, nulls the cover blob for `NOTEBOOK` (decision 11 — the seal's cover-capture also skips a
`NOTEBOOK` notebook, so nothing repaints one), clears the notebook's stamp in **both** backup maps
(a scope or passphrase change re-keys the file without touching `updatedAt`, and a stamp compared
against `updatedAt` would otherwise keep the old-key copy forever), and forgets the process unlock.
`updatedAt` is never bumped. `IndexRepository.quarantine(id)` is `setEncryptionState(id,
KeyScope.NOTEBOOK)`.

### Open-site table

| Site | GLOBAL | NOTEBOOK |
|---|---|---|
| Notebook screen (`NotebookActivity.keyFor`) | `resolve` → open | overlay down, `takeParked` then `ask`, overlay up; cancel clears the last-open pointer and finishes, no dialog |
| `refreshMeta` | scope sourced from the index row every time (never the previous meta row) | same — the meta-refresh-wipe trap |
| Cover capture (onStop, close) | captured as usual | **skipped** — no thumbnail ever fetched for a locked notebook |
| `LinkFollowFlow` (follow + walk-back) | opens with `resolve` | **prompts** on both the follow and the walk-back (a follow is a deliberate act); `PassphraseCache.storeOnce` before leaving = one prompt per hop |
| Link picker / `ForeignPageSource` | plain thumbnail | **lock row**, drawn with the lock glyph, that **prompts** on tap; `ForeignPageSource(passphrase)` carries the typed passphrase for the source's lifetime, re-opening after every seal |
| `PickMode.NOTEBOOK` (link-to-notebook) | not opened either way | not opened — nothing is read for this pick mode |
| Library / search / picker grids | ordinary cover | `CardItem.Notebook.locked`, `paintLock` draws `ic_lock` at ⅓ card width in the cover's place, **no thumbnail fetch** |
| Export (`ExportActivity.resolveSourceKey`) | prompts nothing extra | prompts **once**, at the head of `loadCandidates`; `sourceKey` threads into every renderer (artifact/render/text/document-PDF); Keep reads "Keep encrypted (this notebook's passphrase)"; `Guard.LOCKED` / `export_notebook_locked_body` on the resolver-less path |
| `BackupEngine.compactPass` | runs as usual | **skipped** (og's rule — no unattended key); the copy and `-wal` sidecar still happen before the stamp |
| `SelfContainedSnapshot` (cloud leg) | a copied non-empty WAL is absorbed under the verified raw key or the session global | **no open at all when no WAL was copied** — a sealed notebook has none, so a locked notebook uploads without a key; a leftover WAL is absorbed only if `peekVerified` has its raw key, else the snapshot is refused (`LockedFile`, `Slog` level) and counted failed like any unabsorbed WAL — the cloud never holds a sidecar |
| Extension stores, the index, notebook create | always global | never notebook-scoped — not in this list at all |
| Rotation's id list | included | **excluded** — never touched by a rotation |
| Forget | — | clears `NotebookUnlocks` along with everything else |

## The doors — `ScopeChange` and the four entry points

`crypto/ScopeChange` is **the only caller of `SoilRekey` for one notebook** — the three functions
every door reduces to:

- **`toNotebook(context, id, global, typed)`** — `GLOBAL` → the person's own passphrase.
- **`toGlobal(context, id, current, global)`** — a notebook's own passphrase → `GLOBAL`.
- **`changePassphrase(context, id, current, typed, global)`** — a `NOTEBOOK` notebook's own
  passphrase, replaced.

All three are the same three steps in the same order: **re-key first** (`SoilRekey.rekeyInPlace` —
atomic, the original untouched on any failure), **then record the scope**
(`IndexRepository.setEncryptionState`), **then park the new passphrase**
(`PassphraseCache.storeOnce`, `NOTEBOOK` outcomes only — taken solely by the notebook screen's next
open). Every function refuses a notebook open in this process (`ScopeChange.OpenInProcess`) —
belt over braces, since from the library a notebook never is.

**The downgrade rule** (og's, decision 3): `scopeFor(typed, global)` answers `GLOBAL` when
`typed == global` — a notebook whose "own" passphrase turns out to be the device's global one *is*
a `GLOBAL` notebook, re-keyed to the same key (a no-op copy that still restamps the meta honestly)
with nothing parked, because a `GLOBAL` open never asks. `route(Row, KeyScope)` is the pure table
behind the library sheet's two rows:

| Row | GLOBAL | NOTEBOOK |
|---|---|---|
| *Change passphrase…* | `REDIRECT_TO_ENCRYPTION` | `NOTEBOOK_PASSPHRASE` |
| *Change encryption scope…* | `GLOBAL_TO_NOTEBOOK` | `NOTEBOOK_TO_GLOBAL` |

**New Notebook** — a Key radio row under the type/template rows: **This device's key** (default) /
**Its own passphrase**. Choosing the latter raises `crypto/SetPassphraseDialog` after the name
check but before the file is created; the notebook is created under the typed key, the meta and
index row carry `NOTEBOOK` scope, `TextCover` is skipped, and the passphrase is parked for the
notebook's first open.

**Library long-press sheet** (`library/ScopeChangeFlow`) — the *only* door onto a notebook's key
(the notebook's own bar carries none, U5's phase-start answer). *Change passphrase…* on a `GLOBAL`
notebook shows a redirect dialog ("Global notebooks share this device's key. To change it, use
Encryption.") with an **Open Encryption** button; on a `NOTEBOOK` notebook it prompts the current
passphrase (`NotebookPassphrasePrompt`, which verifies it) then collects a new one
(`SetPassphraseDialog`) and calls `ScopeChange.changePassphrase`. *Change encryption scope…* runs
`toNotebook` (collect a new passphrase, rekey from the session's global) or `toGlobal` (prompt the
current passphrase, rekey to the session's global) depending on the card's own scope. Both routes
run the rekey under a non-cancelable "Re-keying…" dialog (4–8 s on the Nomad — long enough to read
as a hang on e-ink without it) and report a failure as one sentence ("The notebook could not be
re-keyed. Nothing was changed.") with only the exception's class name logged.

**Import chooser** (`crypto/ImportChoice`, inside `ImportFlow`) — asked only after a *foreign*
passphrase verifies during import (a plaintext file or a same-device export never sees it):

| Choice | Outcome |
|---|---|
| Keep this passphrase | `NOTEBOOK`, downgraded to `GLOBAL` via `scopeFor` if it equals the device key |
| Use this device's key | `GLOBAL` (arc 16's unconditional re-key branch) |
| Set a new notebook passphrase | `NOTEBOOK` (via `scopeFor` too) |

`ImportKeying.toScope(incoming, opening, outcome)` is the general form of the old `toGlobal` — a
pass-through (the file already opens under the outcome's passphrase) is still integrity-checked
and restamps `notebook_meta.keyScope` via `ExportKeying.restampMetaScope`, since *Keep this
passphrase* on a foreign export lands a `NOTEBOOK` notebook whose file must say so.
`setEncryptionState(NOTEBOOK)` runs before `refreshMeta`, and a `NOTEBOOK` outcome is parked
before `onImported` — decision 12's rule applies here too.

**Export's Keep label** — a host-side label substitution (`optionLabel`): a `NOTEBOOK`-source
notebook's Keep row reads "Keep encrypted (this notebook's passphrase)"
(`export_keying_keep_notebook`) instead of the generic wording; `:ext-soil` is untouched, and the
meta restamps its scope from the index on the way out.

`crypto/SetPassphraseDialog` is **the one** set-a-notebook-passphrase dialog, shared by all three
doors above: new + confirm fields, `PassphraseRules` inline, the IME never hidden.

## Recovery — `KeyFailure` + `NotebookRecovery`

`crypto/KeyFailure` is a pure classifier: was an open failure the key's fault, or something else
(a schema/migration error, which a passphrase can never fix)? The chain of causes is walked, first
verdict wins:

1. `SoilLockedException` — every "no key fits" refusal in the app throws it → `KEY`.
2. `SQLiteDatabaseCorruptException`, matched **by class name** (so the JVM test suite can pin the
   table without the real class on the classpath) — SQLCipher reports a wrong key as corruption,
   never as "wrong key" → `KEY`.
3. The phrases SQLCipher's own messages use for a wrong key or an undecryptable header ("file is
   not a database", "not a database", "file is encrypted", "corrupt") → `KEY`.
4. Room's schema/migration wording ("invalid schema", "migration", "identity") on an
   `IllegalStateException` → `SCHEMA`, and `isKeyFailure` returns false for it — **never a
   prompt**.

`crypto/NotebookRecovery` is og's "Can't open this notebook" dialog, rebuilt on SN's seams. The
notebook screen calls `offer(activity, notebookId, name, scope)` when
`NotebookSession.OpenResult.Failed.cause` is a key failure. The pure half, `NotebookRecovery.Plan`:

- **`shouldOffer(keyed, isKeyFailure, attempted)`** — offer only for a keyed notebook, only on a
  key failure, and only once per launch.
- **`silentCandidates(global, markerNew)`** — the cached global, then a rotation-in-flight's new
  key, tried silently before any prompt.
- **`decide(scope, keyIsGlobal, hasGlobal)`** → `REPAIR_TO_GLOBAL` when the index says `GLOBAL`
  and the key that worked is not the global itself; `PARK_FOR_REOPEN` for `NOTEBOOK`; `REOPEN`
  otherwise (the global itself worked — the cached raw key was just stale).

**The flow (`offer`):** "Can't open \<name>" (`notebook_recovery_title` / `_body`) → **Try a
passphrase** / **Back to library**. Try first tries the silent candidates, then puts up
`NotebookPassphrasePrompt.ask` with a recovery-specific body ("Enter a passphrase to unlock
\"<name>\"." — `notebook_recovery_prompt_body`), bucket = the notebook id (no softer a target than
the front door). A working key drops the stale raw key (`KeyMaterial.invalidate`) and `Plan.decide`
says what happens next:

- `REPAIR_TO_GLOBAL` → "Repair this notebook?" (`notebook_recovery_repair_title` / `_body`) →
  **Repair and open** = **`ScopeChange.toGlobal`** (never `SoilRekey` directly — the recovery
  flow reuses the same door the sheet does) under a non-dismissable "Re-keying…" box. Declined
  leaves the library untouched.
- `PARK_FOR_REOPEN` → `PassphraseCache.storeOnce` — the reopen does not ask again for what was
  just typed.
- `REOPEN` → nothing further; the stale raw key is already gone.

`NotebookSession.OpenResult.Failed` carries its `cause`; the notebook screen offers recovery
**once per launch** (`EXTRA_RECOVERY_ATTEMPTED` on the Intent, og's `openFixAttempted`), a RETRY
outcome re-runs the whole `openSession()` (not just the key step), and a decline leaves quietly
with the last-open pointer cleared.

A `NOTEBOOK`-scope notebook essentially never reaches the "Can't open" dialog by the *ordinary*
open path — its prompt (`NotebookPassphrasePrompt`) already verifies before the open can even be
attempted, so the front door **is** the recovery for that scope. The quarantine case (a `NOTEBOOK`
row whose "own" passphrase is an old global key, left behind by a rotation that could not open it)
is opened by that same ordinary prompt once the person knows the old global, and brought back to
`GLOBAL` by the library sheet's `NOTEBOOK → GLOBAL` row — the manual half of recovering from a
quarantine.

**The raw-path audit.** `KeyMaterial.peekVerified(context, fileId, file)` — a cache hit verified
against the file, a stale one dropped everywhere — replaces every bare `peekOrLoad` call on an
open path: both `KeyOpener` factories, `SelfContainedSnapshot.absorbWal`, `GlobalRotation`
(`opensUnderOld` and the resume candidates, which never invalidated a stale hit before), and
`KeyResolver.forOpen` (a stale key now answers `NeedsPrompt`, as its own documentation always
said, instead of an `Unlocked` the opener had to refuse anyway). `peekOrLoad` is left for "is one
cached?" questions only (`RekeyProbe`'s report).

**The walk-found race, fixed.** A raw-key warm queued *before* a rekey could land *after* the
rekey's `invalidate` and store a key for a file that no longer existed under that salt.
`KeyMaterial.generation(fileId)` (a per-file counter combined with a `clearAll` epoch) is captured
by `KeyOpener.warm` at the moment it queues the derive; `KeyMaterial.rawKey(…, ifGeneration)`
refuses to persist the derived key if the generation moved in the meantime (logged "discarded").
Known and left as harmless: a cold open that fails still queues one warm for the passphrase it
tried — one wasted derive, caught by verify-first on the next open.

## Backups and the cloud under rotation and scope

Backup stamps compare `updatedAt`, which a rekey never touches — so rotation clears **both** stamp
maps (`BackupStore.clearAllStamps`, local and cloud) before the index's own turn, and a scope or
passphrase change clears the single notebook's stamp in both maps
(`IndexRepository.setEncryptionState`). Either way the next backup run replaces every stale-key
copy; nothing runs unasked (decision 4 — warn before, offer after).

Old backups taken before a rotation or a scope/passphrase change open only under the old key until
that next run. `BackupEngine.compactPass` is skipped for a `NOTEBOOK`-scope notebook (og's
no-unattended-key rule) — the copy and its WAL sidecar still travel uncompacted. The cloud leg's
`SelfContainedSnapshot` opens nothing when the live file had no non-empty WAL — a sealed notebook
never has one, so a locked notebook uploads without any key. A leftover WAL is absorbed under the
verified cached raw key (`peekVerified`) or, for a `GLOBAL` file, the session passphrase; a locked
notebook with a WAL and no cached raw key is refused (`LockedFile`, logged at `Slog` level) and
counted failed like any other unabsorbed WAL, then retried next run — the cloud never holds a
sidecar (arc 25's law), and there is no new status-line wording for the case.

## Failure table

| Situation | What the user sees | What happens on disk | Way back |
|---|---|---|---|
| Wrong global at Unlock | `unlock_wrong` inline error | Nothing | Try again; the confusable fold is tried automatically |
| Lockout (per bucket — global, a notebook id, or `"IMPORT"`) | Entry row `GONE`, countdown text | Nothing | Wait it out; `AttemptLimiter.recordSuccess` clears it |
| Rekey interrupted between renames | (silent — a later launch recovers it) | `.rekey.tmp` verifies or `.old.bak` verifies | Bootstrap's `recoverGarden` (or rotation resume's, before its loop) restores whichever survives |
| Rotation cancelled | "Change paused" | Marker kept, index still open | Resume banner on the Encryption screen |
| Process death mid-rotation, before the index's rekey | (process gone) | Marker on disk, cached global still old | Bootstrap forwards to the Encryption screen's banner (path 2) |
| Process death mid-rotation, after the index rekey, before commit | (process gone) | Index opens only under the marker's new passphrase | `SnIndex.ensureReady` opens under the marker's key and self-commits (path 3) |
| Notebook under neither key during rotation | Reported in the completion dialog | `keyScope = NOTEBOOK`, backup stamps cleared, never deleted | `NotebookRecovery`'s repair once the person knows a working passphrase |
| Store or the index under neither key during rotation | "Change interrupted" | Nothing deleted, marker kept | Hand recovery; Unlock with the right key, then Resume |
| Wrong notebook passphrase | Inline error, dialog + typing kept | Nothing | Retry; lockout after repeated failures |
| Cancel at any passphrase prompt | Dialog closes | Nothing opened | Leave quietly — no error dialog |
| Key failure on the notebook screen | "Can't open \<name>" once per launch | Nothing until a working key is found | `NotebookRecovery.offer` — Try a passphrase / Back to library |
| Schema/migration failure on the notebook screen | The screen's ordinary error path | Nothing | Never offered a passphrase prompt — `KeyFailure` returns false |
| Scope change on an open notebook | `ScopeChange.OpenInProcess` → "nothing was changed" | Refused before anything is touched | Close the notebook, retry |
| Same-as-current passphrase | Inline rule error (`passphrase_rule_same` / `_same_global`) | Refused before anything is touched | Type a different one |
| Forget on this device | Confirm dialog, then the process ends | Cached key + raw keys + parked values gone; nothing on disk changed | Unlock again with the recovery key or chosen passphrase |
| Locked notebook in a backup run | Copied like any other; only a locked file with a leftover WAL on the cloud leg is counted failed | Compaction skipped; local copy + `-wal` alongside; cloud snapshot refused only when a WAL cannot be absorbed | Open the notebook once (seals it, warms the raw key); the next run picks it up |
| Export of a locked notebook without a prompt path | `Guard.LOCKED` / `export_notebook_locked_body` | Nothing exported | Open the notebook once, then export |

## What the Nomad walks proved (measured)

- **Rekey:** ~4.0 s per direction on a 24 KiB throwaway notebook (two KDF verifies plus the copy,
  the KDF dominating).
- **Derivation:** the platform PBKDF2 path is **8.8 s** per derive on the Nomad, versus ~2–3 s for
  the hand loop — but the loop leaves native HMAC contexts for the GC to reclaim (~80 MB of churn
  per key), and a burst of concurrent cold derives after a rotation clears every cached key
  exhausted the allocator's per-size-class budget and killed the process (`Scudo OOM: exhausted
  256M for size class 288/352`, native heap 42 → 663 MB in 20 s). The platform path stays flat.
  `KeyOpener.warm` now runs on `Dispatchers.IO.limitedParallelism(1)` so warms are serial, not
  concurrent.
- **Rotation:** ~4.4 s per file across 52 files (45 notebooks + 6 stores + the index); the minted-key
  leg committed cleanly with a Cancel-and-Resume in the middle; the typed-passphrase leg
  (the passphrase typed twice on the on-screen keyboard) took 3 m 53 s end to end, native heap flat at
  26 MB with the derive fix in place. After a rotation, roughly 45 queued raw-key warms drain
  serially over about 7 minutes of background CPU, with opens falling back to SQLCipher's own KDF
  (~1.5 s each) in the meantime.
- **Warm:** the raw-key warm after a passphrase prompt accepts is ~9 s on the Nomad — the reason a
  caller that just prompted passes `Passphrases(typed)` into an immediate read rather than waiting
  for `Unlocked`.
- **Lockout:** roughly 26–27 s after three wrong entries (the existing `AttemptLimiter` schedule,
  now bucketed per notebook id as well as globally).
- **U2's round-trip walk** (throwaway notebook `20260905_142626`): `integrity_check` = ok, 2 rows,
  24576 B before, mid (under the throwaway), and after; the global stopped opening it mid-way and
  the throwaway stopped opening it after the return leg; `Break a rekey commit` variant A (tmp
  verifies) recovered as `RESTORED_TMP`, variant B (tmp garbage) as `RESTORED_BAK` — in both cases
  only the `.soil` was left after the kill and it opened normally.
- **U6's recovery walk:** Break keying on a `GLOBAL` notebook (4.1 s) → Can't open → Try a
  passphrase → three wrong entries → 27 s lockout → the broken key → Repair this notebook? →
  Repair and open → opened with its content intact. The same break on a `NOTEBOOK`-scope notebook
  opened through the ordinary prompt with **no repair offered** — the front door already is the
  recovery there.

## Design calls recorded outside the wizard

- Rotation ends in a **relaunch through Bootstrap** rather than an in-process index reopen — the
  index's DAO consumers were never audited for caching, and the relaunch mirrors og's own restore
  precedent.
- The link picker **shows a lock row that prompts**, never hides a `NOTEBOOK`-scope notebook — a
  preview grid must never prompt on its own, but hiding the notebook entirely would be worse.
- The cloud backup leg grows **no new wording** for a locked notebook: a sealed notebook has no
  WAL, so the snapshot skips the open entirely and uploads it without a key; only a locked file
  with a leftover WAL and no cached raw key is refused and counted failed, like any unabsorbed WAL.
- A scope or passphrase change clears the notebook's backup stamps rather than bumping
  `updatedAt`, which stays sacred.
- `PassphraseRules`: ≥ 8 characters after trim, no character-class rules, identical-to-current
  refused — one rule set for the global passphrase and every notebook passphrase.
- The rotation marker's new passphrase lives in `PassphraseStore` (`EncryptedSharedPreferences`),
  the same posture as the cached global — never in the index, never in an Intent.
- og's `abandonMarker` (drop a half-done rotation without finishing it) is **not built** — it has
  no UI in og either.
- U4's post-walk fix: the parked hand-off (`PassphraseCache`) is taken **only** by the notebook
  screen's own open (`NotebookPassphrasePrompt.takeParked`, called from `NotebookActivity.keyFor`)
  and expires after `PassphraseCache.TTL_MS` = 60 s — see the standing trap below for what this
  replaced.
- The picker rule and the New Notebook radio row were both settled as phase-start answers rather
  than wizard decisions (recorded above under "Decisions").

## Standing traps

- **`sqlcipher_export` drops `PRAGMA user_version`.** Copy it by hand and re-verify from the
  finished file — og's own bricked-notebook history. `ExportKeying` already does this;
  `SoilRekey` inherits it through `exportAndKeyToPrimary`.
- **`PRAGMA rekey` is unreliable on device.** Export-and-key only, never rekey-in-place at the
  SQLite level.
- **A cached raw key can be stale** for a file this process has not itself opened via `KeyOpener` —
  verify against the file (`peekVerified`) and invalidate before any raw open, never trust a bare
  `peekOrLoad` on an open path.
- **Clearing only the Keystore leaks the RAM copy** for the process lifetime — `KeyMaterial.
  invalidate` always clears both (the Paper Phase-6 lesson).
- **A meta refresh must source scope from the index, never from the previous meta row** — the
  meta-refresh-wipe trap.
- **Backup stamps compare `updatedAt`**, which a rekey never bumps — a rotation or scope change
  that forgets to clear the stamps leaves old-key copies in every backup forever.
- **The index cannot be closed under a live screen.** After `SnIndex.closeForRotation()` the
  Encryption screen touches nothing but dialogs and leaves only through Bootstrap.
- **A walk that rotates or forgets without the key written down locks the dev library.** Every
  crypto walk starts at the Encryption screen's Reveal dialog.
- **The Nomad's dev library has real test data** (the tag/calendar/events fixtures from arcs
  21–24) — quarantine and Break-keying walks run on a throwaway notebook made for the walk, never
  the real fixtures.
- **Reveal → Copy puts the key on the system clipboard, and it survives a process kill** — the
  cheapest way to feed Unlock on a Supernote after a Forget, since `adb shell input text` is
  swallowed by the device's IME.
- **`adb shell input keyevent 67` (backspace) is swallowed too**, like `input text` — a walk can
  append to a field but never clear it.
- **The parked-value trap (U4's finding).** Before the fix, whichever prompt ran first —
  `NotebookPassphrasePrompt.ask` itself, an Export prompt, or a picker drill — could spend the
  parked hand-off silently, so the notebook screen's *next* open asked when the person expected it
  not to ("sometimes it asked, sometimes not"). The fix: only `takeParked`, called solely from the
  notebook screen's own open, ever reads `PassphraseCache`; every other prompt asks regardless of
  what is parked.
- **A warm queued before a rekey can land after its invalidate** and store a key for a salt that
  no longer applies — `KeyMaterial.generation` plus `KeyOpener.warm`'s `ifGeneration` guard is the
  fix; see "The raw-path audit" above.

## Debug tooling (`.dev` only)

`library/RekeyProbe`, wired into the debug menu, never a release entry point:

- **Rekey one notebook round-trip (debug)** — an alive notebook picked from the library:
  `integrity_check` + row count under the global key → rekey to a throwaway passphrase → verify
  the throwaway opens it and the global no longer does → rekey back → the same checks under the
  global again, raw key invalidated and re-warmed. Timings and PASS/FAIL in a copyable report.
- **Break a rekey commit (debug)** — reproduces a death **between the two renames** of
  `commitReplace` by hand (no `am force-stop` can time that window): variant A leaves a tmp that
  verifies under the global, variant B leaves garbage. The caller kills the process afterward; the
  next launch's `recoverGarden` puts it right.
- **Break keying (debug)** — `RekeyProbe.breakKeying`, arc 26 / U6: re-keys one notebook to
  `RekeyProbe.BROKEN_KEY` (the constant `"brokenkey1"`) while leaving the index's scope untouched —
  exactly the state a backup restored from before a rotation, or a file re-keyed elsewhere, would
  leave behind. For a `NOTEBOOK`-scope notebook the caller collects its current passphrase through
  the real prompt first (there is no session key to rekey from).

## Not built / future

- `SoilMigrator`'s plaintext branch — SN was born encrypted, so `probe == Plaintext` at boot stays
  `FOREIGN_FILE` rather than a conversion target.
- `abandonMarker` (dropping a half-done rotation without finishing it) — no UI for it in og either.
- A `SelfHealingKeyFactory` port — SN's verify-first `KeyOpener.roomFactoryFor` already covers the
  same ground without a heal-after-throw factory.
- A cloud-leg skip wording for a locked notebook with no key — U4's phase-start answer found no
  new wording was needed.
- Everything else tracked in `PARITY_BACKLOG.md` — item 1 (this arc) is now done; item 2 (restore)
  is separate and untouched here.

## Tests

1077 tests in `:app` as of U6 (0 SQLCipher-dependent — SQLCipher itself never runs on the JVM; the
transform steps are proven on the Nomad by the debug tools above).

| Test class | What it pins |
|---|---|
| `RekeyCommitTest` | `commitReplace`'s rename/fsync/delete order over `FakeRekeyFs`, which logs every op so the *order* is checked, not just the end state; all five `Outcome`s |
| `RekeyRecoveryTest` | `RekeyRecovery.decide`'s presence table and the executor's re-verify-before-delete rule |
| `PassphraseRulesTest` | length, mismatch, same-as-current, trimming |
| `PassphraseCacheTest` | single-use `takeOnce`, the 60 s TTL, `clear` |
| `RotationMarkerTest` | JSON encode/decode round-trip, `augmented`, `quarantine`, `without` |
| `RotationPlanTest` | id ordering (notebooks → stores → index), the `decide` / `afterFailure` outcome tables, `commitSteps`, `resumeCandidates` |
| `BootstrapRouteTest` | the three-way `afterOpen` decision and `carriesThenBackup` |
| `BackupStoreTest` | both stamp maps, `clearAllStamps` |
| `RawKeyDerivationTest` | the platform PBKDF2 path agrees byte-for-byte with the hand loop |
| `KeyResolverTest` | the full `decide` table over scope × cache × marker × unlocked |
| `KeyScopeTest` | `of(column)`'s null/legacy handling |
| `NotebookUnlocksTest` | mark/has/forget/clear |
| `ScopeChangeTest` | `scopeFor`'s downgrade rule, `route`'s four outcomes |
| `ImportChoiceTest` | the chooser's outcome table including both downgrade cases |
| `KeyFailureTest` | the classifier's chain order and a cause-cycle guard |
| `NotebookRecoveryTest` | `Plan.shouldOffer` / `silentCandidates` / `decide` |
| `ExportKeyingTest` | the shared export-and-key core `SoilRekey` builds on |
| `AttemptLimiterTest`, `GlobalKeyTest` | pre-existing — the lockout schedule and the Crockford mint/fold, unchanged by this arc |
