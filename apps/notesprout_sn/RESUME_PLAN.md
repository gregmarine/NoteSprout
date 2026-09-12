# RESUME_PLAN.md — Arc 32 "Resume" (Notesprout SN, branch `ratta`)

**Standalone plan for launch restore** — item 7 of `PARITY_BACKLOG.md`, the last item on it: a
cold launch returns to whatever screen the user last had open, the whole chain, not just the last
notebook. This file is the cross-session memory for the arc: read it whole at every phase start,
together with the root `CLAUDE.md` and `apps/notesprout_sn/CLAUDE.md`. **Do not load
`RATTA_PLAN.md` for this arc** unless a standing trap needs checking; its protocol and traps are
summarized at the end so this file is enough. `HARVEST_PLAN.md` is the shape this file copies.

**Status: ✅ ARC COMPLETE + FROZEN 2026-09-09 — RS1 ✅ · RS2 ✅ · RS3 ✅ (all 2026-09-09).** The
references are `docs/library.md` § Launch restore and `docs/notebook.md` § Cold-launch restore;
this file is the ledger. `PARITY_BACKLOG.md` item 7 — the last item — is DONE and the backlog is
closed. No next arc is planned; the user decides.
Baseline before the arc: 1564 `:app` / 2945 JVM tests, g-paper 0.1.28, `API_VERSION` 9, fourteen
modules, version `0.1.0-ratta`. Host-only: no point, no API bump, no schema change, no g-paper
change, no new module.

**Phase code:** **RS** — two letters, the arc-29/31 precedent.

---

## What this arc is

SN today reopens the **last notebook** on a cold launch and nothing else: one nullable id in
plaintext prefs (`BrowseState.lastOpenNotebookId`, written at `NotebookActivity` open, cleared at
close and at the two cancelled-open paths), read once by `LibraryActivity.reopenLastNotebookIfNeeded`
inside the grid's first global-layout pass, cleared regardless of outcome, validated three ways
(alive index row · type NOTEBOOK · `.soil` on disk), then `openNotebook`. Everything else — the
calendar, the scratch pad, the document editor — opens at the library.

og restores a **surface stack** (`state/SurfaceStack`): a bottom-first list of screens in prefs,
maintained from `onCreate` (attach) and `onResume` (mark top — drop everything above me), never
`onDestroy`; `MainActivity` replays it on a cold launch with one `startActivities`, dropping any
entry whose target is gone and keeping the rest.

**Three facts that shape SN's version (surveyed 2026-09-09):**

1. **Extension screens cannot be `startActivities`'d.** The calendar, the scratch pad and the
   document editor are other processes; `HostCallerCheck.enforceActivity` finishes them unless
   `callingPackage` is the host, and each needs a store lease + held bind + `begin()` before its
   Intent means anything. A restored task can only be `Library → [one host Activity]`; the host
   screen that comes back on top reopens the extension screen above it **itself**, through its
   existing entry class, once its own session is up. One bind lifecycle per level.
2. **Per-surface position is already solved.** The notebook's last page is on its own row's
   `refId` (`NotebookSession.saveLastOpened`, written at `onStop` and `close`); the calendar keeps
   `lastView/lastDate/lastHalf` in its `state` table; the pad keeps `current`; the editor keeps its
   `caret` table. A stack entry needs only **which surface**, plus a notebook id and the via-link
   flag where one applies — og's finding, kept.
