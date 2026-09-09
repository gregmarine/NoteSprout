# PAGE_PLAN.md — Arc 30 "Page" (Notesprout SN, branch `ratta`)

**Standalone plan for page erase and page export** — item 5 of `PARITY_BACKLOG.md`. This file is
the cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this file
is enough. `LOOP_PLAN.md` is the shape this file copies.

**Status: 🔄 IN PROGRESS — wizard locked 2026-09-08.** PE1 ✅ (2026-09-08) · PE2 ⬜ · PE3 ⬜. When frozen the
references will be `docs/notebook.md` (the page sheet, the erase, the undo row) and `docs/export.md`
(the scope seam, the notebook door).

**Phase code:** **PE** — two letters, the arc-29 precedent (every single letter is spoken for).

---

## What this arc is

SN's notebook page sheet (one-finger long-press) offers Copy · Cut · Paste · Page template · Delete.
og's canvas "Page" menu also offers **Erase Page** and a per-page **Export**. This arc adds both.

- **Erase page** is a content-only wipe: soft-delete every live object on the page in one
  transaction, keep the page row, its `order`, its size and its template. One undo entry.
  Type-agnostic the way `deleteCurrent()` already is — strokes, headings, links, text, shapes,
  sticky notes and their children, all through the one `liveDescendantIds(pageId)` query.
- **Page export** is the notebook's Export reachable at page scope: a row on the page sheet that
  closes the notebook, runs `ExportActivity` seeded to that page, and reopens the notebook after.
  The Export screen gains a scope latch (This page · Whole notebook) **only when entered from the
  page sheet**; from the library it is whole-notebook with no latch, as today.

**Nothing crosses the seam.** `ExportSpec` has no scope field and gains none: the host filters
the page rows before it bakes a bundle or assembles a document, so every exporter — including item
6's future image exporter — inherits page scope without knowing it exists. No ninth point, no
`API_VERSION` bump, no new row type, no `.soil` version bump, no g-paper change.

