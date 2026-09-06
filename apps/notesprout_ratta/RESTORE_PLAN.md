# RESTORE_PLAN.md — Arc 27 "Restore" (Notesprout SN, branch `ratta`)

**Standalone plan for whole-library restore** — item 2 of `PARITY_BACKLOG.md`. This file is the
cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this
file is enough. `ENCRYPTION_PLAN.md` and `DRIVE_PLAN.md` are the shapes this file copies.

**Status:** wizard locked 2026-09-05 · Fable review folded in 2026-09-05 (R1–R7, § Review
amendments) · L1 ✅ · L2 ✅ · L3 ✅ · L4 ✅ · L5 ⬜ · L6 ⬜

**Phase letters:** every letter A–Z is spoken for in `RATTA_PLAN.md` except **H** and **L**. This
arc takes **L**; H stays free.

---

## What this arc is

SN backs up and cannot restore. The local SAF leg (arc 17 / K2) and the cloud leg (arc 25 / V4)
both write a complete, self-describing library — the index, every alive `.soil`, every
`Garden/<pkg>.db` extension store — and there is **no path of any kind to put one back**. A single
notebook returns through arc 16's Import, because every backup file is a self-describing `.soil`.
Nothing else does: the folder tree, the tags, the calendar and its events, the scratch pad's pages,
the proofread dictionary and the editor's prefs come back only by a hand copy over adb with the app
closed, which `docs/backup.md` § Extension stores documents and the monorepo `BACKLOG.md` defers.

This arc builds the inverse of the backup run: **choose a backup, prove it opens, replace the
library with it.** og Notesprout (`apps/notesprout_android` — reading reference, **no code
copied**) has `data/backup/RestoreEngine` + `RestoreSource` + `SafBackupReader`: staging-first,
validate, free-space gate, aside-swap with the index as commit marker, clear the keys, restart into
unlock, and a launch-time repair for a kill mid-commit. That skeleton is right and SN takes its
logic. Four things make SN's restore a different animal, and each is a decision below:

1. **The backup destination lives inside the thing being replaced.** SN's whole backup config —
   the SAF `treeUri`, `cloudEnabled`, `cloudDeviceFolder`, and both stamp maps — is one `backup`
   row in `notesprout.db`. A naive restore installs the *source device's* destination over this
   device's. That is the user's own incident (a BOOX backup restored onto a Supernote silently
   flipped the Supernote's backup folder to the BOOX's; several runs later the BOOX's backup had
   been overwritten by the Supernote's) — in SN it would also install a dead SAF grant and a
   foreign cloud device folder, and carry stamps describing files this device never wrote.
   **Decision 3** is the rule: reading a backup and writing one are two different questions, and
   the answer to the first must never silently answer the second.
2. **SN has extension stores; og has none.** Seven `Garden/<pkg>.db` files are part of the library
   and part of the swap.
3. **Arc 26 happened.** "Clear the keys" is now `PassphraseStore` + `KeySession` + `KeyMaterial` +
   `NotebookUnlocks` + `PassphraseCache` **and a rotation marker**, `NOTEBOOK`-scope notebooks
   exist, and `SoilRekey` leftovers can be sitting in a backup folder.
4. **The cloud leg already reads.** `ICloudStorage` has `list` and `download`, so a cloud restore
   costs no contract change.

**Not a point, not an `API_VERSION` bump, no extension touched, no `<queries>` change.** Host-only:
`data/backup/`, `restore/`, the Backup screen, the bootstrap. Version stays `0.1.0-ratta`.

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `SafBackupWriter` — the `.part`/`.old` swap, one listing per write, DocumentsContract by hand (no `androidx.documentfile`) | the write half only | L1 adds `SafBackupReader`, its read twin, in the same hand-rolled style |
| `SelfContainedSnapshot` — WAL absorbed into a cache copy so the cloud never holds a sidecar | write side | L4 relies on it being true: a **cloud** backup's mains are complete, so any `-wal` there is stale and is **never** fetched (R3); a **local** one is fetched with its main |
| `SoilCrypto.probe` / `SoilFileKind` | the one probe | L2's staged-set validation — `Encrypted` passes without a key, exactly og |
| `SoilRekey.recoverGarden` + `RekeyRecovery` (Bootstrap runs it after the index opens) | arc 26 / U2 | L2 adds `RestoreEngine.recoverInterrupted` **before** it in the same `boot()` — a restore's aside must settle before a rekey's leftovers are judged |
| `SnIndex.closeForRotation()` — the one door that closes the index | arc 26 / U3, rotation only | L2 is its **second** caller; its contract widens from "rotation only" to "rotation or restore", both ending in a relaunch |
| `ExtensionStores.closeAll()` / `checkpointIfOpen` | arc 26 / U2 + arc 17 | L2 closes every store before the swap — the host caches each store it opens for the life of the process and closes none |
| `SoilOpenFiles.isOpen` / `awaitClosed` | arc 15 | L2's pre-flight: a held `.soil` means refuse, never swap under a live writer |
| `AttemptLimiter(key)` — `GLOBAL` / `"IMPORT"` / notebook-id buckets | arc 26 / U4 | L2 adds a `"RESTORE"` bucket for decision 6's key prompt |
| `KeyOpener` / `KeyMaterial.peekVerified` / `RawKeyDerivation` (platform PBKDF2, `warm` serialized) | arc 26 / U6 | L2's staged-index test-open goes through the same door; **never** a hand HMAC loop |
| `CloudClient.list` / `.download` + `CloudTimeouts` + `CloudBrowserDialog` | arc 25 / V3–V5 | L4 consumes them unchanged; `CloudBrowserRules` is the precedent for the pure listing rules |
| `BackupStore` / `BackupConfig` (the `backup` index row) | arc 17 / K2, grown V4 + U3 | L2 reads this device's destination fields out **before** the swap and writes them back after |
| `BootstrapActivity.relaunchIntent` + `BootstrapRoute.afterOpen` | arc 26 / U3 | L2/L3 end a restore the same way a rotation does — a clean task rooted at Bootstrap |
| Arc 16 Import (probe → unlock → placement → keying) | the single-notebook door | unchanged; restore is explicitly **not** an import and never reuses its pipeline |

## Decisions (wizard 2026-09-05 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Sources | **Both legs.** Local SAF **and** cloud, one engine behind two `RestoreSource`s. A device that only ever backed up to the cloud must be recoverable in-app — that is the data-loss case this backlog item exists for. No contract change: `ICloudStorage` already has `list` + `download`. |
| 2 | Replace mode | **Replace all — og's shape.** The whole library is swapped: index, every notebook, every extension store. Not a merge, not a per-notebook import (that is Import's job, and it already works from a backup file). No selective mode, no keep-my-stores mode — the restored index's ids would no longer match the tag assignments, calendar rows and scratch pages naming them. |
| 3 | The backup destination | **Device-local — never restored.** This device's `treeUri`, `cloudEnabled` and `cloudDeviceFolder` are read out before the swap, parked device-locally, and re-applied on the first successful open after the relaunch. The backup's own destination fields are **always** discarded, whether or not this device has one configured — a restore never *sets* a destination. **Both stamp maps are cleared.** The user's directive, made whole: "restoring from a folder must never rewrite the configured backup destination; restore-from-anywhere stays allowed." |
| 4 | The restored cloud account | **Restored like any other store — change nothing.** `Garden/<ext-cloud pkg>.db` comes back with the rest and the device comes up already connected. The host does **not** reach into `:ext-cloud`'s `account` table (`EditorSchema.prefs` stays the ONE extension table the host reads) and does not special-case a store by package name. The shared-refresh-token consequence — `disconnect` on the restored device revokes for the source device too — is documented in the failure table and `docs/cloud.md`, not fixed by crossing the seam. Uploads cannot collide, because decision 3 already cleared `cloudDeviceFolder`. |
| 5 | Undo | **None — og's shape.** The replaced library is discarded the moment the restored index is installed. No aside kept past commit, no "put my previous library back", no parked previous passphrase. This is what makes decision 6 load-bearing. |
| 6 | Prove the key before committing | **Yes — the staged index must open first.** After staging: try this device's cached global key silently (a same-device backup just works, no prompt); if it fails, prompt for the backup's recovery key under `AttemptLimiter("RESTORE")`. **No key that opens the staged index means no commit and nothing live touched.** The proven key is installed as the global passphrase during the commit, so the relaunch lands in the library, not at the unlock gate. Proves the **index** only — a `NOTEBOOK`-scope notebook inside still prompts on open exactly as it does today, and `NotebookRecovery` is still the way back. |
| 7 | The door | **One: a row on the Backup screen opening its own `RestoreActivity`.** `BackupActivity` is 711 lines against the ~800 rule and restore needs six steps of its own. **Not** on the Unlock screen (considered and declined), **not** in the library overflow, **not** on the library bottom bar (the row is full). |
| 8 | Phases / review | **Six, L1–L6, this standalone `RESTORE_PLAN.md`. No `/code-review` anywhere in the arc** — L5 is a **hardening / failure-injection pass** on the Nomad instead, and L6 is docs-and-freeze with no code. Fable reads every phase's code before its walk (the model recipe). |
| 9 | Walk data | **Rotate to make a stranger — Nomad only.** A foreign backup is produced with the app's own machinery: back up a throwaway library, run `GlobalRotation`, then restore the pre-rotation backup — a library under a key the device no longer holds. Rename the cloud device folder first when a foreign `cloudDeviceFolder` is wanted. **No Manta**, and the standing Nomad-only rule holds. **Every restore walk is driven by Fable by hand, step by step** (arc 26's rule for anything where a wrong step locks the library; here a wrong tap wipes the dev library outright). |
| 10 | App version | Stays `0.1.0-ratta` (a phase-start question, as always). |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **A restore is refused while a rotation marker exists.** The marker names pending file ids the
  restore is about to delete. `GlobalRotation.hasMarker` → the Restore row explains and points at
  the Encryption screen's resume banner. Symmetrically, the marker is cleared as part of the
  commit's key-state reset, because the library it described is gone.