3. **The replay is bounded by Bootstrap.** It runs only on `BootstrapRoute.Next.LIBRARY` (never
   RECOVERY_KEY or ENCRYPTION — those routes never reach the library's cold-launch code, so the
   gate is structural), only on `coldLaunch` (`savedInstanceState == null`), and at the spot where
   `reopenLastNotebookIfNeeded` runs today. `IndexGuard.ready` deliberately `CLEAR_TASK`s whatever
   Android rebuilt after a background kill, so persisted state is the only source of truth.

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `BrowseState` (`sn_view_state`: `folderId`, `mode`, `lastOpenNotebookId`, `lastOpenViaLink`) — ids and enum names only, nothing trusted as existing | the one-notebook restore | `lastOpenNotebookId`/`lastOpenViaLink` **retired** into the stack; `folderId`/`mode` untouched (D1) |
| `LibraryActivity.reopenLastNotebookIfNeeded` — read once, clear regardless, three validity gates, `openNotebook` | | generalized into the replay (D2); the three gates and the read-once rule are kept verbatim |
| `LibraryActivity.openNotebook` — the one door into `NotebookActivity` (R6) | three launch sites | still the one door; the replay is one of them |
| `NotebookActivity.EXTRA_INITIAL_PAGE_ID` — consumed once on a cold create, ignored on a task rebuild | | the shape of `EXTRA_RESUME_ABOVE` (D2) |
| `NotebookActivity.opened` (true once the page is on the paper) + the `opened && !closing` guard on every extension button | | the gate the notebook-level reopen waits on (D2) |
| `reopenCalendarAfterPad` latch (library + notebook): calendar closes asking for the pad, a plain pad close brings the calendar back | the calendar → pad chain | the latch is **persisted structurally**: a `CALENDAR` entry beneath a `SCRATCH_PAD` entry (D1, D2) |
| `ExtensionScreenEntry.open` / `onResult` (`opening` latched at the tap, released with the result), `ScratchPadEntry`, `CalendarEntry` | the pad and calendar doors on both host screens | push at launch, pop at result (D1) |
| `DocumentEditorEntry.open` / `onResult` / `reconnect` + `KEY_DOCUMENT_SHOWING` saved state (same-process recreate) | the editor door; a recreated host reconnects to a showing still on the glass | push/pop like the others; the cold-launch reopen is a fresh `open()`, never `reconnect()` (D2) |
| `DocumentSeedFlow.start` (flush → stored document? → recognize → stage → `documentEntry.open()`) | the tap | bypassed on restore: `documentEntry.open()` directly (decision 4) |
| The own-key passphrase prompt inside `NotebookActivity.openSession` (cancel clears the restore id) | | unchanged; cancel clears the **stack** (decision 3) |
| `NotebookSession.saveLastOpened` (`refId`), the calendar `state` table, the pad `current`, the editor `caret` | per-surface position | untouched — nothing positional rides the stack |
| `RecentsPrefs`, `LinkTrail` (`sn_trail`, survives death by design) | | untouched |
| `IndexGuard.ready` / `bounced`, `BootstrapActivity.forwardAfterOpen`, `BootstrapRoute` | the process-death door | untouched |