**Item 6 stays out.** Images, presets, page-to-template and calendar export are item 6. This arc
builds the scope seam they will ride on and nothing more.

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `NotebookActivity.showPageSheet()` (`ActionSheetDialog`, rows absent never disabled; every row through `runPageOp`) | five rows | two more rows: **Erase page** after Page template, **Export page** after Delete (D1, D3) |
| `confirmDeletePage()` → `doDelete()` → `session.store.drain()` → `session.deleteCurrent()` → `undo.record(Action.Page(snap))` | delete | the erase copies the chain: confirm → drain → `eraseCurrent()` → `Action.PageErased` (D1) |
| `NotebookSession.deleteCurrent()`: `dao().liveDescendantIds(victim.id)` + one `withTransaction { softDelete }` | delete | `eraseCurrent()` = the content half alone, page row untouched (D1) |
| `SoilDao.liveDescendantIds(pageId)` — strokes/headings/links/documents/text/shapes/stickies + link-wrapped + sticky children | the type-agnostic id list | **the erase's whole read** — no new SQL (D1) |
| `SoilDao.softDelete(ids, at)` / `restore(ids, at)` | replay by id | `PageErased` replays through these two alone (D2) |
| `NotebookUndo.Action` — 23 kinds, two exhaustive replay `when`s (`revert` / `reapply` in `NotebookActivity`) | | **`PageErased(pageId, objectIds)`** (D2) |
| `refreshToPage(pageId)` after a multi-store restore (`Deleted`'s replay) | repaint | the erase and both replays end on it (D2) |
| `close(andThen)` + `startActivity(intent(...))` — the link-jump / recents close-then-launch | notebook → notebook | notebook → Export (D3) |
| `NotebookActivity` opens at its **bookmark** (the page it was closed on) | recents, launch | the reopen after Export lands on the exported page for free (D3) |
| `ExportActivity.intent(context, notebookId, notebookName)` + `EXTRA_NOTEBOOK_*` | whole notebook | `EXTRA_PAGE_ID` + `EXTRA_RETURN_TO_NOTEBOOK` (D3, D4) |
| `ExportRender.bake`: `dao.childrenOfType(notebookId, TYPE_PAGE)` → `plan(rows)` (pure, tested) → `PageBake` list | every page | the rows filtered to the scope **before** `plan` (D4) |
| `DocumentPdfRender.render` (Document on white pages) · `ExportText.markdownOf` (`pageDocumentsIn` joined over `childrenOfType(TYPE_PAGE)`) | every page | the same filter (D4) |
| `ExportActivity.loadCandidates()` — drops exporters that cannot serve (non-renderable, document-format-without-document) | | drops `SOURCE_SOIL` exporters at page scope (D4) |
| `ExportOpen.readOnly` guards 1–5 (file there · not held · key · read-only open · seal) | the cold-file invariant | untouched — the notebook is **closed** before Export runs (D3) |
| `Editor.Latch` / `LatchGroup<T>` (arc 24) | the calendar's word latches | the scope latch on the Export screen (D4) |
| `ic_trash`, `ic_download`, `ic_eraser` in `:sn-screen` | | Export page = `ic_download`; Erase page needs a new Tabler outline (`eraser`-family, check og's `drawable/` first — og uses `ic_erase_all`) (D1) |

## Decisions (wizard 2026-09-08 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Erase confirm | **Confirm dialog** — "Erase this page?" Cancel · Erase (og's shape, the sheet's Delete precedent), then one undo entry. |
| 2 | Export door | **A page-sheet row "Export page"** after Delete. Not a toolbar button (a twelfth falls off the Nomad's bar — arc 29). |
| 3 | Scope control | **Library door = whole notebook, no control** (as today). **Page-sheet door = defaults to this page, with a latch to switch to the whole notebook.** |
| 4 | Exporters at page scope | **PDF + Document; Soil hidden.** Page-bundle exporters get the filtered bundle; the Document exporter gets that page's document. A one-page `.soil` would be a new copy-with-filter engine and og does not offer it either. |
| 5 | Door handoff | **Close, export, reopen.** The notebook closes the way it does for a link jump, Export runs, and on finish (any outcome — exported, cancelled, failed) relaunches the notebook, which opens at its bookmark = that page. Undo history dies with the close, as on every close. Seal-in-place was declined (a new state machine on a ~3900-line screen). |
| 6 | Phases / review | **Three phases, PE1–PE3, no code review.** PE3 is docs + freeze only (arc 29's shape). |
| 7 | Naming / version | **Arc 30 "Page"**, this standalone `PAGE_PLAN.md`, reference folded into `docs/notebook.md` + `docs/export.md`. App version stays `0.1.0-ratta` (a phase-start question every phase, as always). |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **An erase of an empty page is nothing** — no transaction, no undo entry, no repaint; the
  confirm dialog is still shown (the sheet row is always present while the page exists), but
  Erase on an empty page returns silently. Planner call; overridable at PE1 start.
- **Erase is by id, replay is by id.** `PageErased` carries only `objectIds`; revert =
  `restore(ids)`, reapply = `softDelete(ids)`. No per-type snapshot (the `Deleted` shape) is
  needed because nothing moves and nothing is re-minted — the rows stay where they are, dated
  out. Sticky children and link-wrapped children are in the list already.
- **The page's `document` row is part of the erase.** `liveDescendantIds` includes `document`;
  the page document is content of the page and goes with the rest, and comes back on undo like
  everything else. (If the user disagrees at PE1 start, exclude `TYPE_DOCUMENT` from the erase
  set — one filter.)
- **The scribble / eraser rule holds:** the host repaints the page **once**, through
  `refreshToPage`, after the transaction — never per object.
- **Scope is a host-side page-id list.** `ExportRender`, `DocumentPdfRender`, `ExportText` each
  take an optional `pageIds: Set<String>?` (null = all) and filter the `TYPE_PAGE` rows they
  already read; the pure `plan()` seam is untouched. `ExportSpec` and every exporter descriptor
  are untouched.
- **Page scope hides, never disables** (GONE rule): at This page the exporter list omits
  `SOURCE_SOIL`; if the remembered `lastExporter` is Soil the default falls to the first shown.
  Flipping the latch back to Whole notebook re-shows Soil. The latch itself is absent (not
  disabled) from the library door.
- **Endnotes (bundle v2) follow the page**: a page-scope PDF carries only the sticky endnotes of
  the exported page. `Endnotes`' sources are collected from the baked pages, so the filter covers
  it — verify at PE2 rather than assume.
- **The reopen is unconditional.** Export finishes → `startActivity(NotebookActivity.intent(id,
  name))` → `finish()`, whether the export was written, cancelled or refused; a notebook the user
  was in is a notebook they come back to. If the notebook cannot reopen (deleted meanwhile,
  locked) the library's own failure path handles it.
- **The returned notebook opens at its bookmark**, which the close wrote for the page the sheet
  was on. No `EXTRA_PAGE` on the notebook intent.
- **A busy page-op refuses the door.** `Export page` goes through `runPageOp` like every sheet
  row, so a page insert/delete in flight finishes first; `session.store.drain()` runs inside
  `close()` already (the pageOps lock + seal).
- **Frame silence:** the confirm dialog and the sheet are deliberate-tap chrome frames (ledgered);
  the erase repaint is one frame.

---

## Design (binding unless a phase-start question reopens it)

### D1 — Erase page in the notebook (PE1)

- `NotebookSession.eraseCurrent(): List<String>` — `val ids = dao().liveDescendantIds(page.id)`;
  empty → return empty (no transaction); else `db.withTransaction { dao().softDelete(ids, now) }`;
  returns `ids`. No renumber, no page row change, `currentIndex` unchanged.
- `NotebookActivity`: `confirmErasePage()` (AlertDialog, `erase_page_title` "Erase this page?",
  positive `erase_confirm` "Erase", negative cancel — the `confirmDeletePage` shape) →
  `runPageOp { doErase() }`; `doErase()` = `session.store.drain()` (queued stroke commits must
  land before the id snapshot — the delete's rule) → `val ids = session.eraseCurrent()` → if
  non-empty `undo.record(Action.PageErased(pageId, ids))` → `refreshToPage(pageId)`.
- Sheet row: `sheet.addAction(R.drawable.ic_erase_page, getString(R.string.erase_page_action))
  { confirmErasePage() }` between Page template and Delete. Long-press hint not needed (a sheet
  row carries its word).
- Icon `ic_erase_page` in `:sn-screen`: check og's `ic_erase_all` first; if it is Tabler-outline
  24 dp, copy it byte-for-byte; else draw the Tabler `eraser` variant that reads as "all".
- Strings in `:app` `values/strings.xml` beside the delete-page strings.

### D2 — The undo kind (PE1)

- `NotebookUndo.Action.PageErased(override val pageId: String, val objectIds: List<String>)`.
- `revert`: `session.dao().restore(a.objectIds, now)` via a session method (`session.restoreIds`
  / reuse `reconcile`-style helper — the session owns the DAO); `session.store.drain()`;
  `refreshToPage(a.pageId)`. `reapply`: `softDelete` the same list; drain; refresh. Both
  exhaustive `when`s gain the arm (compile-checked).
- Stroke rows: the engine's stroke store (`session.store`) caches strokes for the page — read how
  `Deleted`'s replay revives strokes (`store.revive(ids)`) at phase start and use the same road if
  the store keeps an in-memory mirror that a bare DAO restore would miss. **This is the one
  thing PE1 must read before coding**: if `store.revive` is required for strokes, `PageErased`
  carries the stroke ids apart (`strokeIds` + `contentIds`) and the replay uses `store.revive` +
  `restore(contentIds)`, exactly `Deleted`'s split. The erase then also goes through
  `store.erase`-style removal for strokes if the store is write-through. Decide from the code,
  record in the ledger.
- `NotebookUndoTest` gains the kind both ways; `FakeSoilDao` untouched (no new SQL).

### D3 — The door (PE2)

- Sheet row: `sheet.addAction(R.drawable.ic_download, getString(R.string.export_page_action))
  { runPageOp { exportPage() } }` after Delete — only when an exporter is installed
  (`ExtensionRegistry.exporters(this).isNotEmpty()`, the library's `canExport` rule; the sheet is
  built on Main, so the registry answer is cached at open / asked once — read `showCardSheet`'s
  `sheetPending` idiom and pick the cheaper one).
- `exportPage()`: `val pageId = session.currentPage.id` → `OpeningOverlay.showThen { close {
  startActivity(ExportActivity.intent(this, notebookId, name, pageId = pageId, returnToNotebook
  = true)) } }`. `close()` drains, captures the cover, seals, writes the bookmark — the recents
  path verbatim.
- `ExportActivity`: `EXTRA_PAGE_ID` (nullable) + `EXTRA_RETURN_TO_NOTEBOOK` (boolean). On
  `finish()` from any road when the return extra is set: `startActivity(NotebookActivity.intent(
  this, notebookId, notebookName))` first. One private `leave()` that every Cancel / done /
  problem-dialog path already funnels through — read at phase start; if the paths are scattered,
  override `finish()` once.
- The page-sheet door and the library door share one `ExportActivity`; the library's intent
  carries neither extra and behaves exactly as today.

### D4 — Scope on the Export screen and in the renders (PE2)

- `ExportScope` (host, pure): `sealed`/enum `THIS_PAGE(pageId)` · `WHOLE`. Seeded `THIS_PAGE`
  when `EXTRA_PAGE_ID` is present, else `WHOLE`.
- The latch row (`LatchGroup<ExportScope>` on `Editor.Latch` buttons: **This page · Whole
  notebook**) sits at the top of the options, **present only when `EXTRA_PAGE_ID` is set**. The
  header shows the notebook name as today. Flipping the latch re-runs the candidate filter
  (Soil in/out) and the option rows the chosen exporter shows.
- `loadCandidates()`: at `THIS_PAGE`, drop `info.sourceKind == SOURCE_SOIL`; the default choice
  rule (`standing ?: remembered ?: first`) unchanged, so a remembered Soil falls to the first
  shown. `exportPrefs.lastExporter` is still written on a page export (a format choice is a
  format choice).
- `runExport`: the page set `pageIds = scope.pageIds()` (null at WHOLE) threads into
  `renderedPages` → `ExportRender.render(..., pageIds)`, `renderedDocumentPages` →
  `DocumentPdfRender.render(..., pageIds)`, `assembledDocument` → `ExportText.assemble(...,
  pageIds)`. Each filters its `TYPE_PAGE` rows before its existing plan. A filter that yields no
  page (the page vanished between the sheet and the run) → the existing `Problem.EMPTY` sentence.
- The output filename: today `<notebook>.<ext>`; at This page `<notebook> — page N.<ext>` where N
  is the page's 1-based order (the SAF create dialog shows it; cloud upload the same name).
  Planner call; one string.
- Verification (`ExportVerification`) unchanged — bytes are bytes.
- Tests: `ExportScope` pure; `ExportRender.plan` stays as is; a filter helper `pagesInScope(rows,
  pageIds)` pure + tested once, used by all three renders; `loadCandidates`' Soil-drop rule as a
  pure predicate + test (`ExportCandidates.serves(info, scope)`).

---

## Phases

### ✅ PE1 — Erase page (landed 2026-09-08) (Opus code on a Fable brief · Fable review · walk by hand)

**Questions to resolve at phase start:** app version (stays `0.1.0-ratta`?); whether the page's
`document` row is part of the erase (planner: yes); whether an empty page's Erase is silent
(planner: yes).

- D1 + D2. The one read-before-coding: how `Deleted`'s replay revives strokes (`store.revive`)
  and whether a bare DAO restore is enough for the engine's stroke store — decide the
  `PageErased` payload from that.
- Tests: `NotebookUndoTest` both replay directions; a `NotebookSession` test if the fake DAO
  covers `liveDescendantIds` (read `FakeSoilDao`).
- **Walk (by hand, Nomad):** page with ink + a heading + a text + a shape + a sticky + a link →
  Erase page → confirm → everything gone in one frame, page and template stay, page count
  unchanged; undo → everything back in place (sticky content included, open it); redo → gone
  again; Cancel erases nothing; an empty page's Erase does nothing; page flip and back after an
  erase shows the erased page; `am crash` → reopen shows the erased state persisted.

### ⬜ PE2 — Page export (Opus code on a Fable brief · Sonnet XML · Fable review · walk by hand)

**Questions to resolve at phase start:** app version; the page-scope filename suffix (planner:
"— page N"); latch placement (planner: first row of the options panel).

- D3 + D4. Read first: `ExportActivity`'s leave paths (one funnel or scattered), `Endnotes`'
  source collection (follows the baked pages or reads the notebook?), `ExportText.markdownOf`'s
  page join (the page-document fallback at page scope = that page's document or nothing).
- Tests: `ExportScope`, `pagesInScope`, `ExportCandidates.serves`; existing export suites green.
- **Walk (by hand, Nomad):** page sheet → Export page → the notebook closes, Export opens with the
  latch on This page and Soil absent; PDF export of one page to SAF → one page in the file, its
  endnotes only; Document export of one page → that page's text; flip the latch to Whole notebook
  → Soil reappears, all pages export; Cancel → the notebook reopens on the same page; a failed
  export (cloud offline) → problem dialog → the notebook reopens; the library door unchanged (no
  latch, Soil present); with no exporter installed the sheet has no Export row.

### ⬜ PE3 — Docs, ledger, freeze (Sonnet docs in parallel · Fable read-back · no code review, no code)

- `docs/notebook.md`: the page sheet's seven rows, the erase under § Page operations, the undo
  table's `PageErased` row, the frame-silence ledger entry, the JVM test list.
  `docs/export.md`: § Scope (host-side filter, the door, the latch, the Soil rule, the reopen),
  the `EXTRA_*` table. `docs/objects.md`: one sentence that Erase page takes every kind.
  `docs/library.md`: the library door is no longer the only one (fix the "only entry point"
  sentence and the `ExportActivity.kt:400` comment in code at PE2).
- `PARITY_BACKLOG.md` item 5 → DONE; `RATTA_PLAN.md` header + ledger line; app `CLAUDE.md` arc
  line; root `CLAUDE.md` branch line; memory. Freeze.

---

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- The Erase page row sits between Page template and Delete; Export page is the last row.
- Erase confirm wording: title "Erase this page?", body none, buttons Cancel · Erase.
- Page-scope filename `<notebook> — page N.<ext>`.
- `lastExporter` is remembered on a page export too.
- Walks are **by hand on the Nomad** (the Haiku wander trap); adb can drive the sheet and Export
  but not the pen.

## Standing traps that bind this arc

- **Two exhaustive `when`s over `Action`** in `NotebookActivity` (`revert` / `reapply`) — a new
  kind that misses one is a compile error.
- **`session.store.drain()` before any id snapshot** — queued stroke commits must land first (the
  delete's rule; a stroke drawn a moment ago is otherwise missed by the erase and orphaned).
- **The cold-file invariant** — `ExportOpen` guard 2 refuses a held `.soil`; the notebook must be
  fully closed (`close()` sealed) before `ExportActivity` starts, which `close(andThen)` guarantees
  by ordering.
- **`close()` is one-way** — `closing` flips, undo dies, the cover is captured. Nothing after it
  may touch `session`.
- **GONE, never disabled** — the scope latch is absent from the library door; Soil is absent at
  page scope.
- **`Widget.Notesprout.TextButton` / `LatchButton` set no `layout_width`** — the XML must; inflate
  crashes otherwise (arc 23 trap).
- **The dialog-border trap** — an opaque custom root hides a `<shape>` stroke; the confirm dialog
  is a plain `AlertDialog` through `Dialogs`.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Check og's `drawable/` before drawing a "fresh" icon** — `ic_erase_all` exists there.
- **Backing out of a live notebook through the app before installing** keeps the EPD pin from
  leaking.
- **Doc agents never run git, never revert files they did not create** (the Z6 trap).

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

### PE1 — Erase page · Outcome (2026-09-08)

- **Phase-start answers:** version stays `0.1.0-ratta`; the page's `document` row **is** erased;
  an empty page's Erase is **silent** (dialog shown, confirm writes nothing).
- **The read-before-coding, decided:** `StrokeStore` keeps **no in-memory mirror** — `revive` is a
  bare `dao.restore` queued on the writer, and `reconcile` already restores a page delete's content
  ids with the same bare DAO call. So `PageErased(pageId, objectIds)` is ids only, no stroke split;
  revert = `NotebookSession.restoreIds`, reapply = `NotebookSession.eraseIds` (each one
  `withTransaction`, then `mirror(now)` to touch the index), then `store.drain()` +
  `refreshToPage` — one repaint.
- `NotebookSession.eraseCurrent(): List<String>` — `liveDescendantIds` → empty = return, no
  transaction; else one `softDelete`. Page row, `order`, template, `currentIndex` untouched.
- `NotebookActivity`: `confirmErasePage()` (plain `AlertDialog` through `Dialogs.style`,
  `erase_page_title` / `erase_confirm` / `cancel`) → `runPageOp { doErase() }`; `doErase` = drain →
  `eraseCurrent` → record if non-empty → `refreshToPage`. Both exhaustive `when`s gained the arm.
  Sheet row **Erase page** between Page template and Delete; `ic_erase_page` in `:sn-screen` is
  og's `ic_erase_all` byte-for-byte (Tabler `file-x`, 24 dp / stroke 2 / round).
- No `NotebookSession` test: the session opens a real Room DB (`FakeSoilDao` cannot drive it).
  `NotebookUndoTest` +1 (ids ride the stack, own kind vs `Page` / `Deleted` / `LassoErased`).
- **Tests: 1473 `:app`** (was 1472) / 2833 total. NUL scan clean.
- **Nomad walk passed (all five items)**, incl. `am crash` → reopen persists the erased state.
- Code written by Fable directly (the seams were settled; no Opus brief needed). No code review
  (decision 6).
