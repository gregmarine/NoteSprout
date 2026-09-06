# RESTORE_PLAN.md — Arc 27 "Restore" (Notesprout SN, branch `ratta`)

**Standalone plan for whole-library restore** — item 2 of `PARITY_BACKLOG.md`. This file is the
cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this
file is enough. `ENCRYPTION_PLAN.md` and `DRIVE_PLAN.md` are the shapes this file copies.

**Status:** wizard locked 2026-09-05 · L1 ⬜ · L2 ⬜ · L3 ⬜ · L4 ⬜ · L5 ⬜ · L6 ⬜

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
| `SelfContainedSnapshot` — WAL absorbed into a cache copy so the cloud never holds a sidecar | write side | L4 relies on it being true: a **cloud** backup has no `-wal` to fetch; a **local** one can |
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
- **The WAL rule, read side.** A `-wal` is taken **only** with its main file, and both must land or
  the whole fetch fails. A `.soil` staged without its backed-up `-wal` is a silent loss of the
  writes in it; a `-wal` staged without its `.soil` is meaningless. Never one, never neither-when-
  the-backup-has-both. (The cloud leg's backups carry no sidecars at all by
  `SelfContainedSnapshot`'s construction; the rule still runs, and finds none.)
- **`dev/`.** Debug builds back up into a `dev/` subfolder. Enumeration is therefore **one level
  deep**: the picked tree counts as a backup if it directly holds `notesprout.db`, and each
  immediate subfolder that holds one counts too. og's rule, and it is also what lets a user pick a
  parent holding several devices' folders.

### D2 — Staging (`restore/RestoreStaging`, L1)

`cacheDir/restore_staging`, wiped and recreated at the top of every attempt. Every file streams to
a `.part` name and renames on completion, so a dropped read never leaves a truncated file under a
name the commit would install. **Every per-file result is checked and any single failure aborts the
whole fetch** — a silently short staging set would commit as the entire library. Progress is
`(done, total)` across the manifest. The live library is untouched by anything in this step.

### D3 — The commit (`restore/RestoreEngine`, L2)

og's order, with SN's four additions. Steps 1–5 touch nothing live; the point of no return is 7.

1. **Pre-flight.** No rotation marker; no `.soil` held open; a destination the source can still
   reach. Refuse with a named `Problem`, nothing staged.
2. **Stage** (D2).
3. **Validate.** `SoilCrypto.probe` every staged `.soil`, every staged store and the staged index —
   `Invalid` fails the restore by name. `Encrypted` passes; nothing is read deeper without a key.
4. **Prove the key** (decision 6). Test-open the *staged* index: cached global first, silently;
   then the prompt, under `AttemptLimiter("RESTORE")`, folding Crockford confusables the way Unlock
   does. Opens or the restore stops here, live library untouched. The proven passphrase is held in
   memory for step 8 — **never** logged, never in an Intent, never written outside `PassphraseStore`.
5. **Free-space gate.** Staged bytes + 64 MB headroom against the library volume's usable space;
   short means a hard fail naming the shortfall. The commit copies the staged set in while the old
   library still exists aside.
6. **Read out this device's destination** (decision 3) — `treeUri`, `cloudEnabled`,
   `cloudDeviceFolder` — from `BackupStore` while the index is still open, and park them
   device-locally (`SecurePrefs`, a `restore_pending_destination` blob; not a secret, but it rides
   the store that already survives the swap).
7. **Close and swap.** `ExtensionStores.closeAll()` → `SnIndex.checkpoint()` →
   `SnIndex.closeForRotation()` → rename the live `notesprout.db*` and the whole `Garden/` into
   `restore_replaced/` → copy the staged Garden in → install the staged index **last**, `.part` +
   fsync + rename. **The installed index is the commit marker.**
8. **Key state.** `PassphraseStore.setGlobalPassphrase(proven)`, clear the recovery-key
   acknowledgement **only if** it was never set for this library (see below), `KeyMaterial.clearAll`,
   `KeySession.clear()`, `NotebookUnlocks.clear()`, `PassphraseCache.clear()`,
   `PassphraseStore.clearRotationMarker()`.
9. **Discard the aside** (decision 5) and the staging dir.
10. **Relaunch.** Report dialog → Restart → `BootstrapActivity.relaunchIntent(thenBackup = false)` +
    `finishAffinity()`.

**Failure inside step 7 rolls the aside back and reopens the index**, so the app keeps working
without a restart — og's rule, and the reason the aside is renames rather than copies.

**The acknowledgement question.** The restored library's recovery key is the source device's, and
the user just typed it (or it was already this device's). Showing `RecoveryKeyActivity` after a
restore would present a key the user demonstrably already has. So the acknowledgement is **left
set** when the key came from the cached global, and **set** when it came from the prompt — a
restore never routes to the recovery-key screen. Recorded here because `BootstrapRoute.afterOpen`
would otherwise send them there.

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

Launch-time repair, called from `BootstrapActivity.boot()` **before** `SoilRekey.recoverGarden` and
before `SnIndex.ensureReady` can treat a missing index as a fresh install. The installed index is
the commit marker:

- aside present + **no** live index → the swap never completed → roll the old library back.
- aside present + live index present → the commit finished and the cleanup did not → the aside is
  the replaced library; discard it.
- a leftover `notesprout.db.part` is stale on both branches; delete it.

Ordering matters: a restore's aside must settle before a rekey's `.rekey.tmp` / `.old.bak` are
judged, or `recoverGarden` would reason about a Garden that is halfway between two libraries.

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

### ⬜ L1 — the read side: manifest, sources, staging

Pure rules + the SAF source + staging. **No UI, no engine, nothing live is touched by any code in
this phase.**

- `restore/RestoreManifest` (D1) — pure, exhaustively JVM-tested against the real filename shapes:
  `.part`, `.old`, `.rekey.tmp`, `.old.bak`, `-shm`, a store whose stem fails
  `isValidExtensionPackage`, a `-wal` with no main file, a main file with no `-wal`.
- `restore/RestoreSource` + `RestoreBackup` (D6).
- `data/backup/SafBackupReader` — the writer's read twin, hand-rolled `DocumentsContract`, one
  listing per enumeration, `.part`+rename copies.
- `SafRestoreSource` — one-level-deep enumeration (D1), notebook counts, index mtime.
- `restore/RestoreStaging` (D2).

**Questions to resolve at phase start:** app version · whether `RestoreBackup` carries a total byte
size (it would let L3 show "N notebooks · 412 MB" and L2 pre-check free space before fetching — one
extra `COLUMN_SIZE` query per file, or the listing's size column if it is populated).

### ⬜ L2 — the commit engine

The dangerous half, and the one Fable writes.

- `restore/RestoreEngine` (D3) — `Result`/`Problem` types, never throws past its top-level catch.
- `restore/RestoreDestination` (D4) — the pure merge + the `SecurePrefs` park; applied in
  `BootstrapActivity`.
- `RestoreEngine.recoverInterrupted` (D5), wired into `boot()` ahead of `recoverGarden`.
- `SnIndex.closeForRotation`'s contract widened to "rotation or restore" (doc comment + the
  `IndexGuard` note); `AttemptLimiter` `"RESTORE"` bucket.
- JVM tests for every pure part: the merge table, the manifest→plan, the free-space arithmetic, the
  recover-interrupted branch table.

**Questions to resolve at phase start:** whether the free-space headroom stays og's 64 MB · whether
a `.soil` that fails its probe fails the whole restore (og) or is skipped-and-named (the backup
engine's "copy it as the bytes it is" instinct points the other way; **default: fail**, because a
restore installs a library and a bad notebook in it is a library that lies) · where the parked
destination blob lives if not `SecurePrefs`.

### ⬜ L3 — the screen, the Backup row, and the local walk

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
extension stores separately, as the backup report does (W5's call).

### ⬜ L4 — the cloud source and its walk

- `restore/CloudRestoreSource` (D6) — `Backups/` enumeration, per-folder listing, `download` into
  staging, the four typed failures.
- The Restore screen's second source row, `GONE` (never disabled) while no trusted provider is
  installed — the Backup screen's Cloud section rule.
- **The walk (Fable, by hand, Nomad):** the same rotation recipe, cloud leg — including a **renamed
  cloud device folder** before the backup so the restored config carries a foreign one and decision
  3 is visibly exercised; a mid-fetch disconnect must abort with nothing live touched.

**Questions to resolve at phase start:** the download timeout budget (reuse
`CloudTimeouts.uploadBudgetMs(length)` or a read-side twin) · whether a cloud restore refuses
outright below some free-space margin before it starts downloading.

### ⬜ L5 — hardening: failure injection on the Nomad

**No `/code-review` in this arc** (decision 8). Instead, break it on purpose and fix what falls out.
A debug-menu `RestoreProbe` is the door, in the `RekeyProbe` shape. At minimum:

- kill the process mid-commit, at each of: after the aside rename, after the Garden copy, before the
  index rename, after the index rename and before the key reset. D5's branch table proved live.
- a full disk at the free-space gate, and a disk that fills *during* the Garden copy.
- a torn staging set (delete a staged `.soil` between validate and commit).
- a backup folder salted with `.part`, `.old`, `.rekey.tmp`, `.old.bak`, a stray `-shm` and a
  foreign `.db` — none of them may be staged.
- a `.soil` + `-wal` pair where only one side is present in the backup.
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