## Decisions (wizard 2026-09-09 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Surfaces | **Notebook (always) · Calendar · Scratch pad · Document editor.** Templates and Backup **excluded** (the user unselected them). Tags, Export, Encryption, Restore, the folder picker, New notebook, the link picker and the sticky editor are never restore targets. |
| 2 | Depth | **Full chain**, bottom-first: library → notebook → the extension screen over it, and the calendar → pad chain. Back walks out exactly as before. |
| 3 | Own-key notebook | **Reopen into its unlock prompt**, as a plain reopen does today. Cancel lands on the library and clears the stack (the existing rule, widened). The extension screen above reopens only after the unlock succeeds. |
| 4 | Document editor | **Reopen the editor directly** — no seed dialog, no recognition. If the document row is gone the entry is dropped and the notebook comes back alone. |
| 5 | Storage | **Prefs, `sn_view_state`**, beside `BrowseState` — device-local browsing state, never backed up or restored, ids and enum names only. An index row declined (a restored library would reopen another device's screen). |
| 6 | Housekeeping | Version stays **`0.1.0-ratta`**; **no code review** (the arc 29–31 shape); **arc 32 "Resume"**, phase code **RS**, this standalone `RESUME_PLAN.md`. |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **The stack is device-local by nature and by rule.** `docs/library.md` § Prefs already says prefs
  hold only device-local browsing state; the stack is the same kind of thing. It never rides a
  backup, and a whole-library restore (arc 27) relaunches through Bootstrap with `CLEAR_TASK`, so
  the replay after one runs against a stack written by *this* device before the restore — every
  entry is re-validated, and a notebook that no longer exists is dropped like any other.
- **Entries carry a token per Activity/entry instance, not per surface** (og's reason: the same
  notebook can legitimately appear twice — a link followed into itself). `attach` appends or
  refreshes in place by token; `markTop` drops everything above the token; a pop removes by token.
- **Host screens maintain the stack from lifecycle, extension screens from their entries.**
  `NotebookActivity`: attach in `onCreate` (where `lastOpenNotebookId` is written today), markTop
  in `onResume`, pop at `close()` and at the two cancelled-open paths and the failed-open path
  (the four places that clear the id today). `LibraryActivity`: **reset in `onResume`** — nothing
  can be above the library when it is resumed (extension screens over it are results; the notebook
  is finished before the library resumes). `ExtensionScreenEntry` / `DocumentEditorEntry`: push
  after `launcher.launch` succeeds, pop at the top of `onResult`. Never `onDestroy` (og's rule: a
  killed process gets none).
- **A cold launch reads the stack in `onCreate` (beside `BrowseState`), clears it at once, and
  replays from the local copy in the first-layout listener** — `reopenLastNotebookIfNeeded`'s
  read-once-clear-regardless discipline, so a target that fails is never retried on the next
  launch. The `onResume` reset lands between the read and the replay and is harmless because the
  copy is local.
- **The replay launches the bottom host screen and hands the rest down.** Only two shapes exist:
  `[NOTEBOOK(id, viaLink), …above]` → `openNotebook(id, name, viaLink)` with the remainder as
  `EXTRA_RESUME_ABOVE` (a list of surface names — consumed once on a cold create exactly like
  `EXTRA_INITIAL_PAGE_ID`, ignored on a task rebuild); or `[CALENDAR | SCRATCH_PAD, …]` with no
  notebook beneath → the library opens the top entry itself once the grid has measured and the
  entry has discovered its service.
- **The notebook reopens the chain above it once `opened` is true and the page is on the paper**
  — the same gate every extension button reads (`opened && !closing`) — and never before the
  own-key prompt has succeeded. Order of the chain above a notebook: at most one extension screen is
  showing at a time, so the chain is either one entry, or `CALENDAR, SCRATCH_PAD` (the calendar's
  pad door). For the pair the host opens the **pad** and arms its `reopenCalendarAfterPad` latch;
  a plain pad close brings the calendar back exactly as today.
- **Nothing rides an extension screen's Intent that does not ride it today.** The reopen calls the
  entry's `open()` with no `InkSend` (there is no lasso to send). The calendar, pad and editor land
  on their own persisted positions.
- **The document editor reopens on the page the notebook landed on** (its `refId` last page,
  written at `onStop`), through `documentEntry.open()` — no `DocumentSeedFlow`. For a **text
  document** the notebook's own open path already launches the editor (`openIntoEditor(launch =
  true)`), so a `DOCUMENT_EDITOR` entry above a text-document notebook is consumed silently —
  never a second launch.
- **Missing target → drop that entry, keep the rest below it, never the ones above it.** A
  notebook that is not alive, not a NOTEBOOK, or has no `.soil` empties the whole stack (nothing
  above it can stand). An extension whose service is not installed / not trusted / below its floor
  is skipped and the chain ends there (the library or notebook simply comes back). A document
  editor whose page has no document row is dropped (decision 4). Every drop is one `Slog.d` line
  naming the surface, never an id.
- **The stack refuses `SEARCH`-like transient states by construction** — it holds surfaces only.
  The library's `folderId`/`mode` restore is `BrowseState`'s and is untouched.
- **A restored own-key notebook opens its `.soil` for a screen the user is looking at**, unlike
  og's "encrypted notebook underneath" case — in SN nothing can sit above the notebook until its
  open has succeeded, so the accepted-risk row in og's doc has no SN counterpart.
- **Screens that finish themselves into another notebook** (`switchToNotebook` after a seal,
  `closeAndLaunch` on a link follow) work by token: the new instance attaches before the old one
  pops, and the old pop removes its own token only.
- **Migration:** on the first read, a stored `lastOpenNotebookId` with no stack is read as a
  one-entry `NOTEBOOK` stack (with `lastOpenViaLink`) and both keys are removed. Read once.
- **Killed behind the Export screen** (page-sheet export closes the notebook first; the calendar's
  export leaves the notebook open with a process-lifetime latch): the stack holds what was open —
  nothing for the first case, the notebook alone for the second. Export is never a target; the
  existing "honest fallback" comments stay true.
- **Tests are JVM for everything pure:** the stack model (attach/markTop/pop/reset, token
  semantics, ordering), the codec (round-trip, corrupt blob → empty, unknown surface name →
  dropped entry not a crash), the replay planner (a pure function from a stack + validity answers
  to a launch plan: notebook + above list · library-level entry · nothing), the migration.
  Activity glue is walked on the Nomad — **`am force-stop` + `am start` is the process-death door
  adb can drive**, and `mResumedActivity` is the proof.

## Design (binding unless a phase-start question reopens it)

### D1 — The stack (RS1)

- **`data/prefs/SurfaceStack.kt`** (pure model + codec, Context-free) and the prefs door beside
  `BrowseState` in `sn_view_state`, key `surfaceStack`. `enum class Surface { NOTEBOOK, CALENDAR,
  SCRATCH_PAD, DOCUMENT_EDITOR }`; `data class SurfaceEntry(token: String, surface: Surface,
  notebookId: String? = null, viaLink: Boolean = false)`; kotlinx JSON list, bottom-first.
  In-memory list mirrored to prefs on every mutation, Main thread only (og's shape). API:
  `attach(entry)`, `markTop(token)`, `pop(token)`, `reset()`, `snapshotAndClear(): List<SurfaceEntry>`,
  `migrate(browseState)`.
- **Host screens:** `NotebookActivity` attaches in `onCreate` where `BrowseState.lastOpenNotebookId`
  is written today (token = a `UUID` minted per instance and saved in `onSaveInstanceState` so a
  same-process recreate refreshes in place), marks top in `onResume`, pops at the four clear sites.
  `LibraryActivity` resets in `onResume`. `BrowseState.lastOpenNotebookId`/`lastOpenViaLink`
  removed after the migration read (their tests go with them).
- **Entries:** `ExtensionScreenEntry` gains a `surface: Surface` constructor value (the pad's and
  calendar's subclasses pass theirs) and a `stack: SurfaceStack` door; push after `launcher.launch`,
  pop at the top of `onResult` and in `close()`. `DocumentEditorEntry` the same, `DOCUMENT_EDITOR`.
  `TagManagerEntry` / `CloudConnectEntry` untouched.
- **Replay in RS1 = notebook only** (parity with today): `LibraryActivity` reads + clears the
  stack in `onCreate`, resets in `onResume`, and in the first-layout listener replays the bottom
  `NOTEBOOK` entry through `openNotebook` with the three gates — `reopenLastNotebookIfNeeded`
  renamed to `replayStack` and grown. Entries above the notebook are **dropped in RS1** (logged);
  RS2 hands them down.
- Tests: the model, the codec, the migration, a pure `ReplayPlan.of(stack, alive, soilExists,
  serviceAvailable)` skeleton with the notebook-only arm.

### D2 — The chain above (RS2)

- **`ReplayPlan`** grows its two remaining arms: `Notebook(id, viaLink, above: List<Surface>)` ·
  `LibraryLevel(top: Surface, calendarBeneath: Boolean)` · `Nothing`. Above-lists are normalized
  to the two legal shapes (`[X]` or `[CALENDAR, SCRATCH_PAD]`); anything else is truncated to its
  first legal prefix, logged.
- **Library level:** after the grid's first layout and the entries' `refresh()` (service
  discovery), `calendar.open()` or `scratchPad.open()`; for the pair, `scratchPad.open()` with
  `reopenCalendarAfterPad = true`.
- **Notebook level:** `EXTRA_RESUME_ABOVE` (`ArrayList<String>` of surface names) read once on a
  cold create into `resumeAbove`, consumed at the end of the page-load tail where `opened = true`
  lands (both the ordinary open and `openIntoEditor`): `CALENDAR` → `calendar.open()` ·
  `SCRATCH_PAD` → `scratchPad.open()` · the pair → pad + latch · `DOCUMENT_EDITOR` →
  `documentEntry.open()` if a document row exists for the landing page (else drop), or nothing for
  a text document (already launched). The reopen runs **after** the "Opening…" overlay is down and
  after `paper.releaseForHandoff()` via the entry's `beforeLaunch`, exactly as a tap would.
- **Own key:** no change to the prompt; `resumeAbove` simply waits behind it because `opened` does.
- **Walk (adb, Nomad):** open notebook → calendar → `am force-stop` → `am start` Bootstrap →
  `mResumedActivity` is `CalendarActivity` over `NotebookActivity`; Back → the notebook at its
  page; Back → the library. Same for the pad, the editor, the calendar's pad door (pad on top,
  Back → calendar), the library-level calendar, a deleted notebook (library, one log line), an
  uninstalled calendar (notebook alone), a text document (editor once, not twice). By hand: an
  own-key notebook (prompt → cancel → library; prompt → key → calendar comes back).

### D3 — Docs, ledger, freeze (RS3)

- `docs/library.md` § Launch restore (new; the prefs table row; the replay, the gates, the drop
  rules) · `docs/notebook.md` § Cold-launch restore (rewritten: the chain, `EXTRA_RESUME_ABOVE`,
  the own-key wait, the text-document rule) · `docs/extensions.md` (a boundary row: the host
  reopens a screen through the entry, never the Intent; nothing new crosses) · `docs/links.md`
  (the via-link flag now rides the stack). `PARITY_BACKLOG.md` item 7 DONE and the file's status
  line. Both `CLAUDE.md`. `RATTA_PLAN.md` header. Memory.

## Phases

### ✅ RS1 — The stack (landed 2026-09-09) (Fable the model + the notebook's lifecycle wiring · Opus the entries and the library replay on a Fable brief · Sonnet tests fan-out · Sonnet adb walk)

**Questions to resolve at phase start:** version (stays `0.1.0-ratta` unless said otherwise);
whether the token is saved in `onSaveInstanceState` or re-minted (planner: saved — a recreate
must refresh in place, not duplicate).

- Read first: `BrowseState.kt` whole; `LibraryActivity` 170–260 + 368–391; `NotebookActivity`
  836–860, the four clear sites (≈890, 991, 1158, 3951), `onResume`, `onSaveInstanceState`;
  `ExtensionScreenEntry` `open`/`onResult`/`close`; `DocumentEditorEntry` `open`/`onResult`/`close`.
- Walk: today's behaviour unchanged (notebook comes back, page kept, via-link trail kept, cancelled
  own-key open lands on the library and does not retry); the migration from a pre-arc install.

### ✅ RS2 — The chain above (landed 2026-09-09) (Opus on a Fable brief · Fable review of the lifecycle edges · Sonnet adb walk · own-key by hand)

**Questions to resolve at phase start:** version; whether the library-level reopen waits for the
entry's first `refresh()` or for a service-discovery callback (planner: the existing `refresh()`
in `onResume` runs before the first-layout listener — verify the order on the Nomad before
choosing).
**Answered 2026-09-09:** version stays `0.1.0-ratta`; **the replay runs its own discovery** — the
entries gain a `suspend fun discovered(): Boolean` (runs `discover`, sets `ref`, shows/hides the
button) and every reopen awaits it before `open()`. Chosen from the code, not the Nomad:
`refresh()`'s discovery is IO in one coroutine and the first-layout replay is another after two
index reads, so their finishing order is a race either way.

- Read first: `openSession`'s tail where `opened = true` (both arms); `openIntoEditor`;
  `DocumentHostHooks` — what `open()` does without a document row; `onCalendarClosed`/`onPadClosed`
  on both hosts; `EXTRA_INITIAL_PAGE_ID`'s consume.
- Walk: D2's list.

### ✅ RS3 — Docs, ledger, freeze (landed 2026-09-09) (Sonnet docs fan-out · Fable read-back, ledger, backlog, CLAUDE.md, memory · no code, no code review)

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- Surface names in prefs are the enum names; an unknown name drops the entry, not the stack.
- The library-level replay opens **one** screen; the notebook-level replay opens one screen or
  the calendar → pad pair. Nothing deeper exists in SN.
- No "Opening…" overlay for a reopened extension screen beyond what its entry already shows.
- Export, Tags and the pickers are not targets; `TemplatesActivity`/`BackupActivity` are not
  targets (decision 1).
- A replayed notebook is recorded in `RecentsPrefs` exactly as a tapped one is (it goes through
  the same `onCreate`).
- **A text document consumes the whole above-list on its editor launch** (RS2): a `DOCUMENT_EDITOR`
  entry silently (the route is already launching the editor), anything else logged and dropped —
  a text document reopens into its editor and nothing stands on a page it never shows. If the
  editor extension is missing the pages come up with nothing above them.
- **The reopen awaits the entry's own discovery** (`discovered()`), never `isAvailable` — the
  RS2 phase-start answer.

## Standing traps that bind this arc

- **ActivityResult callbacks run BEFORE `onResume`** — the pop at the top of `onResult`, the
  markTop in `onResume`; never assume the other order.
- **`IndexGuard.ready` first** — an Activity that bounces must not attach; the bounce check is
  already the first line of every `onCreate`, keep the attach after it.
- **`am start` onto an already-RESUMED activity fires no `onResume`** — HOME or `force-stop`
  first in every walk step.
- **Walk the `.dev` package**, verify `mResumedActivity` before any screencap conclusion; the
  Nomad sleeps behind a PIN — check `mCurrentFocus` first.
- **A Binder call cannot be cancelled** — the reopen reuses the entries' existing timeouts; add none.
- **EPD handoff:** the reopen goes through the entry's `beforeLaunch` (`releaseForHandoff`) like a
  tap; never launch an extension paper screen from the host without it.
- **Two handlers reading one piece of state: order the write after the read** — the cold-launch
  read of the stack in `onCreate` precedes the `onResume` reset.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **GONE, never disabled; a screen that explains itself and then leaves, leaves on DISMISS.**
- **Backing out of a live notebook through the app before installing** keeps the EPD pin from
  leaking; `force-stop` in a walk is the one sanctioned exception (it is the door under test).
- **Doc agents never run git, never revert files they did not create.**
- **`am force-stop` on the host alone is NOT a device death** (RS1 walk): the extension screen on
  top lives in its own process and stays on the glass, and `am start` is *delivered to the
  currently running top-most instance* — no cold launch happens. A restore walk kills the
  extension process(es) too (`am force-stop …ext.calendar.dev` / `…ext.scratchpad.dev` /
  `…ext.document.dev`), then `am start`s Bootstrap. **The order is HOST FIRST, then the
  extensions** (RS2 walk): an extension killed while the host still lives hands the host a
  cancelled result, whose `onResult` pops the entry — the stack then reads `[NOTEBOOK]` and the
  walk reports a drop that never happened. One shell command for all four force-stops.
- **Hand-writing the prefs file over `run-as`:** a piped heredoc into `run-as … sh -c "cat > …"`
  fails on the Nomad; `adb push` the XML to `/data/local/tmp` and `run-as … sh -c "cat
  /data/local/tmp/x > shared_prefs/sn_view_state.xml"` instead (host force-stopped first).