- **A restore is refused while any `.soil` is held open** (`SoilOpenFiles`) — structurally
  unreachable from the Backup screen, kept as the backup engine keeps it.
- **The backup list shows name, notebook count and the index's last-modified time.** Enough to tell
  two backups apart without opening either.
- **A successful restore ends in a report dialog with one non-cancelable action, Restart**, which
  starts `BootstrapActivity.relaunchIntent` and `finishAffinity()`s — the rotation's ending.

---

## Design (binding unless a phase-start question reopens it)

### D1 — What a backup folder legitimately contains (`restore/RestoreManifest`, L1, pure)

A backup folder is not a curated set — it is whatever the writer left, plus whatever a killed run
stranded. The manifest rules are pure and JVM-tested, and they are the only thing that decides what
gets staged:

- **Taken:** `notesprout.db` (required — its presence is what makes a folder *a backup*), its
  `notesprout.db-wal`, `<uuid>.soil` + `<uuid>.soil-wal`, `<pkg>.db` + `<pkg>.db-wal` where `pkg`
  passes `isValidExtensionPackage` (`extensionStorePackage` is the existing authority — reuse it,
  do not re-derive).
- **Never taken:** `*.part` and `*.old` (a killed `SafBackupWriter` swap), `*.rekey.tmp` and
  `*.old.bak` (an arc-26 `SoilRekey` commit interrupted **on the source device** and copied by a
  later backup run), any `-shm` (rebuilt on open, never copied by the writer either), any
  directory, anything else.
- **The WAL rule, read side — local leg only.** A `-wal` is taken **only** with its main file, and
  both must land or the whole fetch fails. A `.soil` staged without its backed-up `-wal` is a silent
  loss of the writes in it; a `-wal` staged without its `.soil` is meaningless. Never one, never
  neither-when-the-backup-has-both.
- **The cloud leg never takes a `-wal` (R3).** `SelfContainedSnapshot` makes every uploaded main
  file complete, so any `-wal` sitting in a cloud device folder is **stale by construction** — left
  by a stale-sidecar delete that failed (`CloudBackupLeg` guards exactly this). Pairing it with a
  fresh main file is the corruption that guard exists to prevent; the read side must not undo it.
  The manifest takes a `leg` parameter and the WAL rule is skipped-and-ignored for `CLOUD`.
- **`dev/`.** Debug builds back up into a `dev/` subfolder. Enumeration is therefore **one level
  deep**: the picked tree counts as a backup if it directly holds `notesprout.db`, and each
  immediate subfolder that holds one counts too. og's rule, and it is also what lets a user pick a
  parent holding several devices' folders.

### D2 — Staging (`restore/RestoreStaging`, L1)

**`getExternalFilesDir(null)/restore_staging/` — the library's own volume, a sibling of `Garden/`
(R1; og stages in `cacheDir` and copies, and the plan first copied og).** The index and `Garden/`
both live under `getExternalFilesDir(null)`, so staging beside them makes the commit **renames
only**: peak disk drops from old + staged + new-copy to old + staged, the kill window shrinks from a
multi-hundred-MB copy to milliseconds, D5 gets simpler, and the free-space gate measures the one
volume everything sits on. Nothing enumerates that directory (`extensionStoreFiles` and
`recoverGarden` read `Garden/` only), so a leftover is invisible to the library. Layout mirrors the
live one: `restore_staging/notesprout.db` (+ `-wal`) and `restore_staging/Garden/…`.

Wiped and recreated at the top of every attempt. Every file streams to a `.part` name and renames
on completion, so a dropped read never leaves a truncated file under a name the commit would
install. **Every per-file result is checked and any single failure aborts the whole fetch** — a
silently short staging set would commit as the entire library. Progress is `(done, total)` across
the manifest. The live library is untouched by anything in this step.

**Free space is gated before the first byte is fetched (R1).** Both listings carry sizes for free —
`CloudEntry.sizeBytes`, and `COLUMN_SIZE` in the one SAF listing — so `RestoreBackup` carries a
`totalBytes` and the pre-flight refuses when `totalBytes + HEADROOM` exceeds the volume's usable
space. The post-stage check (D3 step 6) is the honest re-measure.

