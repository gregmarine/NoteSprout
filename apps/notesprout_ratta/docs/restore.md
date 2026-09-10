# Restore (arc 27 "Restore")

Putting a whole library **back** — the inverse of arc 17's backup run and arc 25 / V4's cloud leg.
Choose a backup, prove it opens, replace the library with it: the index, every notebook, every
`Garden/<pkg>.db` extension store. `PARITY_BACKLOG.md` item 2, the user's "need restore for sure",
built L1–L6 on 2026-09-05/06 and walked by hand on the Nomad at every phase.

**Host-only. Not a point, not an `API_VERSION` bump, no extension touched, no `<queries>` change.**
`restore/` (13 files), `data/backup/SafBackupReader`, the Backup screen's one new row, two lines in
`BootstrapActivity.boot()`, one in `UnlockActivity`. Version stays `0.1.0-ratta`. og Notesprout's
`data/backup/RestoreEngine` + `RestoreSource` + `SafBackupReader` and og `docs/backup.md` § Restore
were the reading references — **no code copied**. The plan and the per-phase ledger (every walk,
every injection, every number) is the standalone `RESTORE_PLAN.md`; this file is the reference.

**Status: arc 27 complete + frozen 2026-09-06** — L1 read side · L2 engine · L3 screen + local walk ·
L4 cloud source + cloud walk · L5 failure injection (nine faults, every one walked) · L6 this doc.
**No `/code-review` anywhere in the arc** (decision 8) — L5 broke it on purpose instead. 1194 JVM
tests per variant.

---

## Decisions (binding, wizard 2026-09-05)

