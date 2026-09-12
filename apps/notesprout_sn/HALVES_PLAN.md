# HALVES_PLAN.md — Arc 35 "Halves": a Day send carries both halves

**Branch `ratta` · a fresh user decision (2026-09-10, during the og2sn migration walk on the Manta).
Read this file, not `RATTA_PLAN.md`, for any work on this arc. Standing rules in
`apps/notesprout_sn/CLAUDE.md` bind.**

## What this arc is

The calendar's top-bar **Send** on a **Day** page lands only the half on screen (AM or PM). The user
wants the whole day: **both halves, AM then PM, whichever half is showing**, each as its own papered
page after the displayed notebook page, exactly the arc-31 / HV5 page-send shape twice over.
Month, Week and every selection send are untouched.

## Decisions (wizard 2026-09-10 — binding)

| # | Decision |
|---|---|
| 1 | **One undo step** for the pair: undo removes both landed pages, redo brings both back. |
| 2 | **Always both halves.** An empty half lands as its timeline paper alone (HV5's rule for an empty whole-page send, per half). |
| 3 | Order is AM then PM regardless of which half was showing; the notebook ends on the PM page, the lasso armed on its ink. |
| 4 | The seam grows one appended `ICalendar` method, `advanceOutgoing()`, under **`API_VERSION` 10** — a compatible tail on arc 31 / HV4's pattern (`MIN_API_VERSION_FOR_CALENDAR_DAY_SEND` = 10, a method floor; `MIN_API_VERSIONS` untouched, a calendar declaring 9 is never asked and behaves as before). |
| 5 | Devices: the user tests on the **Manta** (release SN is installed there beside OG, the migrated library on it). Install there only on the user's word. |

## Design

- **`:ext-ink` `InkScreenActivity.send`** — after a whole-page `parkOutgoing`, one open hook
  `parkCompanionPages(page)` (suspend, default no-op). The pad never overrides it.
- **`:ext-calendar`** — `CalendarSession` keeps a FIFO of further parked pages
  (`OutboundPage(chunks, width, height, target)`); `advance()` pops the next into the base fields
  `takeOutgoing` / `outgoingTarget` already read, false when empty; `clear()` drops it.
  `CalendarActivity.parkCompanionPages`: on a Day target, read the other half off the store
  (`CalendarStore.readPage`, IO), chunk it, and re-park so **AM is first** and PM is queued (an
  unminted half parks zero chunks at the showing page's size — its paper is still a page).
  `CalendarService.advanceOutgoing()` → `CalendarSession.advance()`. Manifest declares **10**.
- **`:app`** — `HeldInkPoint.advanceOutgoing` (default false), `HeldInkClient.advanceOutgoing()`,
  `CalendarClient` overrides it. `ExtensionScreenEntry.onResult`: drain + paper as today, then
  while the point declares ≥ 10 and `advanceOutgoing()` is true, drain + paper again; `onDrained`
  now takes the **list** (the pad and every single send pass one). `NotebookActivity
  .receiveCalendarPages`: one `runPageOp`, `session.receivePage` per drain in order (each inserts
  after the page the previous one landed on), `Action.PagesReceived(snapshots)` when more than one
  (undo = reconcile to the first snapshot's `before` with every created id deleted; redo = the last
  snapshot's `after` with them revived), `navigateTo` the last, lasso on its ink.
- **Tests:** `ExtensionContractTest` (10, the new floor), `CalendarSessionTargetTest` (queue,
  advance, clear), `NotebookUndoTest` (`PagesReceived` reports the last page).

## Phases

### ✅ HA1 — Seam + calendar + host (Fable, 2026-09-10)
### ✅ HA2 — Docs, ledger, freeze (Fable, 2026-09-10)

**ARC COMPLETE + FROZEN 2026-09-10.** No code review (the user's call). No next arc without a user
decision.

## Ledger

- 2026-09-10 — wizard answered (1–3), design fixed, HA1 started.
- 2026-09-10 — HA1 landed as designed. Extras the walk asked for: a **"Receiving from the
  calendar…" / "…scratch pad…" box** (`RecognizingOverlay` with `EntryWording.receivingRes`) over
  the caller from the send's result callback until the landing is done — the two-page send runs
  for seconds and read as a hang. Gates: 3079 JVM tests across the modules (1654 `:app`, 322
  `:ext-calendar`, 232 `:extension-api`, 52 `:ext-ink`), `:app` + `:ext-calendar` release APKs
  signed with the debug keystore, verified, installed on the **Manta** at the user's word. Walked
  by the user: Day send from either half → AM then PM after the current page, ending on PM; undo
  removes both, redo brings both back; the box shows for the wait.
- 2026-09-10 — HA2: `docs/calendar.md` § Both transfers, `docs/extensions.md` (API ledger 10,
  the calendar's declaration, § `advanceOutgoing`), `docs/notebook.md` (undo table +
  § The received page), `docs/scratchpad.md` (the box), both CLAUDE.md files, `RATTA_PLAN.md`
  header, memory. Frozen.