### D3 — The commit (`restore/RestoreEngine`, L2)

og's order, with SN's additions and the review's reshaping (R1, R2, R4, R7). Steps 1–7 touch
nothing live; the point of no return is 8.

1. **Pre-flight.** No rotation marker; no `.soil` held open; a destination the source can still
   reach; the listing's `totalBytes` + headroom fits the library volume (D2). Refuse with a named
   `Problem`, nothing staged.
2. **Stage** (D2).
3. **Validate.** `SoilCrypto.probe` every staged `.soil`, every staged store and the staged index —
   `Invalid` fails the restore by name. `Encrypted` passes; nothing is read deeper without a key.
4. **Prove the key** (decision 6). Test-open the *staged* index: cached global first, silently;
   then the prompt, under `AttemptLimiter("RESTORE")`. **The prompt accepts a typed passphrase
   (R6)** — arc 26 lets a library carry a typed global, the Nomad's dev library does — so it verifies
   the text **as typed first, then `GlobalKey.normalize`d** exactly as `UnlockActivity` does, and
   its wording says "passphrase or recovery key", never just "recovery key". Opens or the restore
   stops here, live library untouched. The proven passphrase is held in memory for step 9 —
   **never** logged, never in an Intent, never written outside `PassphraseStore`.
5. **Read out this device's destination** (decision 3) — `treeUri`, `cloudEnabled`,
   `cloudDeviceFolder` — from `BackupStore` while the index is still open, and park them
   device-locally (`SecurePrefs`, a `restore_pending_destination` blob; not a secret, but it rides
   the store that already survives the swap).
6. **Free-space re-check.** Staged bytes already sit on the volume, so what remains to fit is only
   the headroom (64 MB, an L2 question) — a hard fail naming the shortfall if even that is short.
7. **Blind the process (R2).** `KeySession.clear()` **before** anything closes. `ExtensionStores.open`
   *creates* an empty store when the file is missing, and the cloud leg's downloads handed `:ext-cloud`
   its `IExtensionStore` binder — that process can call back after the fetch, and the swap window
   below is exactly when the Garden is absent. With no key in session every such call throws
   `SoilLockedException` instead of minting a store the install would collide with. The session is
   set again only in step 9, after the index is installed; a rollback re-sets the **old** passphrase.
8. **Close and swap — renames only (R1).** `ExtensionStores.closeAll()` → `SnIndex.checkpoint()` →
   `SnIndex.closeForRotation()` → aside in this order: **(a)** live `notesprout.db` + its sidecars
   into `restore_replaced/`, **(b)** live `Garden/` into `restore_replaced/Garden`; then install:
   **(c)** staged `Garden/` renamed to live, **(d)** staged index sidecar (if any) renamed beside,
   **(e)** staged `notesprout.db` renamed **last**. **The installed index is the commit marker**, and
   every step is a same-volume `rename` — nothing is copied, nothing is `.part` at this point.
9. **Key state.** `PassphraseStore.setGlobalPassphrase(proven)`, **`setRecoveryKeyAcknowledged`
   (R7 — set, unconditionally; see below)**, `KeyMaterial.clearAll`, `KeySession.set(proven)`,
   `NotebookUnlocks.clear()`, `PassphraseCache.clear()`, `PassphraseStore.clearRotationMarker()`.
10. **Discard the aside** (decision 5) and the staging dir.
11. **Relaunch.** Report dialog → Restart → `BootstrapActivity.relaunchIntent(thenBackup = false)` +
    `finishAffinity()`.

**Failure inside step 8 renames the aside back and relaunches (R4)** — it does **not** reopen the
index in place. `SnIndex` has no reopen door after `closeForRotation` except `ensureReady`, which
is Bootstrap's alone; a rotation already ends every path in the Bootstrap relaunch and `IndexGuard`
already bounces every other screen there. The rollback is D5's rule run in-process (per-item,
idempotent), then the old passphrase back into the session, then a report dialog naming the failure
with the one action Restart. One ending path, two outcomes.