| # | Decision |
|---|---|
| 1 | **Both legs.** Local SAF and cloud, one engine behind two `RestoreSource`s. `ICloudStorage` already had `list` + `download`, so the cloud restore cost no contract change and no ninth point. A device that only ever backed up to the cloud must be recoverable in-app — that is the data-loss case the backlog item exists for. |
| 2 | **Replace all — og's shape.** Index + every `.soil` + every extension store, swapped whole. Not a merge, not a per-notebook import (arc 16's Import already recovers one notebook from a backup file), no keep-my-stores mode — the restored index's ids would no longer match the tag assignments, calendar rows and scratch pages naming them. |
| 3 | **The backup destination is device-local and a restore NEVER rewrites it.** This device's `treeUri`, `cloudEnabled`, `cloudDeviceFolder` are read out before the swap, parked device-locally, re-applied on the first successful open after the relaunch. The backup's own destination fields are **always** discarded, even when this device has none configured — a restore never *sets* a destination. **Both stamp maps cleared.** See [The destination rule](#the-destination-rule-decision-3) for the incident behind it. |
| 4 | **The restored cloud account comes back untouched.** `:ext-cloud`'s store is content like any other; the host does not reach into its `account` table (`EditorSchema.prefs` stays the ONE extension table the host reads) and special-cases no store by package. Known consequence, documented not fixed: both devices then hold one refresh token — see the failure table and [`docs/cloud.md`](cloud.md). |
| 5 | **No undo.** The replaced library is discarded the moment the restored index is installed. Keeping it until the first successful unlock was offered and declined. This is what makes decision 6 load-bearing. |
| 6 | **The staged index must be proved openable BEFORE any commit.** Cached global key tried silently (a same-device backup just works, no prompt), then a prompt under `AttemptLimiter("RESTORE")`. No key that opens it → no commit, nothing live touched. The proven key is installed at commit, so the relaunch lands in the **library**, not the unlock gate. Proves the index only — a `NOTEBOOK`-scope notebook still prompts on open exactly as today. |
| 7 | **One door:** a *Restore from a backup…* row on the Backup screen opening its own `RestoreActivity`. Not the Unlock screen (offered, declined), not the library overflow, not the bottom bar (full). |
| 8 | **Six phases, no `/code-review`.** L5 is a failure-injection pass on the Nomad; L6 is docs + freeze with no code. |
| 9 | **Walk data: "rotate to make a stranger", Nomad only.** Back up a throwaway library, `GlobalRotation` to mint a new key, restore the pre-rotation backup — a foreign library under a key the device no longer holds, made with the app's own machinery. Rename the cloud device folder first when a foreign `cloudDeviceFolder` is wanted. No Manta. **Every restore walk is Fable by hand** — one wrong tap wipes the dev library. |
| 10 | Version stays `0.1.0-ratta` (asked at every phase start, same answer). |

**Phase-start answers that also bind:** free-space headroom stays og's **64 MB** · the parked
destination lives in `SecurePrefs` (`sn_secure`, key `restore_pending_destination`) · the source
pick is two rows on the Restore screen, not a dialog · one non-cancelable progress dialog, per phase,
with a per-file counter while staging · the report names the stores separately (W5's rule) · the
cloud download budget is a read-side rate twin, `CloudTimeouts.downloadBudgetMs` · no cloud-only
free-space margin · **one** permanent debug entry (*Break a restore*) · L5's fixes were additive
to L2's surface · **orphans are skipped and named** (below).

**Derived rules (never separately asked):** a restore is refused while a rotation marker stands
(the marker names files the restore is about to delete; the marker is cleared at commit because
the library it described is gone) · refused while any `.soil` is held open (`SoilOpenFiles.anyOpen`)
· the backup list shows name, notebook count (**a file count** — the listing's, not the index's),
size and the index's last-modified time · a successful restore ends in a dialog with one
non-cancelable action, *Restart*, which is `BootstrapActivity.relaunchIntent(thenBackup = false)` +
`finishAffinity()` — the rotation's ending · a restore takes a READ grant on the picked tree and
**never** `takePersistableUriPermission`s it (persisting would be setting a destination) · no
`androidx.documentfile` — `SafBackupReader` is hand-rolled `DocumentsContract` in the writer's
style · a restore never routes to `RecoveryKeyActivity` (the user demonstrably has the key they
just typed).

---

## The model

A backup folder — `Documents/<picked>/` (debug: its `dev/` subfolder) or the cloud's
`Backups/<device folder>/` — holds whatever the writer left plus whatever a killed run stranded.
**A backup folder is an accretion, not a curated set:** the writer never deletes, a trashed
notebook's file stays, a stale copy under a key two rotations gone stays, og Dev's plaintext
`.soil` from before this app existed stays. So the rule the whole arc rests on:

> **A restore installs what the backup's index names and the proven key opens — never "the folder".**

Six steps, in the order the screen walks them, each answering a typed result and never throwing
past its own catch:

```
preflight → stage → validate(INDEX_ONLY) → prove the key → pruneOrphans → validate(NOTEBOOKS) → commit
```

Steps up to the commit touch nothing live. The commit's point of no return is one rename (8e
below), and **the installed index is the commit marker** — present means the restore finished,
absent with an aside present means it did not.

### What a backup folder legitimately contains — `RestoreManifest` (pure)

The manifest is the only thing that decides what gets staged. Given a listing (`name`, `size`) and
a `RestoreLeg`:

- **Taken:** `notesprout.db` (required — its presence is what makes a folder *a backup*) and its
  `notesprout.db-wal`; `<stem>.soil` (`[A-Za-z0-9_-]+`) + `<stem>.soil-wal`; `<pkg>.db` +
  `<pkg>.db-wal` where the stem passes `extensionStorePackage` (the one path authority — reused,
  never re-derived). Order: index → soils → stores, each main then its WAL.
- **Never taken:** `*.part` and `*.old` (a killed `SafBackupWriter` swap), `*.rekey.tmp` and
  `*.old.bak` (an arc-26 `SoilRekey` commit interrupted on the source device and copied by a later
  run), any `-shm` or `-journal`, any directory, anything else. Refused-by-suffix runs first, so a
  `.soil.part` can never match the `.soil` rule.
- **The WAL rule, local leg:** a `-wal` is taken **only** with its main file in the same listing —
  both or neither. A `.soil` without its backed-up `-wal` silently loses the writes in it; a `-wal`
  without its `.soil` is meaningless.
- **The cloud leg never takes a `-wal` (R3).** `SelfContainedSnapshot` makes every uploaded main
  complete, so any `-wal` in a cloud device folder is **stale by construction** — a failed
  stale-sidecar delete. Pairing it with a fresh main is exactly the corruption `CloudBackupLeg`
  guards against; the read side must not undo the guard.
- **`totalBytes`** sums the listing's sizes (an unreported size counts 0 and sets
  `hasUnknownSizes`, which the free-space gate treats as refuse).
- **Enumeration is one level deep:** the picked tree is a backup if it directly holds
  `notesprout.db`, and each immediate subfolder holding one is too — that is what finds debug's
  `dev/` and what lets a user pick a parent holding several devices' folders. An unreadable
  subfolder is skipped, never fatal.

### Staging — `RestoreStaging`

**`getExternalFilesDir(null)/restore_staging/`, the library's own volume, a sibling of `Garden/`**
(R1 — og stages in `cacheDir` and copies; the first draft copied og). Index and Garden both live
under `getExternalFilesDir(null)`, so staging beside them makes the commit **renames only**: peak
disk is old + staged rather than old + staged + copy, the kill window is milliseconds rather than a
multi-hundred-MB copy, and the free-space gate measures the one volume everything sits on. Nothing
enumerates that directory (`extensionStoreFiles` and `recoverGarden` read `Garden/` only), so a
leftover is invisible to the library. Layout mirrors the live one: `restore_staging/notesprout.db`
(+ `-wal`) and `restore_staging/Garden/…`.