- **`adb shell dumpsys window` on the Nomad does not print `mResumedActivity`** — use
  `dumpsys activity activities | grep mResumedActivity`.

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

One phase per session; read this file whole at phase start, flip the phase to 🔄, ask its
phase-start questions **one at a time**, then code. Fable plans / seams / reviews; Opus features;
Sonnet scaffold, layouts, resources, docs; ≤ 5 background agents. JVM tests for every pure piece;
the user gets a **short numbered checklist** only for what needs a hand or an eye. **Nomad only**
(SNN `SN078D10012852`); the Manta only on explicit ask. Commit + push only when every suite is
green (or the user's all-clear), after docs / memory / CLAUDE.md are in; then the user runs
`/clear`. A long explanation and an `AskUserQuestion` never share one turn — explain, wait, then
ask.

## Ledger

*(one Outcome entry per phase as it closes)*

### RS1 — Outcome (2026-09-09)

**Landed:** `data/prefs/SurfaceStack.kt` — `Surface { NOTEBOOK, CALENDAR, SCRATCH_PAD,
DOCUMENT_EDITOR }`, `SurfaceEntry(token, surface, notebookId?, viaLink)`, the pure
`SurfaceStackCodec` (decode treats the blob as untrusted: corrupt → empty, an unknown surface name
or blank token drops that entry only; `attach` appends or refreshes in place by token; `markTop`
drops everything above the token and is a no-op for an unknown token; `pop` removes by token;
`migrate` reads the pre-arc `lastOpenNotebookId`/`lastOpenViaLink` as a one-entry NOTEBOOK stack
only when no `surfaceStack` key exists) and the prefs door `SurfaceStack` (`sn_view_state`, key
`surfaceStack`; `snapshotAndClear` reads once, migrates, removes all three keys). `BrowseState`
lost the two legacy properties. `library/ReplayPlan` — pure `of(stack)` → `Notebook(id, viaLink,
above)` (the above-list stops at a second NOTEBOOK) · `LibraryLevel(top, calendarBeneath)` ·
`Nothing`. **NotebookActivity:** `stackToken` minted per instance, saved under `KEY_STACK_TOKEN`
(phase-start answer: saved, so a recreate refreshes in place); attach in `onCreate` where the old
id was written; `markTop` first line of `onResume` (guarded on init — the IndexGuard bounce);
`pop` at the four old clear sites (recovery declined, passphrase cancelled, `failOpen`, `close`).
**LibraryActivity:** `stack.snapshotAndClear()` in `onCreate` on a cold launch into a local copy
(before `onResume`'s `reset()`); `replayStack()` replaces `reopenLastNotebookIfNeeded` in the
first-layout listener with the same three gates; RS1 replays the notebook only — entries above it
and a library-level entry are logged and dropped. **Entries:** `ExtensionScreenEntry` takes
`surface`, mints one token per entry instance, exposes `stackEntry`, pushes right after
`launcher.launch`, pops synchronously at the top of `onResult` and in `close()`;
`DocumentEditorEntry` the same (`reconnect` untouched). **The calendar → pad latch is
structural:** both hosts' `onCalendarClosed` re-attach `calendar.stackEntry` before
`scratchPad.open()` — the callback is posted, so it runs after the host's `onResume` markTop.

**Tests:** 1564 → **1591** `:app` (`SurfaceStackCodecTest` 15 + `ReplayPlanTest` 12), 2945 →
**2972** across the modules, all green. Version
stays `0.1.0-ratta`. No code review (decision 6).

**Nomad walk (adb, by Fable):** the pre-arc migration — the device was left in a notebook under
the old build; force-stop, install, cold launch → the notebook came back and the prefs held a
one-entry stack with both legacy keys gone · new-format restore → the notebook · notebook →
calendar → pad → back → back read `NOTEBOOK CALENDAR SCRATCH_PAD` → `NOTEBOOK CALENDAR` →
`NOTEBOOK` in prefs at each step · killed behind the calendar → the notebook alone with
`restore: [CALENDAR] above the notebook dropped (RS1)` · document editor push/pop · notebook close
→ `[]` and a restart stays on the library · a library-level calendar → `CALENDAR`, killed → the
library with its drop line · a hand-written stack naming a dead notebook id plus a `BOGUS` surface
→ the library, `restore: the notebook is gone — chain dropped`, no crash, stack cleared. Not
walked: via-link (the flag rides the entry exactly as the old key did — codec-tested) and the
own-key cancel (the same `pop` on the same line; the Nomad library is all GLOBAL) — both by the
user's hand if wanted.

### RS2 — Outcome (2026-09-09)

**Landed:** `ReplayPlan.legalAbove` (the two legal shapes — one screen or `CALENDAR, SCRATCH_PAD`
— anything else cut to its longest legal prefix; a `NOTEBOOK` first is nothing) applied in `of`,
and `ReplayPlan.decodeAbove` (surface names off the Intent, an unknown name dropped, then
`legalAbove`). `ExtensionScreenEntry.discovered()` / `DocumentEditorEntry.discovered()` — the
awaitable body of `refresh()` (runs discovery, sets `ref`, shows or hides the button; `refresh()`
now launches it). **NotebookActivity:** `EXTRA_RESUME_ABOVE` (host-internal `ArrayList<String>`
of surface names, read once on a cold create beside `initialPageId`, ignored on a task rebuild);
`replayAbove()` as the last line of `loadCanvas` — consume-once, after `opened = true` and the
overlay is down, so behind the own-key prompt by construction; each arm awaits `discovered()` and
re-checks `standingForReplay()` (`opened && !closing && !isFinishing && !isDestroyed`) after every
suspension: `[CALENDAR]` / `[SCRATCH_PAD]` → the entry's `open()`; the pair → `openPadOverCalendar()`
(the three lines `onCalendarClosed` used, now shared: re-attach the calendar's entry, set the
latch, open the pad; calendar missing → chain dropped, pad missing → the calendar alone);
`[DOCUMENT_EDITOR]` → `documentEntry.open()` only when `session.documents.get(displayedPageId)`
answers a row (decision 4 — no seed flow, no recognition, nothing staged); `openIntoEditor(launch
= true)` consumes the list first (a bare `[DOCUMENT_EDITOR]` silently). **LibraryActivity:**
`openNotebook(…, resumeAbove)` rides the extra; `replayStack`'s notebook arm hands `plan.above`
down; `replayLibraryLevel` opens the calendar / the pad / the pad over the latched calendar with
the same discovery-await and drop lines, and logs a library-level `DOCUMENT_EDITOR` as dropped.
Every drop is one `Slog.d` naming the surface, never an id.

**Tests:** 1591 → **1606** `:app` (`ReplayPlanTest` +15: every legal shape unchanged, each
truncation, `decodeAbove` with unknown / all-unknown / null / empty), 2972 → **2987** across the
modules, all green. Version stays `0.1.0-ratta`. No code review (decision 6).

**Nomad walk (adb, Sonnet + Fable):** notebook → calendar / pad / document editor, each killed
(host first) and cold-started → the extension screen resumed over `NotebookActivity` with its
`restore: reopening […] above the notebook` line and the stack re-formed, Back → notebook →
library with the stack shrinking to `[]` · the calendar's pad door → pad on top, Back → the
calendar comes back, Back → notebook · library-level calendar, and the calendar's pad over the
library · a hand-written dead notebook id with a `CALENDAR` above → the library, `the notebook is
gone — chain dropped`, `[]` · the calendar uninstalled behind a `NOTEBOOK CALENDAR` stack → the
notebook alone with `the calendar is not installed — dropped` (reinstalled after) · a text
document (created for the walk from the New notebook screen's Text radio) killed behind its
editor → the editor exactly once (one `START … DocumentEditorActivity`, no `already showing`, no
`restore:` line), Back → library · crash log empty throughout. **Not walked:** the own-key
notebook (Nomad library is all GLOBAL) — by the user's hand if wanted: prompt → cancel → library
with the stack cleared; prompt → key → the calendar comes back.

**Trap found and recorded above:** the first walk agent killed the extensions before the host and
reported RS2 as "not built" — the host had popped the entry on the cancelled result.

### RS3 — Outcome (2026-09-09)

**Docs (no code):** `docs/library.md` — § Prefs (the `BrowseState` row loses `lastOpenNotebookId` /
`lastOpenViaLink`; a `SurfaceStack` row; the migration and the untrusted-decode rules) and a new
§ Launch restore (what it is, the surface allowlist, the stack model and who maintains it, the
structural calendar → pad latch, the replay's three plans and two arms, the drop rules, device-local
by rule, the Nomad walks, the host-first walk trap, the tests). `docs/notebook.md` — § Open (the
attach + `EXTRA_RESUME_ABOVE` beside `EXTRA_INITIAL_PAGE_ID`), § Close & lifecycle (markTop / the
four pops), § Cold-launch restore rewritten (the chain, `replayAbove()` behind the own-key prompt,
the arms, the text-document rule, the via-link flag on the entry), the entries' push/pop.
`docs/extensions.md` — boundary row 49 (a reopen goes through the entry, never a rebuilt Intent;
nothing new crosses; no extension knows a restore is happening). `docs/links.md` — the via-link
flag rides `SurfaceEntry.viaLink` now, the K4 rule kept. `PARITY_BACKLOG.md` item 7 DONE + the
status line + the header (**the backlog is closed — every item done**). Both `CLAUDE.md` (root:
the arc-32 bullet + "arcs 1–32 frozen, backlog closed"; app: the arc-32 entry with the shape as
built and the walk trap). `RATTA_PLAN.md` header. Memory.

**Final numbers:** 1606 `:app` / 2987 JVM tests across the modules, `API_VERSION` 9, fourteen
modules, g-paper 0.1.28, version `0.1.0-ratta`. No code review (decision 6). Arc 32 is complete
and frozen; arcs 1–32 are all frozen. **`PARITY_BACKLOG.md` has no open item — the next arc, if
any, is a fresh user decision.**