**The acknowledgement question (R7).** The restored library's recovery key is the source device's,
and the user just typed it (or it was already this device's cached global). Showing
`RecoveryKeyActivity` after a restore would present a key the user demonstrably already has, so the
commit **sets the acknowledgement unconditionally** — it is either already set (cached-global case)
or must be set now (prompt case). A restore never routes to the recovery-key screen; it must be set
before the relaunch or `BootstrapRoute.afterOpen` sends them there.

### D4 — Destination carry-over (`restore/RestoreDestination`, L2, pure decision + a tiny store)

Parked at step 6, applied on the **first successful index open after the relaunch** — the index is
encrypted under the restored library's key and cannot be written before then. `BootstrapActivity`,
right after `SnIndex.ensureReady` answers READY/FIRST_LAUNCH and before `forwardAfterOpen`, applies
any parked destination and clears the park. Idempotent, and a park that survives a second restore
is simply overwritten.

The pure rule (`RestoreDestination.merge(restored, parked)`), JVM-tested:

| Field | Result |
|---|---|
| `treeUri` | **this device's** (`parked`), or null if it had none — never the restored value |
| `cloudEnabled` | **this device's** |
| `cloudDeviceFolder` | **this device's**, or null → the Backup screen mints a fresh one on its next render |
| `stamps` / `cloudStamps` | **empty**, both |
| `lastRunAt` / `lastCopied` / `lastSkipped` / their cloud twins | **cleared** — this device has never backed up this library |
| everything else | the restored value |

### D5 — Interrupted-commit recovery (`RestoreEngine.recoverInterrupted`, L2)

Launch-time repair, called from `BootstrapActivity.boot()` **first** — before `SnIndex.ensureReady`
(which would otherwise treat a missing index as a fresh install, or judge rekey leftovers over a
half-swapped Garden) and therefore before `SoilRekey.recoverGarden`. The installed index is the
commit marker. The aside is built by two renames and installed by three (D3 step 8), so the repair
is **per-item and idempotent (R5)** rather than a two-branch table:

- **Live index present** → the commit finished. Whatever sits in `restore_replaced/` is the replaced
  library: delete it whole. Delete `restore_staging/` too (its files were renamed out; anything left
  is a partial of nothing).
- **Live index absent, aside index present** → the swap did not complete. First, if **both** a live
  `Garden/` and `restore_replaced/Garden` exist, the live one is the **new** Garden renamed in at
  step 8(c) — delete it. Then rename every aside item back (Garden, index sidecars, index). Delete
  `restore_staging/`. The old library is whole again.
- **Live index absent, aside index absent** → nothing of a restore is in flight (a fresh install, or
  a rekey leftover for `ensureReady` to judge). Touch nothing except a stray `restore_staging/`.
- `restore_replaced/` present with **only** a Garden (killed between 8(a) and 8(b) is impossible in
  that order; between 8(b) and 8(c) leaves aside = index + Garden) — covered by the second rule,
  which keys on the aside **index**, the first thing moved aside and the last thing moved back.

The pure decision (`RestoreRecovery.plan(liveIndex, asideIndex, liveGarden, asideGarden)` → an
ordered list of `Delete` / `RenameBack` actions) is JVM-tested against every combination.

### D6 — The sources (`restore/RestoreSource`, L1 + L4)

One interface, two implementations, and the engine knows neither:

```
interface RestoreSource {
    suspend fun listBackups(): List<RestoreBackup>          // name, notebook count, index mtime
    suspend fun fetchInto(index: Int, staging: File, onProgress: ...): RestoreManifest
}
```

- **`SafRestoreSource`** (L1) over platform `DocumentsContract`, hand-rolled in `SafBackupWriter`'s
  style — `androidx.documentfile` is not on the classpath and the no-new-dependencies rule stands.
  **One directory listing serves the whole enumeration**, the writer's own K3 lesson. A restore
  takes a **read** grant on the picked tree and **never** persists it — persisting would be a
  destination, and decision 3 forbids a restore setting one.
- **`CloudRestoreSource`** (L4) over `CloudClient.list` / `.download` under `CloudTimeouts`.
  `list(["Backups"])` gives the device folders; one `list` per folder gives its contents, and the
  leg pays one `list` for the chosen folder at fetch time — a `list` costs most of a second on this
  seam. Downloads are `download` into the staging `.part`. The four typed failures map exactly as
  `CloudBackupLeg`'s do (`NOT_CONNECTED` / `NETWORK` / no-answer / `CLOUD_GONE`), and **a mid-fetch
  failure aborts the whole restore** — there is no partial staging.

---

## Phases

### ✅ L1 — the read side: manifest, sources, staging

Pure rules + the SAF source + staging. **No UI, no engine, nothing live is touched by any code in
this phase.**

- `restore/RestoreManifest` (D1) — pure, exhaustively JVM-tested against the real filename shapes:
  `.part`, `.old`, `.rekey.tmp`, `.old.bak`, `-shm`, a store whose stem fails
  `isValidExtensionPackage`, a `-wal` with no main file, a main file with no `-wal`, and the same
  folder under `leg = CLOUD` (every `-wal` ignored).
- `restore/RestoreSource` + `RestoreBackup` (D6).
- `data/backup/SafBackupReader` — the writer's read twin, hand-rolled `DocumentsContract`, one
  listing per enumeration, `.part`+rename copies.
- `SafRestoreSource` — one-level-deep enumeration (D1), notebook counts, index mtime.
- `restore/RestoreStaging` (D2) — on the library volume, beside `Garden/`; `RestoreBackup.totalBytes`.

**Questions to resolve at phase start:** app version. *(Resolved by the review: `RestoreBackup`
**does** carry `totalBytes` — `CloudEntry.sizeBytes` and the SAF listing's `COLUMN_SIZE` both come
free with the one listing — so L3 can show "N notebooks · 412 MB" and the pre-flight gates free
space before fetching, D2/R1.)*

### ✅ L2 — the commit engine

The dangerous half, and the one Fable writes.

- `restore/RestoreEngine` (D3) — `Result`/`Problem` types, never throws past its top-level catch;
  the swap is renames only, the session is cleared before it, a failed swap rolls back and relaunches.
- `restore/RestoreRecovery` (D5) — the pure per-item plan; `recoverInterrupted` executes it.
- `restore/RestoreDestination` (D4) — the pure merge + the `SecurePrefs` park; applied in
  `BootstrapActivity`.
- `RestoreEngine.recoverInterrupted` (D5), wired into `boot()` ahead of `recoverGarden`.
- `SnIndex.closeForRotation`'s contract widened to "rotation or restore" (doc comment + the
  `IndexGuard` note); `AttemptLimiter` `"RESTORE"` bucket.
- JVM tests for every pure part: the merge table, the manifest→plan (both legs — the cloud leg
  takes no `-wal`), the free-space arithmetic, the recovery plan over every live/aside combination.

**Questions to resolve at phase start:** whether the free-space headroom stays og's 64 MB · whether
a `.soil` that fails its probe fails the whole restore (og) or is skipped-and-named (the backup
engine's "copy it as the bytes it is" instinct points the other way; **default: fail**, because a
restore installs a library and a bad notebook in it is a library that lies) · where the parked
destination blob lives if not `SecurePrefs`. **Answered 2026-09-05: 64 MB stays · fail the whole
restore · `SecurePrefs` (the `sn_secure` file, key `restore_pending_destination`) · version stays
`0.1.0-ratta`.** *(Settled by the review, not to be re-asked: staging
on the library volume + rename-only commit (R1); session cleared before the swap (R2); rollback
relaunches rather than reopening (R4); per-item recovery (R5); typed-then-normalized prompt (R6);
acknowledgement set unconditionally (R7).)*

### ✅ L3 — the screen, the Backup row, and the local walk

- `restore/RestoreActivity` — source pick → backup list (name · N notebooks · date) → confirm
  ("Replace your library?", naming the backup, warning that the backup's own recovery key will be
  needed and that **the current library will be gone**) → key prompt when the cached key does not
  open it → non-cancelable progress → report → Restart.
- The Backup screen's `Restore from a backup…` row, under the Cloud section; refused-with-a-reason
  while a rotation marker stands.
- Every string; every outcome a dialog, never a toast (the standing rule, and this screen exists to
  answer "did it work").
- **The walk (Fable, by hand, Nomad):** build a throwaway library → back it up locally →
  `GlobalRotation` to mint a new key → restore the pre-rotation backup → the key prompt appears →
  the wrong key is refused and the limiter bites → the right key commits → relaunch lands in the
  **library** (not Unlock) → the library is the backup's → **the Backup screen still names this
  device's folder** (decision 3, the whole point) → a second "Back up now" copies everything.

