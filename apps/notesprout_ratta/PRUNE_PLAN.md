# PRUNE_PLAN.md — Arc 34 "Prune" (Notesprout SN, branch `ratta`)

**Standalone plan for fixing the findings of the 2026-09-09 code review of arcs 24–33** — a fresh
user decision (2026-09-09), not a `PARITY_BACKLOG.md` item (the backlog is closed). This file is
the cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this
file is enough. `FOCUS_PLAN.md` is the shape this file copies.

**Status: 🔄 IN PROGRESS — P1 ✅ (2026-09-09, H1 landed; user walk WAIVED) · P2 🔄 (M1 ✅ + M2 ✅ + M3 ✅ + M4 ✅ + M5 ✅ + M6 ✅ 2026-09-09, all by Fable at the user's call; M7–M9 ⬜) · P3 ⬜ · P4 ⬜.**
Baseline before the arc: 1626 `:app` / 3033 JVM tests, g-paper 0.1.28, `API_VERSION` 9, fourteen
modules, version `0.1.0-ratta`. No point, no API bump, no schema change, no g-paper change, no new
module, no new dependency. **The notebook's bottom-strip pager (`NotebookActivity.kt` /
`activity_notebook.xml` / `docs/notebook.md`) was committed as `9f651def` before P1 started** (the
tree was clean at P1's start) — P3's group A now only replaces its `PenIdle` copies (L1); there is
no uncommitted working tree to land.

**Phase code:** **P** — one letter (no earlier arc used a bare P; PE was arc 30).

---

## What this arc is

The review (`/code-review`, eight finders + four adversarial verifiers, range `8f584d86..HEAD`
plus the working tree) confirmed **32 findings: 1 high, 9 medium, 22 low**; four candidates were
refuted and are listed at the end so nobody re-raises them. This arc fixes all 32, split by
weight to the model that fits:

| Phase | Model | Scope | Findings |
|---|---|---|---|
| **P1** | **Fable** | The one high — a restore data-path defect | H1 |
| **P2** | **Opus** | The nine mediums — correctness in rotation, events, notebook, extension launch; two efficiency items | M1–M9 |
| **P3** | **Sonnet** | The twenty-two lows — reuse, simplification, dead code, conventions, small efficiency, two doc mismatches | L1–L22 |
| **P4** | **Sonnet** (docs) + **Fable** (freeze) | Docs, ledger, memory, freeze | — |

Every fix carries its JVM test where the code is pure; the three findings that need a device
walk (H1, M5, M7) get a short numbered checklist for the user's hand. **No `/code-review` of the
fixes themselves** unless the user asks at a phase start.

## Binding rules for every phase

- **Fix the finding, not the neighbourhood.** A finding names one defect; the fix is the smallest
  change that removes it and pins it with a test. Refactors beyond that are P3's reuse items and
  nothing else.
- **A documented decision beats a finding.** Where a verifier found the behaviour ledgered as a
  decision, the finding was dropped (see § Refuted). If an implementer finds another such ledger
  line for a surviving finding, stop and ask at the phase start — do not silently skip.
- **Docs move with the code.** Each fix updates the sentence in `docs/*.md` that described the old
  behaviour, in the same phase (P4 only sweeps for what was missed).
- **Tests before the fix where a test can fail first** — every correctness fix (H1, M1–M7) starts
  with the failing JVM test that reproduces the verifier's scenario.
- **NUL scan every changed file** before calling a phase done.

---

## P1 — Fable — the high

### H1 · SAF restore refuses any backup carrying a store `-wal`
`app/.../restore/RestoreEngine.kt:447` (Step 0) + `:346` (`pruneOrphans`).

**Defect.** `commitInner` Step 0 exempts only INDEX / INDEX_WAL from the "staged size == manifest
size" check. `pruneOrphans` calls `SoilCrypto.verifyPassphrase` on every staged STORE, which opens
it **read/write** (`openRaw` → `openOrCreateDatabase`); stores are WAL-mode, so the last-connection
close checkpoints a staged `<pkg>.db-wal` into the main file and unlinks it. Step 0 then sees the
store grown and the STORE_WAL item missing → `Outcome.Refused(Problem.InvalidFile("<pkg>.db"))`,
staging discarded, every time, for an intact backup. The SAF leg does write store WALs
(`BackupEngine.copyStore` → `copyDatabase`, the WAL-alongside rule) and `RestoreManifest.plan`
keeps STORE_WAL for `RestoreLeg.LOCAL`; the cloud leg drops every WAL (R3) and is immune.

**Fix (Fable decides between these at phase start; the first is the recommendation).**
1. **Verify stores read-only.** Give `SoilCrypto` a read-only verify (`SQLiteDatabase.OPEN_READONLY`
   / `NO_LOCALIZED_COLLATORS`) and use it from `pruneOrphans` — a read-only open neither replays
   nor deletes the WAL, so the staged bytes stay exactly what the manifest measured and Step 0
   holds as written. Check that SQLCipher's read-only open of a WAL-mode file with a sidecar
   works (it does for SQLite ≥ 3.22 when the `-shm` can be created; the staging dir is writable).
2. Fallback: extend the Step 0 exemption to STORE / STORE_WAL with the same "SQLite's close
   checkpoints" rationale as INDEX. Weaker — the bytes then differ from the manifest and a real
   truncation on the store would pass.

**Tests.** `RestoreEngineTest` (or a new `RestoreCommitTest`): stage a WAL-mode store with a
non-empty `-wal` sidecar + a manifest that lists both sizes; run the prune + commit path; assert
`Outcome.Ok` and the restored store carries the WAL's rows. Plus a `SoilCryptoTest` that the
read-only verify leaves a `-wal` in place byte-for-byte.

**Walk (user's hand, Nomad).** Make a SAF backup while an extension store is busy enough to leave
a `-wal` (open the calendar, write, back out, back up immediately), confirm the folder holds
`<pkg>.db-wal`, then restore from it: expect the restore to land, not `InvalidFile`.

**Docs.** `docs/restore.md` § Commit (the Step 0 paragraph: stores are verified read-only, so only
the index is exempt — or the widened exemption), § Traps.

---

## P2 — Opus — the nine mediums

Order is by area so each sub-step opens one file set. Each item: failing test → fix → docs line.

### M1 · `BothKept` rekey outcome quarantines a good GLOBAL notebook
`app/.../crypto/GlobalRotation.kt:261`.

After `SoilRekey.rekeyInPlace` throws on `RekeyCommit.Outcome.BothKept` (original at `X.old.bak`,
new copy at `X.rekey.tmp`, no `X`), `rotateFile`'s catch re-verifies against the missing `file`,
both verifies are false, `afterFailure(NOTEBOOK, opensUnderOld = false)` = QUARANTINE, and the index
row becomes NOTEBOOK-scope while `recoverGarden` later restores the verified `.rekey.tmp` — a
GLOBAL file the index calls passphrase-locked. STORE / INDEX in the same window report STUCK
("hand recovery only") though they self-heal.

**Fix.** Implement `ENCRYPTION_PLAN.md:103-104`'s per-file rule in the catch: before the re-read,
`if (!file.exists() && RekeyNames.tmpOf(file).let { it.exists() && SoilCrypto.verifyPassphrase(it, new) })`
→ finish the commit (`RekeyRecovery.recover` for that one file, or a direct rename) and answer
DONE; only then fall through to the existing verify pair. **Test:** `GlobalRotationTest` scenario
that stubs `rekeyInPlace` to throw after leaving `.old.bak` + `.rekey.tmp` and asserts
`FileOutcome.Done` with the file back in place and no `quarantine` call. **Docs:**
`docs/encryption.md` § Rotation failures (the BothKept row).

### M2 · `opensUnderOld` says REKEY for an already-NEW-keyed file on Resume
`GlobalRotation.kt:276` (+ `:250`).

`peekVerified` answers any cached raw key that opens the file, including one warmed under the
NEW passphrase by `KeyResolver`'s two-candidate GLOBAL resolve after a Cancel. `underNew` is
short-circuited, so the plan says REKEY, `rekeyInPlace` fails under the old passphrase, and the
catch rescues it as DONE after one or two wasted KDFs and a spurious warning.

**Fix.** Evaluate `underNew` **before** `opensUnderOld` in `rotateFile` (a raw-key verify under
the new key's cached raw material where available, else `verifyPassphrase(file, new)`), so an
already-rotated file answers SKIP/DONE without a rekey attempt; correct the KDoc at `:37-38` and
`docs/encryption.md:216-218` (a cached key is NOT invalidated by a Cancel-then-open). **Test:**
`RotationPlanTest` / `GlobalRotationTest` — cache a raw key derived under NEW, resume, assert no
`rekeyInPlace` call.

### M3 · FOLLOWING edit of a COUNT series restarts the full count
`ext-calendar/.../EventWrites.kt:95`.

`editWithScope(FOLLOWING)` truncates the head to UNTIL occ-1 and saves the successor with
`edited.recurrence` verbatim (the editor prefilled `endCount` from the original), so a "10 times"
series split at #5 becomes 4 + 10.

**Fix.** When the successor's rule and anchor equal the original's (the user changed something
other than the recurrence), set `endCount = original.endCount - occurrencesBefore(occurrence)`
(a pure `Recurrence.countBefore(rule, anchor, date)`); when the user changed the rule, keep theirs.
**Test:** `EventWritesTest.editingThisAndFollowingKeepsTheRemainingCount` (4 + 6 = 10) and the
changed-rule case. **Docs:** `docs/calendar.md` § Scoped edits (the FOLLOWING sentence).

### M4 · FOLLOWING edit resurrects deleted later occurrences
`EventWrites.kt:95`.

The successor is saved with `exceptions = emptySet()`; THIS-deletions dated after the split come
back. `editSeries` (ALL) carries `original.exceptions`. The KDoc rationale is inverted (the
truncated part is the head).

**Fix.** `exceptions = original.exceptions.filter { !it.isBefore(occurrence) }.toSet()` — valid
when the rule is kept (dates still match), harmless when re-anchored (never matches). Fix the KDoc
at `:72`. **Test:** flip `EventWritesTest.kt:179-180` to assert the carried set; add a re-anchored
case. **Docs:** `docs/calendar.md:455` (the "no inherited exceptions" sentence).

### M5 · Multi-batch event write mutilates the original on failure
`EventStore.kt:221` (edit) + the plain save path (`:172`, `EventWrites.kt:31-38`).

Statement order puts `exceptionOn` / `truncateEvent` (and, for a plain save, the event row +
weekday / exception / reminder rewrites) in batch 1 ahead of the new row and the note; when
`StoreBatches.split` yields ≥ 2 batches and a later batch fails, the compensation only deletes
`newId` / drops minted strokes — the original stays mutated while the dialog says "Nothing was
changed" (`docs/calendar.md:395-396` is false today).

**Fix.** Reorder the statement list so **every statement that mutates a pre-existing row comes
last**: note strokes and the new event row first, then the original's exception / truncation /
field rewrites. A failure in any batch before the last leaves the original intact and the
existing compensation (delete `newId`, drop minted strokes) restores the pre-write state; a
failure inside the last batch is a single-batch transaction and lands nothing. If the last batch
can itself be split (a rewrite with > 10 000 child rows is not realistic — assert it), the split
must keep the original-mutating tail whole. **Test:** `EventStoreTest` with a fake store that
fails batch 2 — assert the original's `untilDate`, exceptions, title and children are untouched
for THIS, FOLLOWING and plain save. **Walk (user's hand):** none — JVM-pinned; the trigger needs a
4 MiB note.

### M6 · Erased sticky / link soft-delete skipped if Back lands first
`app/.../notebook/NotebookActivity.kt:1694` (+ twin `:2290`).

Link removal was narrowed to `links.filter { it.stickies.isEmpty() }` in the synchronous enqueue;
sticky-wrapping links and loose stickies are soft-deleted only in `recordWithStickies`' later
`runPageOp`, which skips under `closing`. Erase while another page op holds the mutex, tap Back:
the rows are never deleted and come back on reopen.

**Fix.** Enqueue the sticky and link removals **synchronously** in the erase / delete path (the
pre-arc-28 shape — `session.links.remove(links)` + `session.stickies.remove(stickies)` before any
await), leaving `recordWithStickies` to record the undo entry only (its `withContent` read stays
for the undo payload). Both call sites. **Test:** `NotebookSessionTest` / a
`NotebookActivity`-free pure test if the removal is factored into a `PageErase.plan(links,
stickies)`; else a session test that the seal after an erase-then-close finds the rows deleted.
**Docs:** `docs/notebook.md:1384-1390` (the "recorded a beat later" paragraph — the delete is
synchronous, only the undo record is deferred).

### M7 · Host `runPageOp` swallows Erase / Delete failures silently
`NotebookActivity.kt:1911`.

`runCatching { block() }.onFailure { Log.w }` catches `CancellationException` (a spurious log on
every close) and turns a throwing `eraseCurrent()` / `deleteCurrent()` into a confirmed tap that
did nothing.

**Fix.** One shape, mirroring `InkScreenActivity.runPageOp`: rethrow `CancellationException`;
on `SQLiteException` / `IOException` (the host's "store unavailable" analogue) show the existing
plain `Dialogs.problem` with a new `page_op_failed_title/body` pair ("The page could not be
changed. Nothing was saved."); log the rest. Keep it in `NotebookActivity` — sharing with
`:ext-ink` would need a new module edge (no). **Test:** JVM if the dispatch is factored pure
(`PageOpFailure.classify(t)` → Rethrow / Dialog / Log); the dialog itself is a walk item.
**Walk (user's hand, Nomad debug build):** none required — the classify test pins it; optional:
fill the disk and Erase page to see the dialog. **Docs:** `docs/notebook.md:1391-1394`.

### M8 · `launcher.launch(intent)` unguarded after `releaseForHandoff`
`app/.../extension/ExtensionScreenEntry.kt:266`.

`fresh.open()` validates only the service; an `ActivityNotFoundException` / `SecurityException`
from `launch` escapes the coroutine after `beforeLaunch()` released the pen pipeline — a host
crash with the extension's `begin()`ed showing leaked.

**Fix.** Wrap the launch: `try { launcher.launch(intent) } catch (e: ActivityNotFoundException /
SecurityException) { fail(fresh, e) }` where `fail` closes the held bind (`fresh.close()` /
`end()`), clears `opening`, hides the overlay, restores the paper (`afterReturn` / the re-arm the
result path does), and shows the existing "extension unavailable" dialog. Also `resolveActivity`
the Intent before `beforeLaunch()` so the common case never releases the pen at all. **Test:**
`ExtensionScreenEntryTest` with a launcher stub that throws — assert `opening == false`, the bind
closed, `afterReturn` called. **Docs:** `docs/extensions.md` boundary audit (a new row: a screen
Intent that does not resolve is a dialog, never a crash).

### M9a · Per-page PNG export binds / unbinds `:ext-image` every page
`app/.../export/ExportActivity.kt:2019`.

**Fix.** `ExtensionBinder.hold(appContext, c.ref, action, tag, IExporter::asInterface, timeout)`
once before the loop; call `export` on the held interface per page; `close()` in `finally`.
`ExporterClient` gains a `held(...)` variant or the loop uses the binder directly — whichever
keeps `ExporterClient`'s per-call timeout semantics. **Test:** JVM if `ExporterClient` is given a
`Binding` seam; else the existing export walk. **Docs:** `docs/export.md:22-24` ("one call per
page" → "one bind per export, one call per page").

### M9b · Four Drive metadata round-trips per uploaded file
`ext-cloud/.../DriveApi.kt:153` (+ `rootId` `:105-116`, `ensurePath` `:127-131`).

**Fix.** A per-`DriveApi` `folderIds: MutableMap<String, String>` keyed by the full path,
populated by `ensurePath`; `upload` reads it first; a 404 on the upload's parent evicts that
path (and its descendants) and re-resolves once — the same rule `docs/cloud.md:231` already
states for the root. Drop `rootId()`'s unconditional `exists(cached)` probe in favour of the
same 404-driven re-resolve. **Test:** `DriveApiTest` with a counting fake transport — assert one
listing per new segment per run, zero per repeated upload, and the eviction on 404. **Docs:**
`docs/cloud.md` § Paths (the cache rule now covers every folder, not only the root).

---

## P3 — Sonnet — the twenty-two lows

Grouped by file set; each group is one commit-sized unit. Every deletion is grep-verified across
**all** modules first (Kotlin + XML, build dirs excluded).

### Group A — the pager's `PenIdle` copies (the pager itself is already committed, `9f651def`)
- **L1** `NotebookActivity.kt:3931` — replace the private `releaseRenderIfIdle()` /
  `whenPenIdle()` with `PenIdle.releaseRenderIfIdle(paper)` / `PenIdle.whenIdle(paper,
  binding.root, action)`; also `NotebookToolbar.releaseRenderIfIdle` (`NotebookToolbar.kt:163`).
  Do **not** move the pager into `NotebookToolbar` (out of scope — a "fix the neighbourhood").

### Group B — chrome bars
- **L2** `ShapeTransformBar.kt:167` / `AnchoredBar.kt:100` / `SelectionToolbar.kt:383` — delete
  the private `rectOf` copies, call `PaperToolbar.rectOf(v)`. Make `AnchoredBar.button` `internal`
  and use it from `ShapeTransformBar.iconButton` and `SelectionToolbar`'s button.
- **L3** `NotebookActivity.kt:4056` (+ `StickyEditorActivity.kt:344/641`, `InkScreenActivity.kt:595`)
  — `ChromeToggle` gains `onChanged: (Boolean) -> Unit = {}` (host passes `chromePrefs::hidden::set`)
  and `sync(persisted: Boolean)` (the resume rule, once); `initial` splits into
  `releaseRender: Boolean`. JVM test for `sync` in `ChromeToggleTest`. `docs/sn-screen.md` row.

### Group C — shapes / geometry
- **L4** `ShapeGeometry.kt:74` — `outline` routes the rotation through
  `ShapeBox.toBox(s).toPage(p.x, p.y)`; `tightBounds` untouched. Existing `ShapeGeometryTest`
  must stay byte-identical (it pins the numbers).

### Group D — calendar extension
- **L5** `EventWording.kt:18` — `TimeMath.hour12` / `isPm` + `CalendarDates.HALF_NAMES`.
- **L6** `CalendarActivity.kt:140/604/642` — drop `bakedToday`, compare `bakeKey?.today`, drop
  `force = true` at `:643`.
- **L7** `EventStore.kt:94` — generate each COUNT event's starts once per `eventsInRange`
  (a `Recurrence.coveredDays(e, from, to)` or a per-call cache); `RecurrenceTest` stays green.
- **L8** `EventsActivity.kt:219` — one `store.dayAndUpcoming(on)` that loads the recurring set +
  children once; `docs/calendar.md:1354` sentence.

### Group E — restore / backup
- **L9** `SafRestoreSource.kt:83` — fold `backupOf` into `CloudRestoreRules.rowFor(name, entries,
  leg, handle)` (rename to `RestoreRows.rowFor` if the "Cloud" name misleads); the existing test
  gains the LOCAL case.
- **L10** `RestoreStaging.kt:148` — `writeStaged` becomes a wrapper over `writeStagedVia`.
- **L11** `RestoreEngine.kt:93` — delete `Problem.NoKey`, the `RestoreActivity.kt:629` arm and
  `restore_problem_no_key_title/_body`; fix `docs/restore.md:328` and `:350` (Cancel discards
  staging silently).
- **L12** `RestoreActivity.kt:197/223` — one `adopt(picked, progressRes, caption)`; pass
  `result.backups` straight to `renderList`.
- **L13** `BackupActivity.kt:408/424` — one `legBlock(r, countsRes, countsFailedRes, vararg
  prefixArgs)`; cloud caller appends its problem line.

### Group F — export
- **L14** `ExportActivity.kt:269` — one `answers: NotebookAnswers?` (hasDocument, hasSticky,
  pageFacts) set once; gate = `answers == null`.
- **L15** `ExportRender.kt:342` — `ExportScope.pagesInScope` returns `ScopedPage(row, number)`;
  `Endnotes.caption` takes the notebook-relative number for display while `fromPage` stays
  bundle-relative for the link. `EndnotesTest` gains the page-7 case. `docs/export.md` § Endnotes.
- **L16** `PageReads.kt:51` — one `dao.childrenOf(parentId)` split by type in Kotlin; per-type
  order preserved; `PageReadsTest` (new, small) pins identical output against the six-query shape.

### Group G — crypto (small)
- **L17** `GlobalRotation.kt:249` — pass the raw key from `peekVerified` into `rekeyInPlace`
  (`SoilCrypto.openRawKey` for `absorbWal`, `RawKeyDerivation.rawKeyLiteral` SQL-quoted as
  `attachKeyLiteral`); passphrase path kept for the miss. `SoilRekeyTest` gains the raw-key case.
  **Do after P2's M1/M2 land** (same file).
- **L18** `SoilRekey.kt:130` — `SoilFile.rekeyLeftovers(context)` beside `extensionStoreFiles`
  owns the listing; `recoverGarden` calls it. `SoilFile.kt` KDoc + `CLAUDE.md` § Standing rules.

### Group H — extension misc
- **L19** `InkScreenActivity.kt:595` — save `chromeToggle.hidden` in `onSaveInstanceState`,
  prefer it over the Intent extra in `initChrome`. `FOCUS_PLAN.md` ledger note.
- **L20** `ImageAssembly.kt:89` — record the extension-module exception in
  `apps/notesprout_ratta/CLAUDE.md` § Standing rules ("`Slog` where it is on the classpath;
  `if (BuildConfig.DEBUG) Log.d` in `:ext-*` modules that depend on `:extension-api` only") —
  no code change; also covers `PdfAssembly.kt:131` and ext-mlkit.

### Group I — dead resources
- **L21** `strings.xml:396` `encryption_key_caption`, `:1013-1014` `sticky_open_failed_title/_body`
  — delete.
- **L22** `CloudBrowserDialog.kt:118` `Pick.File.path` — delete the field and its construction at
  `:320`.

---

## P4 — docs, ledger, memory, freeze

Sonnet sweeps every `docs/*.md` sentence the phases touched (§ Where the code is, § Tests counts,
§ Traps), `docs/extensions.md` boundary audit if M8 added a row, `CLAUDE.md` (both) arc line,
`RATTA_PLAN.md` header, memory. Fable runs the gates and freezes: all fourteen modules debug +
release, full JVM suite, NUL scan, three release APKs signed + verified, the M7 walk confirmed by the
user (the P1 walk was waived), then commit + push.

---

## Refuted at verification — do not re-raise

| Candidate | Why it was dropped |
|---|---|
| `RotationMarker.augmented` re-queues INDEX after the index step | The marker state needed exists only between `marker.without(INDEX_ID)` and `commit()` with no suspension; a death there is committed by `SnIndex.openUnderMarkerOrUnlock` on relaunch, never resumed. |
| `BackupEngine.compactPass` skips NOTEBOOK scope | Explicit arc-26 decision (`ENCRYPTION_PLAN.md:52`, `:165`; `docs/backup.md:198-200`); K1 compaction already runs at every clean seal. |
| Uncommitted pager buttons bypass `PageGestures.standDown` | `standDown` resolves finger-gesture ambiguity; every chrome-driven flip (page sheet cut, paste, calendar receive, the pad's and calendar's pagers) already goes ungated through `navigateTo`, which ends a transform deliberately. |
| Uncommitted bottom strip grew to `toolbar_bar_thickness` | The CLAUDE.md dimen rule for any bar hosting a `ToolbarButton`; ledgered in the uncommitted `docs/notebook.md:75-80`; `chromeBand()` and `StickyDefaults` read live sizes. |

## Standing traps that bind this arc

- **Never `runBlocking` on the UI thread; `Slog.d` not `Log.d`** (except the L20 exception).
- **`GONE` keeps the last measured size; `INVISIBLE` keeps the pen claim** — L2/L3 touch rect
  readers; keep the visibility check.
- **Frame-silence:** `releaseRender()` before every chrome frame; `isPenActive` counts hover
  (L1 keeps the `PenIdle` semantics exactly).
- **ActivityResult callbacks run BEFORE `onResume`** — L3's `sync` runs in `onResume`, the pref
  write in `onResult`; never the other order.
- **A Binder call cannot be cancelled** — M9a's held bind keeps the per-call timeout.
- **EPD handoff** unchanged: M8's `fail` path must re-arm exactly what the result path re-arms.
- **`adb push` into `Android/data` deletes the target** — H1's walk backup lives under the SAF
  folder the user picks, never pushed.
- **A SAF pick cannot be driven by adb** — H1's walk is the user's hand.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Doc agents never run git, never revert files they did not create.**
- **The Nomad dev-library key is the typed `walkpass1`** (memory file) — M1/M2 walks, if any,
  start by writing it down at Reveal.
- **Every `.soil` open routes through `SoilCrypto`** — H1's read-only verify lives there, not
  beside it.

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

One phase per session; read this file whole at phase start, flip the phase to 🔄, ask its
phase-start questions **one at a time**, then code. Fable plans / seams / reviews; Opus features;
Sonnet scaffold, layouts, resources, docs, adb walks (Haiku wanders out of the app on the
Supernote); ≤ 5 background agents. JVM tests for every pure piece; the user gets a **short
numbered checklist** only for what needs a hand or an eye. **Nomad only** (SNN `SN078D10012852`);
the Manta only on explicit ask. Commit + push only when every suite is green (or the user's
all-clear), after docs / memory / CLAUDE.md are in; then the user runs `/clear`. A long
explanation and an `AskUserQuestion` never share one turn — explain, wait, then ask.

## Ledger

- **2026-09-09 — planned.** Review run (`/code-review` arcs 24–33, eight finders, four
  verifiers): 37 candidates → 32 confirmed / plausible (1 high, 9 medium, 22 low), 4 refuted.
  Model split decided by the user: Fable H1, Opus M1–M9, Sonnet L1–L22. Phases P1–P4.
- **2026-09-09 — P1 ✅ (Fable) — H1 fixed, option 1 (read-only verify).**
  `SoilCrypto.openRawReadOnly(file, passphrase)` (`ZeticDB.openDatabase` with `OPEN_READONLY or
  NO_LOCALIZED_COLLATORS`) + `verifyPassphraseReadOnly`; `pruneOrphans` verifies every staged
  store through it and deletes the `-shm` the read-only open leaves (not a manifest item);
  Step 0 untouched — the index stays the one exemption, and the comment says why the stores are
  not. Found along the way: sqlcipher-android's raw read-write open also runs
  `PRAGMA journal_mode=delete` (`SQLiteGlobal.getDefaultJournalMode()`), so the old verify did
  not just checkpoint at close — it flipped the staged store out of WAL mode; and its wrong-key
  path goes through `DefaultDatabaseErrorHandler.onCorruption`, which returns early under a codec
  (never deletes) — the read-only path meets the same handler, unchanged.
  **The pin is on-device, not JVM** (no SQLCipher on the JVM — the same reason arc 26 / U2 made
  `RekeyProbe`): a new debug-menu row *Read-only verify vs a staged WAL* (`WalVerifyProbe`,
  cache dir only) builds the SAF backup's shape (rows only in the `-wal`, main + sidecar copied
  while the writer is open), and asserts read-only verify true / wrong key false / both files
  byte-identical / 3 rows read through the WAL, then runs the old read-write verify last and
  reports the defect (main 4096 → 8192 B, `-wal` gone). **PASS on the Nomad 2026-09-09 over
  adb.** The mechanism was also shown on plain SQLite on the Mac first (read-only open keeps
  both files byte-identical + a `-shm`; read-write close checkpoints and unlinks). The plan's
  `RestoreEngineTest` / `SoilCryptoTest` items are therefore replaced by that probe row —
  nothing pure changed, so no JVM test moved (1626 `:app`, all green). `:app` release compiles.
  Docs: `docs/restore.md` § The commit (Step 0), § Orphans (the read-only bullet), § Standing
  traps (never open a staged file read-write before Step 0), § Debug tooling (the probe row).
  No code review (not asked). **The user's walk below was WAIVED by the user (2026-09-09) — the
  `WalVerifyProbe` PASS is H1's whole pin; do not re-raise it at P4.**

  **P1 user checklist (Nomad, `.dev` — the plan's H1 walk; waived, kept for the record):**
  1. Open the calendar, write a stroke on a day, back out to the library.
  2. Backup → local folder → run it now. In the picked folder's device subfolder, confirm a
     `com.symmetricalpalmtree.notesproutsn.ext.calendar.dev.db-wal` (any `<pkg>.db-wal`) sits
     beside its `.db` — if none does, open the calendar, write again, back out and back up again
     straight away.
  3. Backup → *Restore from a backup…* → that local backup → restore. Expect *Restore complete*
     with **no** "were not part of this backup and were left out" line — not *Backup isn't
     complete* naming `<pkg>.db`.
  4. After the relaunch, open the calendar: the stroke from step 1 is there.

- **2026-09-09 — P2 / M1 ✅ (Fable — the user asked for Fable, not Opus, for this item).**
  Failing test first: `RotationPlanTest` gained four `afterThrow` cases (compile-red before the
  fix). `RotationPlan.afterThrow(kind, originalExists, recover, opensUnderNew, opensUnderOld)`
  is the pure sequence after a rekey throws: a **missing original is recovered before either
  verify is read**, and one still missing afterwards is TRANSIENT (kept pending; the next resume
  runs `recoverGarden` before its loop) — never a quarantine, never STUCK; a standing original
  reads DONE under the new key (now also `KeyMaterial.invalidate`, the step the throw skipped)
  else the old `afterFailure` table. `GlobalRotation.rotateFile`'s catch calls it with
  `SoilRekey.recoverOne(file) { new || old }` as the recover step (the existing recovery table:
  original ABSENT + tmp VERIFIES → RestoreTmp, the bak dropped once `X` verifies). No new file,
  no direct rename. Docs: `docs/encryption.md` § The pure half (`afterThrow` bullet), the
  failure table (a new BothKept row), the test table. Gates: 1630 `:app` tests (1626 + 4), all
  green; `:app` release compiles; NUL scan clean. No walk (JVM-pinned; BothKept needs two
  renames to fail on a real filesystem). Next: M2 (same file — `underNew` before `opensUnderOld`).
- **2026-09-09 — P2 / M2 ✅ (Fable — again at the user's call).** Failing test first: five
  `RotationPlanTest` `beforeRekey` cases (compile-red before the fix). `RotationPlan.beforeRekey(kind,
  resumed, rawKeyOpens, opensUnderNew, opensUnderOld)` is the pure read order of `decide`'s two
  facts: on a **start** a raw-key hit is the old key for free (no marker existed before it, so
  nothing can have been warmed under the new passphrase — the arc-26 cheap path kept whole) and a
  miss verifies old then new; on a **resume** the new key is verified **first** and only a failed
  new verify lets the hit answer "old" (a miss goes new then old). The plan's "raw-key verify under
  the new key's cached raw material" has no real referent — `KeyMaterial` caches one untagged key
  per file, so the only discriminator is one KDF, paid on resumes only. `GlobalRotation.run`
  carries `resumed` from `start` / `resume` into `rotateFile`; the private `opensUnderOld` helper
  is gone. `resumeCandidates` logic untouched (a done notebook opened since the Cancel still
  re-joins; it now costs one verify → SKIP instead of a failing rekey); its KDoc and the class
  KDoc corrected — a Cancel invalidates no cached key. Docs: `docs/encryption.md` § The pure half
  (`beforeRekey` bullet), § Per file, the test table. Gates: 1635 `:app` tests (1630 + 5), all
  green; `:app` release compiles; NUL scan clean. No walk (JVM-pinned). Next: M3.
- **2026-09-09 — P2 / M3 ✅ (Fable — again at the user's call).** Failing test first: four
  `EventWritesTest` cases (compile-red on `Recurrence.countBefore`). `Recurrence.countBefore(rule,
  anchor, date)` counts the starts strictly before a date over `generateStarts` (a COUNT rule
  enumerates its own N, any other is bounded at `END_COUNT_RANGE.last`); **exceptions are not
  passed** — a removed occurrence still spent a slot, exactly as `occurrenceStartCovering`
  enumerates. `EventWrites.editWithScope(FOLLOWING)` saves the successor with
  `remainingRule(original, edited, occurrence)`: a rule that is the same object the editor
  prefilled (`edited.recurrence == original.recurrence`) and is COUNT gets
  `endCount = original − countBefore(occurrence)` (≥ 1 by construction); anything else is the
  person's own rule, count included. **One reading of the plan's "rule and anchor equal":** the
  anchor condition was dropped — the successor's anchor can never equal the original's, and a
  moved date is "something other than the recurrence", so a series moved a day later still gets
  6 of 10 (`aMovedDateStillKeepsTheRemainingCount`); only a retyped rule keeps the typed count.
  M4's "no inherited exceptions" assertion is untouched (its own item). Docs: `docs/calendar.md`
  § og's three recurring scopes (the FOLLOWING bullet), the test table. Gates: 312 `:ext-calendar`
  tests (308 + 4), all green; `:app` + `:ext-calendar` release compile; NUL scan clean. No walk
  (JVM-pinned). Next: M4 (same function — carry the exceptions at/after the split).
- **2026-09-09 — P2 / M4 ✅ (Fable — again at the user's call).** Failing test first: the
  `editingThisAndFollowingTruncatesAndStartsAFreshSeries` "no inherited exceptions" assertion
  flipped to assert the carried `(new, 2026-09-23)` row, plus two new `EventWritesTest` cases
  (an exception before the split dropped, a re-anchored tail still carrying the later one) — 3
  red before the fix. `EventWrites.editWithScope(FOLLOWING)` now saves the successor with
  `original.exceptions.filterTo(HashSet()) { !it.isBefore(occurrence) }`; the KDoc's inverted
  rationale corrected (the truncated part is the head). A planned "exception dated on the split
  itself" case was dropped, not pinned: `occurrenceStartCovering` skips exception dates, so no
  caller can split on one — the inclusive bound is still the right one and costs nothing. Docs:
  `docs/calendar.md` § og's three recurring scopes (the FOLLOWING bullet), the test table. Gates:
  314 `:ext-calendar` tests (312 + 2), all green; `:ext-calendar` release compiles; NUL scan
  clean. No walk (JVM-pinned). Next: M5 (`EventStore` statement order — mutating statements last).
- **2026-09-09 — P2 / M5 ✅ (Fable — again at the user's call).** Failing tests first: four
  `EventStoreTest` cases (a THIS override, a FOLLOWING split and an existing event's save that fail
  in batch 2 each leave the original byte-identical; the rewrite is always the whole last batch)
  plus two `EventWritesTest` cases (`EventWrite.batches`, `NoteWrite.inPlace`) and the flipped
  order assertions — compile-red before the fix. **As built:** `EventWrites` answers an
  `EventWrite(additions, noteMutations, rewrites)` — the `INSERT OR IGNORE` row + the note's
  additions first (the FK needs the row; a no-op on an existing event), the note's mutations next,
  the row's update + child rewrites last, and at THIS / FOLLOWING the original's exception /
  truncation appended **last of all** (`rewriting`). `EventWrite.batches` splits the additions on
  their own and keeps the rewrites whole in the last batch (riding the note mutations' last batch
  when they fit, else their own — an over-cap rewrite set is refused whole by the host, never
  torn). `NoteWrite` is now `(additions, mutations, mintedStrokeIds)`: `inPlace` partitions the
  op log by the minted set via `NoteSql.isPutOfAny` (a re-put of a moved loaded stroke is a
  mutation, as a drop is), `copy` is all additions. `InkStore.compensatedBatches` takes pre-split
  batches (the flat `compensated` delegates to it); `EventStore.write` uses it. **One judgment
  beyond the plan's words:** the note's own mutations are best-effort past the cap (a landed batch
  of drops stays landed; the op log is still pending so the next Save converges) — pinning them
  behind the rewrite would put a lasso-move of a 4 MiB note into the "must be one batch" set and
  make the save unsaveable, which is worse than the finding. No `check` on the rewrite width (the
  host's refusal of an over-cap payload is the honest failure). Docs: `docs/calendar.md` § The
  events half (the statement-order paragraph), the failure table (two rows), the test table.
  Gates: 320 `:ext-calendar` (314 + 6), 52 `:ext-ink`, 54 `:ext-scratchpad`, all green;
  `:ext-calendar` + `:ext-ink` release compile; NUL scan clean. No walk (JVM-pinned). Next: M6
  (`NotebookActivity` — sticky / link soft-deletes enqueued synchronously).
- **2026-09-09 — P2 / M6 ✅ (Fable — again at the user's call).** Failing tests first: two
  `StickyStoreTest` cases + one `LinkStoreTest` case (compile-red on `removeWithContent`). **As
  built — one reading of the plan's "enqueue synchronously, leave `recordWithStickies` to record
  only":** the plan's literal shape (`session.stickies.remove(ids)` on the spot, then
  `withContent` later for the undo payload) cannot work — `StickyStore.content` reads live
  children only, so a read after the delete would snapshot an empty note and the undo would
  revive an empty one. So the read and the delete became **one writer job**:
  `StickyStore.removeWithContent(icons): Deferred<List<PageSticky>>` and
  `LinkStore.removeWithContent(links): Deferred<List<PageLink>>` each `enqueue` a single job that,
  in one transaction, reads every note's content, soft-deletes children + rows, and completes the
  deferred with the full snapshot (a failing job completes it exceptionally; a closed writer
  cancels it). The enqueue is synchronous — in writer order, no drain needed (the writer *is* the
  order), no `runPageOp`, no `closing` gate. `NotebookActivity.recordWithStickies` queues both on
  the spot and records the entry in a plain `lifecycleScope.launch` after the awaits (a cancelled
  deferred ends it quietly: no delete ran, so no entry). Both call sites (the three erases via
  `removeContent`, and `deleteSelection`) route through it unchanged; only the comments moved.
  No `PageErase.plan` factoring (the pure piece is the store job, pinned in the store tests).
  Docs: `docs/notebook.md` § Undo (the "delete snapshot suspends" paragraph). Gates: 1638 `:app`
  tests (1635 + 3), all green; `:app` release compiles; NUL scan clean. No walk (JVM-pinned; the
  race needs a held mutex + Back inside one frame). Next: M7 (`runPageOp` failure dispatch).