Wiped and recreated at the top of every attempt. Every file streams to a `.part` name and renames
on completion (`writeStaged` checks both the streamed count and the landed length; the cloud twin
`writeStagedVia` takes a `ParcelFileDescriptor` lambda). `targetFor` refuses a path that
canonicalises out of staging. **Any single failed file aborts the whole fetch** — a silently short
set would commit as the entire library. The live library is untouched by anything here.

**Free space is gated before the first byte** (R1): both listings carry sizes for free
(`CloudEntry.sizeBytes`, the SAF listing's `COLUMN_SIZE`), so `preflight` refuses when
`totalBytes + 64 MB` exceeds `StatFs`'s usable bytes, naming the shortfall. `fits` is pure; an
unknown volume is refuse.

### The commit — `RestoreEngine.commit`

Whole under `NonCancellable` on IO. Steps 0–7 still touch nothing live.

0. **Re-check the staged set for a tear** (every `SOIL`/`STORE` present and the size the listing
   said; the index exempt from the size rule — the proof opened it and SQLite's close checkpointed
   its WAL into it). The stores are **not** exempt: the prune verifies them **read-only**
   (`SoilCrypto.verifyPassphraseReadOnly`, arc 34 / H1), which leaves a staged store and its
   `-wal` byte-for-byte what the listing measured. The marker and held-file rules are re-checked
   at the last moment.
1. **Read out this device's destination** (decision 3) from `BackupStore` while the index is still
   open and park it in `SecurePrefs`. `ParkFailed` refuses.
2. **Re-measure free space** — the staged bytes already sit on the volume, so only the 64 MB
   headroom must still fit.
3. **Blind the process (R2): `KeySession.clear()` before anything closes.** `ExtensionStores.open`
   *creates* an empty store when the file is missing, and the cloud leg's downloads handed
   `:ext-cloud` a store binder that can call back during the window when the Garden is absent.
   With no key in session every such call throws `SoilLockedException` instead of minting a store
   the install would collide with. The session is set again only after the index is installed; a
   rollback re-sets the **old** passphrase.
4. **Close:** `ExtensionStores.closeAll()` → `SnIndex.checkpoint()` → `SnIndex.closeForRotation()`
   (its contract is "rotation or restore" since L2 — both end in a relaunch).
5. **Swap — renames only, via `RealRekeyFs.rename`, a directory fsync after each group:**
   - **(a)** live `notesprout.db` + every sidecar → `restore_replaced/`
   - **(b)** live `Garden/` → `restore_replaced/Garden`
   - **(c)** staged `Garden/` → live
   - **(d)** staged `notesprout.db-wal` beside the live name (a `-shm` is deleted, never moved)
   - **(e)** staged `notesprout.db` → live, **last** — the marker
6. **Key state:** `PassphraseStore.setGlobalPassphrase(proven)` · `setRecoveryKeyAcknowledged`
   **unconditionally** (R7 — the user just typed this key or already held it; an unset flag would
   route the relaunch to the recovery-key screen) · `KeyMaterial.clearAll` · `KeySession.set(proven)`
   · `NotebookUnlocks.clear()` · `PassphraseCache.clear()` · `clearRotationMarker()`.
7. **Discard the aside** (decision 5) and staging.
8. The screen relaunches: report dialog → *Restart* → `relaunchIntent(thenBackup = false)` +
   `finishAffinity()`.

**Two halves, four outcomes.** The pre-close half's catch un-parks, discards staging and answers
`Refused` (index open, nothing touched — true). The post-close half runs under its own catch with a
`swapBegun` mark: a rename failing at any of a–e runs the recovery plan in-process (aside back, the
staged Garden deleted when both exist), un-parks, puts the old passphrase back in the session and
answers **`RolledBack(SwapFailed(step))`**; a throw after (e) landed answers **`Interrupted`** — the
marker is live, the aside is gone, the key step threw; the relaunch may stop at Unlock, where the
backup's key opens it and the park is applied there. A throw from `closeAll`/`closeForRotation`
itself is `RolledBack` with the swap never begun. **Every outcome relaunches (R4)** — `SnIndex` has
no reopen door but `ensureReady`, which is Bootstrap's alone. A park never outlives the restore
that wrote it.

### Proving the key — decision 6

`proveCached` tries this device's cached global against the *staged* index through
`SoilCrypto.verifyPassphrase` — one platform KDF, silent, no prompt on a same-device backup.
`proveTyped` verifies the text **as typed first, then `GlobalKey.normalize`d** only when that
differs (R6 — arc 26 lets a library carry a typed global, and the Nomad's dev library does; a
Crockford fold would corrupt it), recording success or failure in the `AttemptLimiter.RESTORE_KEY`
bucket; the screen checks the lockout before each prompt and hides the entry row with a countdown
while it stands. The proven passphrase is one local `val` in the screen between the proof and
`commit` — never a Bundle, never an extra, never logged.

### Orphans — `pruneOrphans` (L5)

After the key is proven and before the notebooks are validated:

- reads `SELECT id FROM objects WHERE type = 'notebook' AND deletedAt IS NULL` off the **staged**
  index under the proven key — the same rows `BackupEngine` builds its work list from;
- test-opens every staged store (probe `Encrypted` + `verifyPassphraseReadOnly`, ≈4 s each on
  the Nomad, *Checking extension data n of m…*). **Read-only is load-bearing** (arc 34 / H1): the
  SAF leg stages a store's `-wal` beside it, and a read-write raw open's close checkpoints that
  WAL into the main file and unlinks it (SQLCipher's raw open also switches the file to
  `journal_mode=delete`), so the store then weighed more than its manifest row and the
  `STORE_WAL` file was gone — commit's Step 0 refused every intact local backup that carried
  one as `InvalidFile(<pkg>.db)`. A read-only connection never checkpoints; the one thing it
  leaves is a `-shm`, which the prune deletes so the staged Garden holds exactly what was fetched;
- applies the pure `orphanRule(manifest, aliveIds, deadStores)`: a staged `.soil` with no alive row,
  and a staged store that is not encrypted SQLite or does not open under the proven key, are
  dropped from the manifest and **deleted from staging**;
- `commit(…, leftOut)` carries the names into `Outcome.Committed.leftOut`, and *Restore complete*
  says *"N files in the backup folder were not part of this backup and were left out:"* + names.

Why a store is pruned and not just a notebook: a store has no other key — installing one under
neither key is exactly what **stopped** the L4 and L5 rotations. A notebook is pruned by the index,
never by its age or its key: the L5 walk's "encrypted orphan" from Aug 9 turned out to be the alive
`NOTEBOOK`-scope row, and it restored. `validate(INDEX_ONLY)` runs before the key (a plaintext or
damaged index refuses by name, nothing read deeper); `validate(NOTEBOOKS)` after the prune.

### The destination rule (decision 3)

**The incident.** In og, a restore sets the backup folder to the folder it restored from. The user
restored a BOOX backup onto a Supernote; the Supernote's backup folder silently became the BOOX's;
several runs later the BOOX's own backup had been overwritten by the Supernote's. In SN it bites
harder: the *entire* backup config is one `backup` row inside `notesprout.db` — `treeUri`,
`cloudEnabled`, `cloudDeviceFolder`, and **both** stamp maps — so a naive restore installs a dead
SAF grant (a tree grant is per-app-per-device), a foreign cloud device folder that **would** work
and aim uploads into the source device's folder, and stamps describing files this device never
wrote. **Reading a backup and writing one are two different questions; the answer to the first
must never silently answer the second.**

`RestoreDestination.merge(restored, parked)`, pure and JVM-tested:

| Field | Result |
|---|---|
| `treeUri` | **this device's**, or null if it had none — never the restored value |
| `cloudEnabled` | **this device's** |
| `cloudDeviceFolder` | **this device's**, or null → the Backup screen mints a fresh one on its next render |
| `stamps` / `cloudStamps` | **empty**, both |
| `lastRunAt` / `lastCopied` / `lastSkipped` + cloud twins | **cleared** — this device has never backed up this library |
| everything else | the restored value |

Parked at commit step 1 as one `kotlinx` JSON blob in `SecurePrefs` (written with `commit()`),
applied by `RestoreDestination.applyParked` — write the merge first, clear the park second — in
`BootstrapActivity` right after `SnIndex.ensureReady` answers READY/FIRST_LAUNCH and before
`forwardAfterOpen`, **and** in `UnlockActivity`'s success path for the one kill (after 8e, before
the key step) whose relaunch lands at Unlock. Idempotent; a park that survives a second restore is
overwritten. The log line is `destination re-applied after restore (tree=…, cloud=…)`. Both walks
proved it: after a foreign restore the Backup screen named this device's folder and device folder
(`waltest4`, not the backup's `waltest`), with *Never backed up* on both legs, and *Back up now*
copied everything.

### Interrupted-commit recovery — `RestoreRecovery` + `recoverInterrupted`

**The first line of `BootstrapActivity.boot()`** — before `SnIndex.ensureReady` (which would treat a
missing index as a fresh install) and therefore before `SoilRekey.recoverGarden` (a restore's aside
settles before a rekey's leftovers are judged). Two `exists` stats on an ordinary launch.

The pure plan, `RestoreRecovery.plan(State(liveIndex, asideIndex, liveGarden, asideGarden,
asideSidecars))` → an ordered list of `DeleteAside` / `DeleteStaging` / `DeleteLiveGarden` /
`RenameBack(name)`, is **per-item and idempotent (R5)**, not a two-branch table — the aside is two
renames and the install three, and a kill between any two leaves a state two branches did not name:

| Live index | Aside index | Meaning | Plan |
|---|---|---|---|
| present | any | the commit finished | delete the aside whole, delete staging |
| absent | present | the swap did not complete | if **both** a live and an aside Garden exist, the live one is the new Garden from 8(c) — delete it; rename back Garden, the sidecars, the index **last**; delete staging |
| absent | absent | nothing in flight (fresh install, or a rekey leftover for `ensureReady`) | only a stray staging dir |

An aside Garden with no aside index (impossible by the commit's order) is left for a person, never
deleted. **Two fixes the L5 kills forced:** the executor clears a non-directory squatting on the
Garden name (and a directory on the index name) before a rename back — otherwise a planted file
blocks recovery launch after launch; and it deletes any index sidecar at the live name before the
OLD index returns — after a kill between 8(d) and 8(e) the new index's WAL sits there and SQLite
would replay it into the old file. The same executor is the in-process rollback. Every kill seam
was walked: Android relaunches the task itself ~1.4 s later and Bootstrap's recovery put the old
library back (or finished the new one) every time.

---

## The sources — `RestoreSource`

```kotlin
interface RestoreSource {
    suspend fun listBackups(): ListResult                                  // Backups | Failed
    suspend fun fetchInto(backup: RestoreBackup, staging: File, onProgress): FetchResult  // Staged(manifest) | Failed
}
```

`fetchInto` takes the `RestoreBackup` itself, not an index into the last listing — the handle rides
the row, so a stale position can never name the wrong folder. `RestoreBackup{name, notebookCount,
indexModifiedAt, totalBytes, handle}`; the handle is source-private, never shown or logged.

- **`SafRestoreSource`** over `data/backup/SafBackupReader` — the writer's read twin: `root` /
  `rootName` / `list` (+ `COLUMN_LAST_MODIFIED`) / `open`, no create/rename/delete, one listing per
  enumeration (K3's lesson), never a persisted grant. The root row is named by the tree's display
  name, subfolders by name; the screen shows the folder by its document-id path, never the URI.
  Fetch re-lists and re-plans at fetch time.
- **`CloudRestoreSource`** over `CloudClient.list` / `.download` only. `list(["Backups"])` → device
  folders (folders only, pure `CloudRestoreRules.deviceFolders`) → one `list` per folder →
  `RestoreManifest.plan(…, CLOUD)`. **The handle is the folder NAME** — fetch re-lists
  `Backups/<name>` by path; an entry id would go stale on a re-created folder and buy nothing.
  Downloads land in a `.part` through a `ParcelFileDescriptor` the client owns and closes, under
  `CloudTimeouts.downloadBudgetMs(size)` — `DOWNLOAD_MS` (120 s) flat to 20 MiB, then 120 s per
  20 MiB slice rounded up (`CloudClient.download` gained an optional `budgetMs`; Import stays on the
  flat number). A file whose provider count, landed length and listing size disagree refuses the
  whole fetch. The four typed failures map exactly as `CloudBackupLeg.problemFor`:
  `CloudNotConnected` / `CloudNetwork` / `CloudUnanswered` / `CloudGone` (only when discovery no
  longer finds the provider). Nothing found **and** something failed reports the failure, never
  *No backup here*.

**`fetchFailureProblem`** (L5): a disk that fills mid-fetch reaches the engine as the *source's*
failure — the cloud extension reports its write error as `NETWORK`, and the first walk's dialog
said *Couldn't reach Google Drive*. `stage` now re-measures while the staged bytes still sit on the
volume and names the disk when it is the disk (*needs about 80 MB more*).

---

## The screen — `RestoreActivity` and the one door

The Backup screen's *Restore* caption + *Restore from a backup…* row under the Cloud section; the
tap checks `GlobalRotation.hasMarker` off Main and refuses with a dialog pointing at the Encryption
screen's *Resume*. It passes the provider's `providerName` as an extra so the cloud caption reads
*Backups in Google Drive*, not the extension label.

`RestoreActivity` (`exported="false"`, 711 lines):

1. **Sources pane** — *From a folder…* and *From the cloud…*; the cloud row is `GONE` (never
   disabled) unless `ExtensionRegistry.cloud` finds a trusted provider, re-checked on create **and**
   resume. *Choose another source…* returns here.
2. **The pick** — `OpenDocumentTree` with no persisted grant, then `listBackups` under a
   *Reading folder…* dialog. The cloud lists in ≈4 s (three `list`s for two device folders).
3. **The list** — one bordered row per backup: name · *N notebooks · size · index date time* (N is
   the listing's file count, orphans included) + *Choose another folder…*.
4. **Replace your library?** — the only cancelable dialog: names the backup, says replace-all
   cannot be brought back, that the backup's own passphrase or recovery key may be needed, and that
   this device's backup destination is kept.
5. **One non-cancelable, button-less progress dialog** through `preflight` → `stage` (*Copying /
   Downloading n of m…*) → `validate` (*Checking…*) → `proveCached` (*Unlocking…*) → the key loop →
   `pruneOrphans` (*Checking extension data n of m…*) → `commit` (*Installing…*).
6. **The key prompt** — *Unlock the backup — Enter the passphrase or recovery key of the library
   this backup came from*; inflates `dialog_notebook_passphrase.xml` with body/hint/error swapped,
   keeps the IME up (Ratta), hides the entry row under the `RESTORE_KEY` lockout with the countdown
   ticking, calls only `proveTyped`; Cancel discards staging and answers `NoKey`.
7. **Four endings, all dialogs, no toast:** **Committed** (*Restore complete — Restored N notebooks
   and M extension stores from <name>*, plus the left-out names, one action *Restart*) ·
   **RolledBack** (*Restore failed* / nothing changed, *Restart*) · **Interrupted** (*The backup is
   installed, but a last step failed…*, *Restart*) · **Refused** (a named problem dialog; the
   screen stays on the list). Every `Problem` and `RestoreProblem` kind has its own title + body.

---

## Failure table

| Failure | What the person sees | What happened on disk |
|---|---|---|
| Rotation marker stands | Backup row: dialog → Encryption's *Resume* (`preflight` also refuses, defense in depth — **unreachable by design**: a killed rotation relaunches into Encryption + Resume, back leaves the app, `BackupActivity` is not exported) | nothing |
| A `.soil` held open | *NotebookHeld* (structurally unreachable from the Backup screen) | nothing |
| Not enough space at the gate (`totalBytes + 64 MB`) | *Not enough space — needs about N MB more* | nothing staged |
| Disk fills mid-fetch | the same dialog naming the disk (L5's `fetchFailureProblem`) | staging discarded |
| Folder is not a backup | *No backup here* | nothing |
| Source unreachable / listing failed / a file fails to fetch | named dialog; the whole fetch aborts | staging discarded |
| Cloud: not connected / network / unanswered / provider gone | the four `CloudBackupLeg` wordings, naming the provider (mid-fetch `svc wifi disable` → dialog in < 1 s) | staging discarded |
| Staged index not encrypted SQLite | *Backup isn't complete — notesprout.db … is not encrypted, is damaged, or is no longer there* | staging discarded |
| Staged `.soil` fails its probe (after the prune) | the same, naming the file | staging discarded |
| No key opens the staged index | *That key does not open this backup.* (field clears); 3 misses → *Too many attempts. Try again in N s* with the row hidden; Cancel → `NoKey` | staging discarded on Cancel |
| A `.soil` the index has no alive row for · a store under neither key · a plaintext store | restored anyway, then named in *Restore complete* as **left out** | pruned from staging, never installed |
| Staged set torn between validate and commit | *Backup isn't complete* naming the file (`Refused`) | nothing live touched |
| Park fails | `ParkFailed`, refused | nothing |
| A rename fails at 8(a)–(e) (e.g. a file planted at the `Garden/` name) | *Restore failed — nothing changed*, *Restart* → the old library | aside renamed back in-process, old passphrase back in session |
| Process killed after 8(a)/(b)/(c)/(d) | Android relaunches the task; Bootstrap recovery renames the aside back | old library whole |
| Process killed after 8(e), before the key step | relaunch → Unlock (the backup's key) → park applied there → library | new library installed, aside deleted by recovery |
| A throw after 8(e) | **Interrupted** dialog → *Restart* → Unlock or library | as above |
| A store call from an extension mid-swap | nothing visible; the call meets `SoilLockedException` (R2) | no store minted |
| A `NOTEBOOK`-scope notebook in the backup | restores; shows a lock card; prompts on open as today | installed |
| The restored cloud account | the device comes up connected to the source device's account | store restored as content |
| **Disconnect on the restored device** | the source device's cloud backups start failing `NOT_CONNECTED` — `disconnect` revokes the shared refresh token with the provider | documented, not fixed (decision 4) |
| A foreign `cloudDeviceFolder` / `treeUri` in the backup | never installed; the Backup screen names this device's, or mints a folder | decision 3 |

---

## What the Nomad walks proved (measured)

| What | Number |
|---|---|
| Cloud backup list, two device folders | ≈4.1 s (three `list`s) |
| Cloud download per file | ≈0.7 s (55 files ≈ 40 s) |
| Mid-fetch wifi cut → network dialog | < 1 s, nothing touched |
| Per-store key proof in the prune | ≈4 s each (one platform KDF) |
| The commit (renames + key state) | well under a second |
| Android's own task relaunch after a kill mid-swap | ≈1.4 s |
| `RESTORE_KEY` lockout after three misses | 25–26 s |
| `GlobalRotation` of the dev library (45 notebooks, 53 files) | ≈3.5 min — the walk's "make a stranger" step |
| Local restore of the dev library | 59 files staged, 46 notebooks + 7 stores installed, 4 left out |

Every kill seam (8a–8e), the in-process throw, the planted file, the torn set, the mid-swap store
call, both disk-full shapes (at the gate at 39 MB free; mid-download with 91 MB free and 85 MB
taken at file 9), the three-miss lockout, the foreign-key restore, a store under neither key, the
destination trap on both legs and a `NOTEBOOK`-scope notebook were all walked on the Nomad — the
full logs are in `RESTORE_PLAN.md` § Ledger L3–L5. **Not walked:** a stale `-wal` planted in a
cloud device folder — the user closed L5 without it; the rule is pinned by `CloudRestoreRulesTest`
and `RestoreManifestTest`, and the L4 walk fetched 55 mains and 0 sidecars. Do not re-raise.

---

## Design calls recorded outside the wizard

- **Stage on the library volume, commit by rename (R1).** og copies from `cacheDir`; SN's index and
  Garden share one volume, so the copy bought nothing but a 3× disk peak and a long kill window.
- **Blind the process before closing (R2)** — the mid-swap store call is the walked proof.
- **The cloud leg never fetches a `-wal` (R3).**
- **A failed swap relaunches, never reopens in place (R4).**
- **Per-item recovery (R5)** — 48 states, four invariants, idempotency through a file-system model.
- **Typed-then-normalized prompt, "passphrase or recovery key" (R6).**
- **Acknowledgement set unconditionally at commit (R7).**
- **Validate says "encrypted", never "under the proven key"** — a full per-file proof is one KDF
  each (≈3 min for the dev library). The prune proves the stores (few, each irreplaceable under
  another key) and lets the index judge the notebooks.
- **The count in the list is a file count.** The listing is all the row has before staging.
- **`fetchInto` takes the row, not an index** (L1's one deviation from the plan's sketch).
- **A restore never routes to the recovery-key screen.**
- **The report names stores separately** — W5's rule: a number the user can check against the
  library must keep matching it.
- **The `.dev` `probe.*.db` pair and the dead `ext.drive.dev.db`** still sit in the Nomad's local
  `dev/` and cloud `waltest4` folders (the writer never deletes); every restore names the dead one
  and leaves it out. Harmless, recorded.

## Standing traps

- **A SAF pick cannot be driven by adb** — breadcrumb taps register, folder items do not. Every
  local walk is a user checklist item up to the picker. Supernote's *Document* folder is not
  Android's *Documents*; the backup folder is under the latter.
- **`adb push` into `Android/data/<pkg>/files/` deletes the target** — push to `/data/local/tmp`,
  then `shell cp`. This is how the salted folder was built.
- **The Nomad sleeps behind a six-digit PIN** — `dumpsys window | grep mCurrentFocus` before
  believing a screencap.
- **Every restore walk is by hand** (decision 9). A walk agent's FAIL is re-driven before believed.
- **A rotation STOPS on a store under neither key** (arc 26 behaviour, reproduced twice in this
  arc). The prune keeps a restore from *installing* one, but a rotation that meets one by any other
  route has only the hand recovery: app stopped, the store moved to `/sdcard/Download/nsn-aside/`
  (never deleted), *Resume*. An arc-26 follow-up candidate (quarantine or skip-and-name for stores,
  as notebooks already get).
- **The cloud extension reports ENOSPC as `NETWORK`** — hence `fetchFailureProblem`. Any new
  consumer of `download` that can fill the disk needs the same re-measure.
- **`RawKeyDerivation` stays on the platform PBKDF2 and `KeyOpener.warm` stays serialized** — a
  restore does a burst of cold opens (the prune's per-store proof); the hand HMAC loop would
  Scudo-OOM the process again.
- **Supernote swallows `adb shell input text`** — the key prompt is typed by on-screen keyboard
  taps (the walk's `type.sh`, keyboard rows y = 1337 / 1453 / 1568 / 1683 on the Nomad) or by
  clipboard paste.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Never open a staged file read-write before Step 0 measures it** (arc 34 / H1). A read-write
  SQLCipher open checkpoints and unlinks a `-wal` sidecar on close and flips the file to
  `journal_mode=delete`; the index gets away with it only because Step 0 exempts it. Anything
  that must look inside a staged store or notebook uses `SoilCrypto.openRawReadOnly` /
  `verifyPassphraseReadOnly`. The cloud leg never staged a WAL (R3), which is why the walks of
  arc 27 never met the refusal: they restored a cloud backup or a local one with no store WAL.

## Debug tooling (`.dev` only)

- **Break a restore (debug)** — `RestoreFaults`: arm ONE fault for the next commit, consumed the
  moment it fires, inert unless `BuildConfig.DEBUG`. Nine faults over six seams (`COMMIT_TOP`,
  `AFTER_A`…`AFTER_E`): kill after 8(a) / (b) / (c) / (d) / (e) (`Process.killProcess`), throw
  before step 9 (`InjectedFailure` → the *Interrupted* ending), plant a file at the `Garden/` name
  (8(c) fails → rollback), tear staging (delete the first staged `.soil` at the top of the commit
  → the torn-set refusal), store call mid-swap (`ExtensionStores.open` after 8(b) → must meet
  `SoilLockedException`). The last in-process report rides the menu row's title after the relaunch.
  Everything else in L5 (full disk via `fallocate`, the salted folder, the wrong key, the stale
  sidecar) was adb or hand setup.
- **Extension store self-test** now deletes its two `probe.*` files at the end (an L5 chore; the
  copies already in the backup folders stay, and restore as stores).
- **Read-only verify vs a staged WAL (debug)** — `WalVerifyProbe` (arc 34 / H1): builds the SAF
  backup's shape in the cache dir (a WAL-mode encrypted database whose rows sit only in the
  `-wal`, main + sidecar copied while the writer is open), then proves `verifyPassphraseReadOnly`
  answers true / false-on-wrong-key with both files byte-identical and the rows readable, and
  reproduces the defect with the read-write verify last (main grows, `-wal` gone). PASS on the
  Nomad 2026-09-09. The JVM cannot pin this (no SQLCipher), so this row is H1's pin.

## Not built / future (recorded, no user decision to build them)

- **No undo** — decision 5. No aside kept past commit.
- **No selective restore, no merge** — decision 2; Import is the per-notebook door.
- **No door on the Unlock screen** — decision 7, offered and declined.
- **The shared refresh token after a cross-device restore** — decision 4; documented, not fixed.
- **A store under neither key still stops a rotation** — the arc-26 follow-up above.
- **A post-proof audit of every notebook** (one KDF per file) — declined in favour of the prune.
- **The backup writer never deletes**, so a backup folder keeps accreting orphans; the prune makes
  them harmless. A writer-side prune would be a backup-arc decision.
- Everything else in `PARITY_BACKLOG.md` — items 3–7 are untouched here.

## Tests

1194 per variant in `:app` as of L5 (0 SQLCipher-dependent — the swap, the proof and the prune are
proven on the Nomad by the walks and the fault seam).

| Test class | What it pins |
|---|---|
| `RestoreManifestTest` (22) | every filename shape: `.part`, `.old`, `.rekey.tmp`, `.old.bak`, `-shm`, `-journal`, a bad store stem, a `-wal` with no main, a main with no `-wal`, the same folder under `CLOUD` (every `-wal` ignored), ordering, `totalBytes` / `hasUnknownSizes` |
| `RestoreStagingTest` (21) | `.part`-then-rename, both count checks, `targetFor`'s escape refusal, `fits` incl. unknown = refuse, `writeStagedVia` |
| `RestoreRecoveryTest` (14) | every state the five renames can leave, plus four invariants over all 48 states incl. idempotency through a file-system model |
| `RestoreDestinationTest` (8) | the merge table — this device's fields kept, the backup's discarded, stamps and last-run figures cleared, idempotency |
| `RestoreEngineTest` (36) | both free-space gates, the validation rule by kind over a real temp staging dir, the orphan rule, the recovery executor over real files (the squatter and sidecar fixes), the fetch-failure rule, staged bytes |
| `CloudRestoreRulesTest` (10) | `deviceFolders` (folders only, by name), the not-found-vs-failed rule |
| `CloudTimeoutsTest` (+6) | `downloadBudgetMs` — flat to 20 MiB, then per-slice |
| `AttemptLimiterTest`, `BootstrapRouteTest` | pre-existing — the `RESTORE` bucket rides the same schedule; `afterOpen` unchanged by this arc |