**Questions to resolve at phase start:** whether the source pick is a dialog or two rows on the
Restore screen · progress granularity (per file vs. per phase) · whether the report names the
extension stores separately, as the backup report does (W5's call). **Answered 2026-09-05: two rows on the
Restore screen (the cloud row in the layout, `GONE` until L4) · one non-cancelable dialog, per phase
with a per-file counter during staging · the report names the stores separately · version stays
`0.1.0-ratta`.**

### ✅ L4 — the cloud source and its walk

- `restore/CloudRestoreSource` (D6) — `Backups/` enumeration, per-folder listing, `download` into
  staging, the four typed failures.
- The Restore screen's second source row, `GONE` (never disabled) while no trusted provider is
  installed — the Backup screen's Cloud section rule.
- **The walk (Fable, by hand, Nomad):** the same rotation recipe, cloud leg — including a **renamed
  cloud device folder** before the backup so the restored config carries a foreign one and decision
  3 is visibly exercised; a mid-fetch disconnect must abort with nothing live touched.

**Questions to resolve at phase start:** the download timeout budget (reuse
`CloudTimeouts.uploadBudgetMs(length)` or a read-side twin) · whether a cloud restore refuses
outright below some free-space margin before it starts downloading. **Answered 2026-09-05: a
read-side twin, `CloudTimeouts.downloadBudgetMs(bytes)` — 120 s flat to 20 MiB, then 120 s per
20 MiB slice rounded up, pure + JVM-tested (the flat `DOWNLOAD_MS` stays for Import) · the same
gate as the local leg — L2's preflight already refuses on listing bytes + 64 MB before the first
download, no cloud-only margin · version stays `0.1.0-ratta`.**

### ⬜ L5 — hardening: failure injection on the Nomad

**No `/code-review` in this arc** (decision 8). Instead, break it on purpose and fix what falls out.
A debug-menu `RestoreProbe` is the door, in the `RekeyProbe` shape. At minimum:

- kill the process mid-commit, at each of D3 step 8's seams: after 8(a), after 8(b), after 8(c),
  after 8(e) and before the key reset (step 9). D5's per-item plan proved live on every one.
- an extension call into its store during the swap window (a debug `RestoreProbe` hook that binds
  `:ext-cloud`'s store between 8(a) and 8(e)) — must be refused, never create a store (R2).
- a swap failure injected at 8(c) (a file planted at the live `Garden/` name) — the aside renames
  back, the old session is restored, the relaunch lands in the old library untouched (R4).
- a full disk at the free-space gate, and a disk that fills *during* the Garden copy.
- a torn staging set (delete a staged `.soil` between validate and commit).
- a backup folder salted with `.part`, `.old`, `.rekey.tmp`, `.old.bak`, a stray `-shm` and a
  foreign `.db` — none of them may be staged.
- a `.soil` + `-wal` pair where only one side is present in the backup (local leg); a stale `-wal`
  planted in a cloud device folder — never fetched (R3).
- the wrong recovery key, three times, into the limiter's lockout.
- a restore attempted while a rotation marker stands.
- a `NOTEBOOK`-scope notebook in the backup: it restores, shows a lock card, and prompts on open.
- the destination trap itself, twice: with a local folder configured and with a cloud folder
  configured, both foreign in the backup.

**Questions to resolve at phase start:** which injections are worth a permanent debug-menu entry
versus a one-off adb setup · whether L5's fixes may reshape L2's public surface or must stay local.

### ⬜ L6 — docs + freeze

**No code.** `docs/restore.md` written whole (the model, the manifest rules, the commit order, the
destination rule and *why*, the failure table, the measured Nomad numbers, the design calls, the
traps, the debug tooling, futures, tests). Pointer sections in `docs/backup.md` (its "no restore"
sections replaced), `docs/cloud.md` (the restore source + the shared-token consequence),
`docs/encryption.md` (restore's key handling and the marker refusal), `docs/import.md` (restore is
not an import), `docs/extensions.md` (stores travel with the swap). Both `CLAUDE.md`s; `RATTA_PLAN.md`
header; `PARITY_BACKLOG.md` item 2 → **DONE**; monorepo `BACKLOG.md`'s W5 "a restore screen" item
closed. Arc marked COMPLETE + FROZEN here.

---

## Review amendments (Fable pass, 2026-09-05 — folded into D1–D5 above; recorded so the *why* survives)

The plan was drafted by Opus and reviewed by Fable against the code before L1. Every seam it leans
on was confirmed (per-file SQLCipher salts make a foreign index open under the source passphrase;
`cloudDeviceFolder` is minted lazily by both the Backup screen and `CloudBackupLeg`; `CloudEntry`
carries `sizeBytes` + `modifiedAt`; `BootstrapRoute` does route an unacknowledged key to
`RecoveryKeyActivity`). Seven things changed:

| # | Amendment | Why |
|---|---|---|
| R1 | Stage on the library volume (`getExternalFilesDir(null)/restore_staging/`), commit by **rename only**; gate free space from the listing's sizes **before** fetching | og stages in `cacheDir` and copies; index and Garden share one volume, so the copy bought nothing but a 3× disk peak and a long kill window |
| R2 | `KeySession.clear()` **before** `closeAll` + the swap; session set again only after the index is installed | `ExtensionStores.open` creates an empty store when the file is missing; `:ext-cloud` holds its store binder after the download and can call back during the window when the Garden is aside |
| R3 | The cloud leg **never** fetches a `-wal` | `SelfContainedSnapshot` makes every cloud main complete; a `-wal` there is a failed stale-sidecar delete, and pairing it with a fresh main is the corruption the backup leg guards against |
| R4 | A failed swap renames back and **relaunches**; no in-place reopen | `SnIndex` has no reopen door but `ensureReady` (Bootstrap-only); rotation already ends in the relaunch and `IndexGuard` already covers it |
| R5 | `recoverInterrupted` is a per-item idempotent plan, not a two-branch table | the aside is two renames and the install three; a kill between any two leaves a state the two branches did not name (a new partial Garden beside an aside one) |
| R6 | The key prompt verifies **as typed, then normalized**, and says "passphrase or recovery key" | arc 26 allows a typed global (the Nomad's dev library has one); `GlobalKey.normalize` would corrupt it |
| R7 | The acknowledgement is **set unconditionally** at commit, before the relaunch | the draft's step 8 said "clear if never set" against its own paragraph saying "leave set / set"; an unset flag routes the relaunch to the recovery-key screen |

## Standing traps that bind this arc

From `RATTA_PLAN.md` — assume they all still apply.

- **A SAF pick cannot be driven by adb.** Every restore walk begins with a folder pick, so the walk
  is a **user checklist item** up to the picker; the agent-or-hand driving resumes after it.
- **`adb push` into `Android/data/<pkg>/files/` deletes the target** — push to `/data/local/tmp`,
  then `shell cp`. This is how a salted backup folder gets built for L5.
- **The Nomad sleeps behind a six-digit PIN** — check `dumpsys window | grep mCurrentFocus` before
  believing any screencap.
- **Walk-agent false failures are the most-fired trap.** Every restore walk is Fable by hand
  (decision 9); re-drive any FAIL before believing it.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **`RawKeyDerivation` stays on the platform PBKDF2 and `KeyOpener.warm` stays serialized** (arc 26
  / U3's Scudo OOM). A restore does a burst of cold opens; do not put the hand HMAC loop back.
- **No file over ~800 lines without a written reason** — which is why `RestoreActivity` is its own
  screen and `RestoreEngine` is separate from its sources.
- **Supernote swallows `adb shell input text`** — the recovery-key prompt is typed by tapping the
  on-screen keyboard, or by clipboard paste (the arc-26 trick, in the memory file).

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

1. **One phase per session.** At phase start read this file, root `CLAUDE.md`, and
   `apps/notesprout_ratta/CLAUDE.md`. Confirm the next ⬜ phase with the user, flip it to 🔄, then
   ask that phase's **Questions to resolve at phase start** wizard-style, one at a time, before
   writing code.
2. **Model recipe:** Fable plans and writes the genuinely dangerous code (L2 entire, the commit
   order, the key handling) and drives every walk by hand; Opus for substantial feature work
   (L1, L3, L4); Sonnet for scaffolding, layouts, resources, docs; ≤ 5 concurrent background agents.
3. **Testing gate:** JVM unit tests for all pure logic. The user gets a **short numbered checklist**
   for what only a human can do — every SAF pick, and the eye on the restored library.
4. **Devices:** Nomad only (SNN `SN078D10012852`). No Manta (decision 9).
5. **Commit + push only when all tests pass or the user gives the all-clear**, and only after docs /
   memory / `CLAUDE.md` updates are in. Then the user runs `/clear`.
6. **Status markers:** ⬜ Not started · 🔄 In progress · 🧪 Awaiting device verification ·
   ✅ Complete (commit `<hash>`). Every phase records an **Outcome** note when it closes.

---

## Ledger

*(Each phase appends its Outcome here as it closes.)*

### L1 — Outcome (2026-09-05, Opus build, Fable read + fixed)

**Landed, no code touches anything live.** New package `restore/`: `RestoreLeg` (LOCAL/CLOUD),
`RestoreManifest` (D1 pure — `Listed` → `Item{name,size,kind,relativePath}`; refused-by-suffix first
(`.part` `.old` `.rekey.tmp` `.old.bak` `-shm` `-journal`), index matched before the store rule,
`.soil` stem `[A-Za-z0-9_-]+`, store stem via `extensionStorePackage`; a `-wal` kept only with its
main in the same listing, every `-wal` dropped for CLOUD; order index → soils → stores, each main
then its wal; `totalBytes` counts an unreported size as 0 and flags `hasUnknownSizes`),
`RestoreBackup{name, notebookCount, indexModifiedAt, totalBytes, handle}` (handle = source-private
document URI string, never shown or logged), `RestoreSource` + `RestoreProblem`
(SourceUnreachable / ListingFailed / NotABackup / FetchFailed(fileName); L4 adds the cloud kinds) +
`ListResult` / `FetchResult`, `RestoreStaging` (D2 — `getExternalFilesDir(null)/restore_staging/`
with `Garden/` inside, `File`-rooted functions + `Context` overloads, `.part`-then-rename
`writeStaged` checking both the streamed count and the landed length, `targetFor` refuses a path
that canonicalises out of staging, `fits(total, usable, headroom = 64 MB)` pure with unknown = refuse,
`usableBytes` via `StatFs` → -1 unknown), `SafRestoreSource` (one-level-deep enumeration, root
named by the tree's display name, subfolders by name, an unreadable subfolder skipped not fatal;
fetch **re-lists and re-plans** at fetch time, aborts on the first failed file, progress
`(done, total)`). `data/backup/SafBackupReader` — the writer's read twin: `root` / `rootName` /
`list` (+ `COLUMN_LAST_MODIFIED`) / `open`, no create/rename/delete, **never
`takePersistableUriPermission`**.

**One deliberate deviation from D6's sketch:** `fetchInto` takes the `RestoreBackup` itself, not an
`index: Int` into the last listing — the handle rides the row, so a stale list position can never
name the wrong folder. Fable's read fixed one thing: the `Context` overloads built a `File("")` when
`getExternalFilesDir(null)` was null; now `checkNotNull`, the `gardenDir` shape.

**Tests:** 35 new (`RestoreManifestTest` 21, `RestoreStagingTest` 14) — 1077 → **1112**, 0 failures.
No device walk (nothing to see on a device yet); L3's walk exercises this code end to end.
Version `0.1.0-ratta` (confirmed at phase start).

### L2 — Outcome (2026-09-05, Fable build)

**Landed — the commit engine, the recovery plan, the destination carry-over; nothing in the app
calls `commit` yet (that is L3's screen).** New in `restore/`:

- **`RestoreEngine`** (D3) — five doors the screen walks in order, each catching at its top and
  answering a `Problem`, never throwing: `preflight` (rotation marker → `RotationPending`; any
  claimed `.soil` → `NotebookHeld` via the new `SoilOpenFiles.anyOpen()`; listing bytes + 64 MB
  headroom vs `StatFs` → `NotEnoughSpace(shortfall)`, pure `spaceProblem`), `stage` (resets staging,
  hands the source its fetch, discards on failure), `validate` (pure `validationProblem` over a
  probe: every `INDEX`/`SOIL`/`STORE` must exist, weigh what the listing said, and probe
  **`Encrypted`** — `Plaintext` refused too, SN has no plaintext mode; WAL items present, never
  probed; the first bad file is named, in manifest order — **fail whole, the phase-start answer**),
  `proveCached` (this device's cached global against the *staged* index via
  `SoilCrypto.verifyPassphrase`, one platform KDF, silent) / `proveTyped` (as typed, then
  `GlobalKey.normalize`d only when that differs — R6 — recording success/failure in the new
  `AttemptLimiter.RESTORE_KEY` bucket; the screen checks the lockout before prompting), and
  `commit` (whole under `NonCancellable` on IO): step 0 re-checks the staged set for a tear (the
  index exempt from the size rule — the proof opened it and SQLite's close checkpoints its WAL
  into it), the marker/held rules re-checked at the last moment, this device's destination read from
  `BackupStore` and parked (`ParkFailed` refuses), the headroom re-measured, then
  `KeySession.clear()` **before** `ExtensionStores.closeAll()` + `SnIndex.closeForRotation()` (R2),
  then the swap by `RealRekeyFs.rename` only — (a) live index + every sidecar → `restore_replaced/`,
  (b) live `Garden/` → aside, (c) staged `Garden/` → live, (d) staged index `-wal` beside the live
  name (a `-shm` deleted, never moved), (e) staged index → live **last**, a directory fsync after
  each group — then key state (`setGlobalPassphrase(proven)`, `setRecoveryKeyAcknowledged`
  unconditionally — R7, `KeyMaterial.clearAll`, `KeySession.set`, `NotebookUnlocks.clear`,
  `PassphraseCache.clear`, `clearRotationMarker`), then the aside and staging discarded (decision
  5). **A rename failing at any of a–e runs D5's plan in-process** (the aside back, the staged
  Garden deleted when both exist), un-parks, puts the old passphrase back in the session and answers
  `RolledBack(SwapFailed(step))`; a refusal after the park un-parks too, so a park never outlives
  the restore that wrote it. The caller relaunches on every `Outcome` (R4).
- **`RestoreRecovery`** (D5 / R5) — pure `plan(State(liveIndex, asideIndex, liveGarden, asideGarden,
  asideSidecars))` → ordered `DeleteAside` / `DeleteStaging` / `DeleteLiveGarden` / `RenameBack(name)`.
  Live index present → the commit finished, delete the aside and staging. Live absent + aside
  present → delete the live Garden only when **both** exist (it is the staged one from 8c), then
  rename back Garden, the sidecars, and the index **last**. Neither → only a stray staging dir; an
  aside Garden with no index (impossible by the commit's order) is left for a person, never deleted.
  `RestoreEngine.recoverInterrupted` executes it — **the first line of `BootstrapActivity.boot()`**,
  ahead of `ensureReady` and so of `recoverGarden`; two `exists` stats on the ordinary launch. The
  same executor is the in-process rollback.
- **`RestoreDestination`** (D4) — pure `merge(restored, parked)`: this device's `treeUri` /
  `cloudEnabled` / `cloudDeviceFolder` (or null/false when it had none — **always**, a restore never
  sets a destination), both stamp maps and all six last-run figures cleared, everything else the
  restored value; idempotent. The park is one `kotlinx` JSON blob in `SecurePrefs` (`sn_secure`,
  `restore_pending_destination`, written with `commit()`); `applyParked` — write the merge first,
  clear the park second — runs in Bootstrap right after READY/FIRST_LAUNCH and **also in
  `UnlockActivity`'s success path**, for the one kill (after 8e, before the key step) whose relaunch
  lands at Unlock instead of the library.
- `SnIndex.closeForRotation`'s contract widened to "rotation or restore" (doc), `IndexGuard`'s note
  names both closers, `AttemptLimiter.RESTORE_KEY`, `SoilOpenFiles.anyOpen()`.

**Tests:** 39 new (`RestoreRecoveryTest` 14 — every case the five renames can leave, plus four
invariants over all 48 states incl. idempotency through a file-system model; `RestoreDestinationTest`
8; `RestoreEngineTest` 17 — both gates and the validation rule over a real temp staging dir) —
1112 → **1151**, 0 failures. Files: `RestoreEngine` 430 lines, `RestoreRecovery` 91,
`RestoreDestination` 124. Debug build installed on the Nomad; the user opened it by hand and it came up into the
library as before — the two new Bootstrap lines cost nothing visible (no restore performed —
nothing calls `commit` yet). Version `0.1.0-ratta`.

**Read-back notes for L3:** the screen's order is preflight → stage → validate → `proveCached`,
else loop `AttemptLimiter.check(RESTORE_KEY)` → prompt → `proveTyped` until a key opens or the person
gives up (`Problem.NoKey`) → `commit` → report → `relaunchIntent(thenBackup = false)` +
`finishAffinity()` on **every** `Outcome`. Discard staging (`RestoreStaging.discard`) when the loop
is abandoned before `commit` — the engine only discards on its own refusals. The proven passphrase
lives in the screen's memory between the proof and `commit`, never in a Bundle.

### L3 — Outcome (2026-09-05, Opus build, Fable read + engine fix + the walk by hand)

**Landed — the screen, the door, and the first real restore on the Nomad.** `restore/RestoreActivity`
(561 lines, `activity_restore.xml` + `item_restore_backup.xml`, manifest `exported="false"`):
sources pane (caption + *From a folder…*; the cloud row is in the layout and `GONE` until L4) →
`OpenDocumentTree` with **no** `takePersistableUriPermission` (the grant lives for the showing —
decision 3) → `SafRestoreSource.listBackups()` under a *Reading folder…* dialog → one bordered row per
backup (name · "N notebooks · size · index date time"; the folder named by its document-id path,
never the URI) + *Choose another folder…* → *Replace your library?* (the only cancelable dialog:
names the backup, says replace-all cannot be brought back, that the backup's own passphrase or
recovery key may be needed, and that this device's destination is kept) → one non-cancelable,
button-less progress dialog through `preflight` → `stage` (*Copying n of m…*) → `validate`
(*Checking…*) → `proveCached` (*Unlocking…*) → the key loop → `commit` (*Installing…*). The key prompt
inflates `dialog_notebook_passphrase.xml` (body/hint/error swapped), keeps the IME up, hides the
entry row under the `RESTORE_KEY` lockout with the countdown ticking, calls only
`RestoreEngine.proveTyped` (which records the attempt), and Cancel discards staging. The proven
passphrase is one local `val`. Four endings, all dialogs: **Committed** (counts, stores named
separately, one action *Restart*), **RolledBack** (*Restore failed* / nothing changed, *Restart*),
**Interrupted** (new — below), **Refused** (a named problem dialog, the screen stays on the list).
Restart = `BootstrapActivity.relaunchIntent(thenBackup = false)` + `finishAffinity()`. Every
`Problem` and `RestoreProblem` kind has its own title + body; no toast anywhere. The Backup screen
gained a *Restore* caption + *Restore from a backup…* row under the Cloud section (711 → 738 lines);
the tap checks `GlobalRotation.hasMarker` off Main and refuses with a dialog pointing at the
Encryption screen's Resume.

**Fable's read-back fixed one L2 contract hole, found by the Opus agent:** `commit`'s top-level
catch answered `Refused(Unexpected)` for *any* throw, including one after `closeForRotation` — and
`Refused` promises "index open, nothing touched". The commit is now two halves: the pre-close half's
catch un-parks, discards staging and answers `Refused` (true); the post-close half (`afterClose`,
under its own catch with a `Marks.swapBegun` flag) runs D5's plan in-process and answers
**`RolledBack`** when the restored index did not land (aside back, old session restored) or the new
**`Outcome.Interrupted`** when it did (the marker is live, the aside is gone, the key step threw —
the relaunch may stop at Unlock, where the backup's key opens it and the park is applied). A throw
from `closeAll`/`closeForRotation` itself is `RolledBack` with the swap never begun.

**The walk (Nomad, by hand — Fable drove every tap; the SAF pick was the user's):** local backup of
the dev library (4 copied, 43 skipped, 7 stores, both legs) → `GlobalRotation` `walkpass1` →
`walkpass2` (45 notebooks, 53 files, ~3.5 min) → Backup → *Restore from a backup…* → *From a folder…*
→ picker → `Documents/Notesprout-Dev` → the list showed **"dev — 49 notebooks · 24 MB · Sep 5, 2026
10:26 PM"** → Replace → **first run refused at validate**: *"Backup isn't complete —
`575aca61-….soil` in this backup is not a Notesprout file…, so nothing was restored. Your library is
untouched."* That file is a **plaintext** `.soil` dated 2026-08-09 that og Notesprout Dev left in the
same folder before this app existed; the writer never deletes, so it sat there, and the manifest
takes every `<uuid>.soil` by name. The refusal was exactly L2's "fail whole, by name" — the library
was untouched, staging discarded. Moved that one file into a `stale/` sibling (nothing deleted) →
Replace again → staged 59 files → **the cached `walkpass2` was refused silently and the prompt
appeared** (*Unlock the backup — Enter the passphrase or recovery key of the library this backup
came from*) → `wrongkey1`/`2`/`3` → *That key does not open this backup.* twice, then **"Too many
attempts. Try again in 25 s"** with the entry row hidden → lifted → `walkpass1` → *Installing…* →
**"Restore complete — Restored 48 notebooks and 8 extension stores from dev."** → on disk: no
`restore_replaced/`, no `restore_staging/`, index installed, 58 files in `Garden/` → Restart →
Bootstrap → **the library, not Unlock** (log: `destination re-applied after restore (tree=true,
cloud=true)`) → the Backup screen names **`Documents/Notesprout-Dev` and the cloud folder `waltest`
as before, the account still connected from its restored store, both status lines "Never backed
up"** (stamps cleared — decision 3, the whole point) → *Back up now* copied **everything**: 46
copied, 1 skipped, 8 stores, on both legs (110 files) → Encryption screen: key set, 45 notebooks →
a notebook opened with no prompt. **The Nomad dev library is back under `walkpass1`.**

**Walk findings for L5 / L6 (recorded, not decided here):**
1. **A stale orphan in the backup folder is fatal.** A backup destination shared with og (or any
   file the writer never deleted) can hold a `<uuid>.soil` the backup's index does not name; a
   plaintext one refuses the whole restore by name. The refusal is honest and safe, but the person
   has to find and move the file by hand. L5 should decide whether an orphan (no row in the staged
   index) may be skipped-and-named rather than fatal; the restored `48` also carried two encrypted
   orphans (Aug 9 / Aug 29) into `Garden/` as invisible files, and the L3 list's **"49 notebooks"**
   counted files, not index rows — the count is the listing's, and the doc should say so.
2. The dialog wording "is not a Notesprout file" is wrong for a plaintext og file — it *is* one,
   just unencrypted. Reword in L5 to say "is not encrypted, or is damaged".
3. `probe.legacy.db` / `probe.test.db` (the extension-store self-test's leftovers in `Garden/`) ride
   every backup and restore as stores, and the rotation re-keys them — the `8 extension stores`
   includes them and the pre-rename `ext.drive.dev.db`. Harmless; the self-test should clean up
   (a debug-only chore).
4. **SAF picker on the Nomad:** breadcrumb taps DO register from adb, folder items do NOT — the
   standing trap holds for the pick itself. The system folder "Document" (Supernote's) is not
   "Documents" (Android's) — the backup folder is under the latter.

**Tests:** 1151 (unchanged — the screen's only pure logic is exhaustive `when`s over resource ids).
No new files over 800 lines. Version `0.1.0-ratta`.


### L4 — Outcome (2026-09-05, Opus build, Fable read + the cloud walk by hand)

**Landed — the cloud source, the second row, and the first restore from the cloud on the Nomad.**
`restore/CloudRestoreSource` (212 lines, D6) over `CloudClient.list` / `.download` only:
`list(["Backups"])` → device folders (folders only, by name — pure `CloudRestoreRules.deviceFolders`)
→ one `list` per folder → `RestoreManifest.plan(…, CLOUD)` → a row whose **handle is the folder
NAME** (the fetch re-lists `Backups/<name>` by path, the L1 rule; an entry id would go stale on a
re-created folder and buy nothing). `fetchInto` re-lists, re-plans under `CLOUD` (**no `-wal` is ever
fetched** — R3), downloads each item into a `.part` through a `ParcelFileDescriptor` the client owns
and closes, under `CloudTimeouts.downloadBudgetMs(item.size)` (the phase-start answer: `DOWNLOAD_MS`
flat to 20 MiB, then 120 s per 20 MiB slice rounded up; `CloudClient.download` gained an optional
`budgetMs`, Import untouched), and refuses the whole fetch on the first file where the provider's
count, the landed length and the listing's size disagree. The four failures map exactly as
`CloudBackupLeg.problemFor` (`RestoreProblem.CloudNotConnected / CloudNetwork / CloudUnanswered /
CloudGone`; `CloudGone` only when discovery no longer finds the provider). One subfolder that will
not list is skipped like the SAF source's unreadable subfolder; the two typed refusals end the
enumeration; **nothing found + something failed reports the failure, never "No backup here"**.
`RestoreStaging.writeStagedVia(target, expected) { part -> Long }` is `writeStaged`'s fd-shaped twin
(a negative return carries a typed failure out without a throw). The screen: `btnFromCloud` shown by
`ExtensionRegistry.cloud` discovery on create **and** resume (GONE, never disabled); *Choose another
source…* returns to the two-row sources pane; *Downloading n of m…* for the cloud leg; four new
problem dialogs naming the provider. No cloud-only free-space margin (the phase-start answer — L2's
preflight already gates on listing bytes + 64 MB before the first download).

**The walk (Nomad, by hand — every tap Fable's, no SAF pick needed):** device folder renamed
`waltest` → **`waltest4`** (config-only; the 10:49 PM cloud backup in `Backups/waltest` stays, and
its config now names a folder foreign to this device) → Restore → *From the cloud…* listed **two**
backups in 4.1 s (three `list`s: `Supernote-Nomad-4a4bd938` 42 · 20 MB · Sep 4, and `waltest` 46 ·
23 MB · Sep 5 10:49 PM) → **mid-fetch disconnect first**, under the current key: Replace `waltest` →
`svc wifi disable` at *Downloading 9 of 55* → within 0.8 s *"Couldn't reach NSE · Cloud Storage Dev —
… nothing was restored. Your library is untouched. Try again."*; on disk no `restore_staging/`, no
`restore_replaced/` → wifi back → `GlobalRotation` `walkpass1` → `walkpass2` (see the finding below;
committed 44 re-keyed + 1 quarantined after a hand recovery) → Restore → cloud → `waltest` → Replace
→ 55 files downloaded at ≈0.7 s each (**no `-wal` staged — 55 mains, 0 sidecars**) → cached
`walkpass2` refused silently → the prompt → `walkpass1` → *Installing…* → **"Restore complete —
Restored 46 notebooks and 8 extension stores from waltest."** → on disk: index installed, 54 files in
`Garden/`, no aside, no staging → Restart → **the library** (log: `destination re-applied after
restore (tree=true, cloud=true)`) → the Backup screen: folder `Documents/Notesprout-Dev`, **device
folder `waltest4` — this device's, not the backup's `waltest`** (decision 3 on the cloud leg, the
walk's whole point), account still connected, both lines *Never backed up* → *Back up now*: 46
copied + 8 stores on **both** legs, 110 units, the cloud into the new `Backups/waltest4`. **The Nomad
dev library is under `walkpass1`, device folder `waltest4`.**

**Walk finding for L5 (a real one, not the restore's fault):** the rotation before the restore
**stopped** — `ext.drive.dev.db` (the pre-rename cloud store, a dead package) and
`93d69482-….soil` opened under **neither** key. Both had come back from the **L3 local restore**:
the local backup folder held `93d69482` dated **Aug 29** (a stale copy under a key two rotations
gone — the writer never replaces a file the work list skips), and the 10:26 PM backup had reported
**7 stores of 8**, so one stale store copy stayed too. **A restore installs exactly what the folder
holds, and validate can only say "encrypted", never "under the proven key"** — proving every file
would be one platform KDF each (≈4 s/file on the Nomad, ~3 min for this library). Recorded for L5's
decision (skip-and-name orphans · an optional post-proof audit · at least a doc line that a backup
folder is an accretion). Hand recovery used: app stopped, the dead store moved to
`/sdcard/Download/nsn-aside/` (never deleted), Resume → the rotation finished; the cloud restore
brought the store back and it was moved aside again after the walk. **Other findings:** the Restore
screen's caption names the extension label (*NSE · Cloud Storage Dev*) where the Backup screen says
*Google Drive* — a `status()` would cost a bind, a `providerName` reuse would not; the
`ext.drive.dev.db` leftover should be cleaned from the dev library for good (debug chore, with the
`probe.*.db` pair from L3's list).

**Tests:** 24 new (`CloudRestoreRulesTest` 10, `CloudTimeoutsTest` +6, `RestoreStagingTest` +7,
`RestoreManifestTest` +1) — 1151 → **1175**, 0 failures. `RestoreActivity` 677 lines (under the
threshold, no extraction). Version `0.1.0-ratta`.
