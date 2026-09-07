# LOOP_PLAN.md — Arc 29 "Loop" (Notesprout SN, branch `ratta`)

**Standalone plan for the lasso eraser** — item 4 of `PARITY_BACKLOG.md`. This file is the
cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this file
is enough. `OBJECTS_PLAN.md` is the shape this file copies.

**Status: 🔄 IN PROGRESS — wizard locked 2026-09-06.** LE1 ✅ · LE2 ✅ · LE3 ⬜ · LE4 ⬜.

**Phase code:** **LE** — the first two-letter code; every single letter A–Z is spoken for in
`RATTA_PLAN.md` (H and L went to arcs 28 and 27).

---

## What this arc is

SN has two erase paths: the **point eraser** (`Tool.ERASER`, 15 px, whole-stroke, whole-object
for host content since g-paper 0.1.4) and **scribble erase** (hardwired on, one
`onScribbleErased(strokeIds, contentIds)` per gesture since 0.1.23). og Notesprout carries a third
as its own armable tool — `lassoEraser` — which deletes everything a drawn loop takes.

This arc adds that third path as **an engine tool**, `Tool.LASSO_ERASER` in g-paper (**0.1.28**),
and arms it on **all four paper surfaces**: the notebook, the sticky editor, the scratch pad and the
calendar. Nothing new is stored, nothing crosses a seam, no `API_VERSION` bump, no ninth point. The
host's whole job is the same as for every other erase: mirror what the engine reports into rows and
one undo entry.

**Why the engine and not the host.** The lasso outline never reaches the host (g-paper exposes
`Selection(strokeIds, contentIds, bounds)`, not the polygon), and a host-only version — arm
`Tool.LASSO` under a flag and delete in `onSelectionCreated` — would draw the selection box for one
EPD frame before wiping it, paint the dash trail instead of the Supernote lasso-eraser's x-trail, and
need the paste-on-tap hook gated. That is the workaround the protocol forbids: **g-paper gaps are
fixed in g-paper.**

**Why not a fourth tool button.** With every extension installed the notebook's top bar holds
eleven 62 dp buttons on the Nomad's 749 dp (682 dp + 8 dp padding); a twelfth is 752 dp and falls
off the edge. The calendar's bar is also at eleven. og's slot (`pen · eraser · lassoEraser · lasso`)
does not fit where it matters, so the tool is armed from **a second tap on the armed eraser**, the
precedent being the lasso's own re-tap popup.

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| g-paper `CanvasPaperView` outline capture (`GestureMode.LASSO`, `lassoPoints`, `completeLassoOutline`, `buildSelectionFromOutline`) | selection | the same capture under `Tool.LASSO_ERASER`, completed as an erase (D1) |
| `LassoHitTest.hitStrokeIds` (any point inside, even-odd) + `polygonIntersectsBounds` (outline touches a `HitTarget` box) | the lasso's hit rule | **the lasso eraser's hit rule, unchanged** (decision 4) |
| The scribble-erase consume recipe (dismiss a selection that loses a member → `strokeList.removeAll` → `modelChanged` → one listener call → `finalizeEraseRedraw` → `onGestureStrokeConsumed`) | scribble | the lasso-erase completion copies it (D1) |
| `PaperListener.onScribbleErased(strokeIds, contentIds)` with a forwarding default | scribble | `onLassoErased(strokeIds, contentIds)` in the same shape, same default (D1) |
| Ratta `applyToolToFirmware` (`Tool.LASSO` → `SupernoteInk.Pen.DASH` at `LASSO_TRAIL_EMR`), `contactLassoOutline` + `releaseGestureTrace` at lift, `updateLassoDragHoverSuppress` | the dash trail | `Tool.LASSO_ERASER` → **`SupernoteInk.Pen.CROSS`** (code 3 — already declared as "the Supernote lasso-eraser trail appearance"), same trace ladder, **no** drag hover suppress (there is never a box) (D2) |
| Onyx raw lasso path (`beginRawLasso` / `endRawLasso`, `applyLassoTrailStyle`) | BOOX | every `tool == Tool.LASSO` capture check widened to "a lasso-capturing tool"; untested this arc — SN is Ratta-only (D2) |
| `NotebookToolbar` (`onToolTap`: second tap on the armed tool is a no-op **except** the lasso's `onLassoReTap`), `sync(tool)` via `isSelected`, `showClipboardLoaded` icon swap | three tools | `onEraserReTap` opens the eraser sub-bar; `sync` handles the fourth tool; the eraser icon swaps while the lasso eraser is armed (D3) |
| `PaperToolbar` in `:sn-screen` (the pad's and the calendar's shared bar), `InkScreenActivity` (`:ext-ink`) listener + `InkAction` (`Drew` / `Erased` / `Moved` / `Pasted`) | pad + calendar | the eraser re-tap + sub-bar live in `:sn-screen`; `onLassoErased` override records an `InkAction.Erased` — **no new `InkAction` kind** (D5) |
| `AnchoredBar` + the floating sub-bar recipe (H1–H6 level bar, `InsertBar`, `lassoPopup`, `tagsPopup`) | the notebook | the **eraser sub-bar** (D3) — one implementation in `:sn-screen` so all four bars share it |
| `NotebookActivity.removeContent` + `recordWithStickies` + `eraseEntry` (whole-object erase for headings / links / text / shapes / stickies, sticky content snapshotted) | eraser, scribble, Delete | `onLassoErased` goes through the very same three; `eraseEntry` gains the lasso kind (D4) |
| `NotebookUndo.Action` — 22 kinds, two exhaustive replay `when`s | `Erased`, `ScribbleErased`, `Deleted` | **`LassoErased`** (D4), replaying exactly like `ScribbleErased` |
| `StickyEditorActivity` (own surface, pen · eraser · lasso, notebook's fixed values) | arc 28 | the same eraser re-tap; records its erase as it records the point eraser's today (D5) |
| `ic_lasso_eraser` in og's `drawable/` | og | copied as a Tabler-outline SN icon in `:sn-screen` (check before drawing) |

## Decisions (wizard 2026-09-06 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Tool home | **Engine — `Tool.LASSO_ERASER` in g-paper 0.1.28.** Outline captured like the lasso, no selection box, the firmware x-trail on Ratta, one `onLassoErased(strokeIds, contentIds)` with a forwarding default (the `onScribbleErased` shape). One EPD frame. The engine commit lands with the host commit (LE1 pins it). |
| 2 | Surfaces | **All four paper surfaces**: notebook, sticky editor, scratch pad, calendar. The pad's and the calendar's tools are the notebook's, fixed — an erase that behaves differently one tap away reads as a bug. |
| 3 | Chrome | **Eraser re-tap sub-bar: Point · Lasso.** A second tap on the armed eraser opens a two-button floating sub-bar (the lasso re-tap popup precedent); picking Lasso arms `Tool.LASSO_ERASER`, picking Point arms `Tool.ERASER`. The eraser button's icon swaps to the lasso-eraser glyph while the lasso eraser is armed (the `ic_lasso_clipboard` precedent). Same on all four bars through `PaperToolbar` / `:sn-screen`. No new bar slot. |
| 4 | Hit rule | **Touch semantics — og + g-paper parity.** A stroke goes if any point lies inside the loop; a heading / link / text / shape / sticky goes **whole** if the loop touches its box — exactly what the lasso selects today, so select-then-Delete and lasso-erase always agree. The eraser never reaches inside a sticky from the page. |
| 5 | Phases / review | **Four phases, LE1–LE4, no code review.** LE4 is docs + freeze only (arcs 24–28's shape). |
| 6 | Naming / version | **Arc 29 "Loop"**, this standalone `LOOP_PLAN.md`, reference folded into `docs/notebook.md` (+ pointers in `docs/scratchpad.md`, `docs/calendar.md`, `docs/objects.md`). App version stays `0.1.0-ratta` (a phase-start question every phase, as always). |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **A lasso erase is one gesture, one undo entry** — strokes and content together, the scribble
  rule. Undo puts everything back in place (`revive` / `restore`, never tail-append).
- **The engine removes strokes; the host removes content.** As for the eraser and the scribble:
  the engine drops the hit strokes from its model and re-records, reports both id lists once, and
  the host deletes its content rows and calls `notifyContentChanged()`. Host content never
  disappears by itself (g-paper's standing rule).
- **A loop that takes nothing is nothing** — no callback, no undo entry, no frame beyond the
  trail's own retraction. A tap-sized contact in `Tool.LASSO_ERASER` reports **nothing** (no
  `onPaperTapped` — that hook is the lasso's paste-here and belongs to `Tool.LASSO` only).
- **There is no selection in the lasso eraser** — no box, no drag, no `onSelection*` callback,
  no `setSelection` accepted (the mode is a no-op outside `Tool.LASSO`, as transform mode is). A
  host-injected selection standing when the tool is armed is dismissed by the tool setter
  (leaving LASSO already does this).
- **The barrel button / eraser end still point-erases** in `Tool.LASSO_ERASER`, exactly as it
  does in `Tool.LASSO` ("an erase contact must never become an outline").
- **The recognizers are off in `Tool.LASSO_ERASER`** — smart lasso and scribble erase are
  evaluated only in `Tool.PEN` (unchanged).
- **The armed tool is remembered by nobody.** The sub-bar remembers nothing; the eraser button
  arms `Tool.ERASER` on a plain tap from another tool, whatever was armed last (P1: nothing is
  remembered). The lasso eraser is reached only through the re-tap.
- **A tool change closes the eraser sub-bar**, as it closes the lasso popup and the Insert bar;
  so does a page swap, a pick, another bar's button, or an outside touch.
- **Frame silence:** showing the eraser sub-bar is a chrome frame at a deliberate tap (the lasso
  popup's ledgered justification, extended); the erase repaint is the engine's, one frame. The host
  **never repaints from `onLassoErased`** beyond `notifyContentChanged()` (the `onScribbleErased`
  rule — the engine re-records itself).
- **No new `InkAction` kind** on the pad or the calendar: a lasso erase there is ink only and
  records `InkAction.Erased` — the label reasoning that keeps `LassoErased` its own kind in the
  notebook (headings / links / objects ride it) has nothing to label in an ink-only store.

---

## Design (binding unless a phase-start question reopens it)

### D1 — `Tool.LASSO_ERASER` in `gpaper-core` (LE1, Fable, g-paper 0.1.28)

- `Tool.kt`: `LASSO_ERASER` after `LASSO`, KDoc: captures a closed outline like `LASSO`, erases
  what the outline takes on the lasso's own hit rule (`LassoHitTest`), never selects; strokes leave
  the model, host content is reported whole through `PaperListener.onLassoErased`.
- `PaperListener.kt`: `fun onLassoErased(strokeIds: List<String>, contentIds: List<String>)` with
  the `onScribbleErased` default body (`onStrokesErased(strokeIds)`; `onContentErased(contentIds)`
  when non-empty) so an older host keeps working.
- `CanvasPaperView`:
  - a private `val capturesOutline get() = tool == Tool.LASSO || tool == Tool.LASSO_ERASER`;
    `ACTION_DOWN` routes `LASSO_ERASER` straight to `GestureMode.LASSO` **without**
    `lassoTryBeginDrag` (there is never a box); MOVE/UP unchanged.
  - `completeLassoOutline` branches first: in `LASSO_ERASER` → `completeLassoErase(outline)`:
    `invalidate()`; tap-sized or `< 3` points → return (nothing reported); hit-test with
    `LassoHitTest.hitStrokeIds` + `polygonIntersectsBounds` over `hitTargets()`; nothing hit →
    return; else the scribble recipe verbatim (dismiss a selection losing a member — cannot exist,
    kept for parity; `strokeList.removeAll`; `modelChanged()`; `onLassoErased(hits, contentHits)`;
    `finalizeEraseRedraw()`; `onGestureStrokeConsumed()`; one `Log.i` with counts).
  - the tool setter: `leavingLasso` covers both capturing tools (a standing selection is cleared
    when the lasso eraser is armed — the setter's `clearSelection` on leaving LASSO already covers
    the other direction); `beginTransform` / `setSelection` / finger-drag paths stay
    `Tool.LASSO`-only (`tool != Tool.LASSO` guards untouched).
  - `onPaperTapped` is **not** fired from the erase branch.
- `buildSelectionFromOutline` is not reused as-is (it builds a `Selection`); the hit-test lines are
  lifted into a small private `outlineHits(outline): Pair<List<String>, List<String>>` both call.
- Tests (`gpaper-core/src/test`): `LassoHitTest` already covered; add a pure test only if a new
  geometry helper appears (none planned). The consume path is exercised on the Nomad.
- `docs/api.md` (tool table, listener table, a "Lasso eraser (0.1.28)" section beside the
  recognizers), `docs/host-responsibilities.md` (undo table row, host-content sentence),
  `CHANGELOG`/README as the other bumps did; `GPAPER_VERSION=0.1.28`; `publishToMavenLocal`;
  SN re-pins in `sn-screen/build.gradle.kts` (both artifacts).

### D2 — The device engines (LE1)

- **Ratta** (`RattaPaperView`): `applyToolToFirmware` gains `Tool.LASSO_ERASER →
  SupernoteInk.setPen(SupernoteInk.Pen.CROSS, LASSO_TRAIL_EMR, BLACK)`; the `ACTION_DOWN` mirror sets
  `contactLassoOutline = true` for the lasso eraser too (never `contactLassoDrag`), so the lift runs
  `releaseGestureTrace()` — the x-trail corresponds to nothing in the app layer, the proven ladder
  wipes it; `updateLassoDragHoverSuppress` stays `Tool.LASSO`-only. `firmwareInkSuppressed`
  unchanged. Walked by hand on the Nomad (EPD ink is invisible to screencap).
- **Onyx** (`OnyxPaperView`): every `tool == Tool.LASSO` **capture** check (`applyTool`'s trail
  branch, `onBeginRawDrawing`, `onEndRawDrawing`, the two move receivers, the `!isSetup` fallthrough)
  widened to the capturing pair; the drag half (`rawDragActive`) is unreachable without a box.
  Trail style: the lasso's (`applyLassoTrailStyle`) — og's CHARCOAL-for-the-eraser distinction is
  **not** carried (no BOOX to measure on this arc; recorded as a g-paper backlog note). **Untested
  this arc** — SN is Ratta-only; the change is mechanical and compiles.
- **Generic** engine needs nothing beyond the base (`rendersLiveTrail` draws the outline).

### D3 — The eraser sub-bar and the bar state (LE2 notebook + sticky editor · LE3 pad + calendar)

- `:sn-screen` `notebook/EraserBar` — a two-button floating sub-bar (**Point** `ic_eraser` ·
  **Lasso** `ic_lasso_eraser`), `AnchoredBar` placement under the eraser button, `toolbar_button_size`
  buttons, the bordered `bg_toolbar_button` look with the armed one `isSelected`. One class, used
  by all four bars. Dismisses on a pick, a tool tap, another bar's button, a page swap, an outside
  touch; unions its rect into the exclusion rects and `overChrome` while up (every floating bar's
  rule). Long-press hints on both buttons.
- `PaperToolbar` (`:sn-screen`) gains `onEraserReTap` beside the tool taps and `sync` handles
  `Tool.LASSO_ERASER` (the eraser button `isSelected` + icon `ic_lasso_eraser`; `Tool.ERASER` →
  `ic_eraser`). `NotebookToolbar` (`:app`) gets the same two changes — it is the notebook's own
  bar, not `PaperToolbar`, so the change is made in both **by design** (the same fixed values are
  already duplicated there; the shared piece is the sub-bar itself).
- `onToolChanged` keeps driving the bar (`toolbar.sync`) — the engine never changes the tool to
  `LASSO_ERASER` itself, but a smart-lasso session's PEN restore can still land after a dismissal,
  and the sync path is the one that is right.
- Icon `ic_lasso_eraser`: Tabler-outline, 24 dp, from og's `drawable/` (check first — it exists
  there as `ic_lasso_eraser`), placed in `:sn-screen` so every surface shares it.

### D4 — The notebook's mirror (LE2)

- `NotebookUndo.Action.LassoErased(pageId, strokes, headingIds, links, textIds, shapeIds,
  stickies)` — `ScribbleErased`'s exact shape, its own kind for the label reason (`Deleted` vs
  `Erased`). Revert = `store.revive` + `headings.restore` + `links.restore` + texts/shapes restore +
  `StickyStore.restore`; reapply = the erase half. Both exhaustive `when`s gain the arm.
- `NotebookActivity` listener: `onLassoErased(strokeIds, contentIds)` = the `onScribbleErased` body
  with `eraseEntry(..., kind = LASSO)` — `eraseEntry`'s `scribble: Boolean` becomes a small
  `EraseKind { ERASER, SCRIBBLE, LASSO }` (headings-only under the eraser still records
  `HeadingDeleted`, unchanged). `removeContent` + `recordWithStickies` reused untouched. **No**
  `notifyContentChanged()` beyond what `removeContent` already does for content (mirror the
  scribble path exactly; the engine re-records the strokes itself).
- The sticky editor (`StickyEditorActivity`): the same `onLassoErased` override recording what its
  point eraser records today (its own undo shape — read it at phase start; no new kind unless it
  already distinguishes erase kinds).
- `NotebookUndoTest` gains the kind both ways; `FakeSoilDao` untouched (no new SQL).

### D5 — The pad and the calendar (LE3)

- `InkScreenActivity` (`:ext-ink`): `override fun onLassoErased(strokeIds, contentIds)` →
  `inkPage?.erase(strokeIds)?.let { record(it); scheduleSave() }` — `InkAction.Erased`, the
  `onStrokesErased` body; `contentIds` is always empty there (no content renderers) and is
  ignored. The forwarding default would already do this; the override exists so the intent is
  explicit and a later content renderer on either screen does not silently split one gesture.
- Both layouts keep their three tool buttons; `ScratchToolbar` / `CalendarToolbar` wire
  `onEraserReTap` through `PaperToolbar` to one `EraserBar` placed in each screen's root
  (`FrameLayout` child, added last — the later-sibling trap). Exclusion rects updated as the
  selection toolbar's are.
- No extension declares a new API version: `PaperListener` is g-paper's, not the seam's, and the
  pad and the calendar already consume `:sn-screen` + g-paper directly.

---

## Phases

### ✅ LE1 — The engine tool (Fable; g-paper 0.1.28; SN re-pin)

**Questions to resolve at phase start:** app version (stays `0.1.0-ratta`?).

- D1 + D2 in `~/git/g-paper`: `Tool.LASSO_ERASER`, `onLassoErased`, the base capture/complete
  branch, Ratta `CROSS` trail + trace ladder, Onyx checks widened, docs, `GPAPER_VERSION=0.1.28`,
  `./gradlew test` + `publishToMavenLocal`, one g-paper commit.
- SN: re-pin `sn-screen/build.gradle.kts` 0.1.27 → 0.1.28; `./gradlew test` (nothing else
  changes — the forwarding default keeps every listener compiling). A debug-only way to arm the
  tool for the walk if LE2's bar is not there yet (the debug menu's "Arm lasso eraser" toggle,
  removed in LE2) so the engine is proven on the Nomad **before** the bar exists.
- **As built (2026-09-06):** g-paper `a0796d1` (0.1.28, pushed); SN re-pinned, `NotebookToolbar`
  carries the LE1 debug door — a **re-tap on the armed eraser flips ERASER ↔ LASSO_ERASER**
  (`BuildConfig.DEBUG` only; `sync` keeps the eraser button selected in both), removed by LE2's
  sub-bar. No debug-menu row was needed. `setSelection` while armed is accepted as it is in PEN
  (the next outline dismisses it) — the derived rule above is read that way.
- **Walk (by hand, Nomad):** x-trail paints live and retracts at lift; a loop over ink erases it in
  one frame; a loop over a heading / shape / sticky icon / link reports the content id (the
  forwarding default deletes it through `onContentErased` today); a tap-sized contact does nothing
  and pastes nothing; a loop over nothing leaves the page as it was; barrel button point-erases;
  arming the tool while a selection stands dismisses it; the pen tool afterwards inks normally.
- Commit: g-paper first (the pin must resolve from a fresh clone), then SN with the pin.

### ✅ LE2 — Notebook + sticky editor (Opus code on a Fable brief · Fable review · walk by hand)

**Questions to resolve at phase start:** app version; whether the eraser sub-bar sits under the
eraser button or centred under the bar (planner call: under the button, `AnchoredBar` clamped).

- D3 (`EraserBar` in `:sn-screen`, `PaperToolbar` + `NotebookToolbar` re-tap and sync, the icon),
  D4 (`LassoErased`, `EraseKind`, the listener override, the sticky editor's override). The LE1
  debug toggle is removed.
- Tests: `NotebookUndoTest` both replay directions; `EraserBar` placement if it grows any
  arithmetic of its own (else `AnchoredBar`'s tests cover it).
- **Walk:** re-tap opens the sub-bar (and a second re-tap closes it); Lasso arms + icon swaps;
  Point returns; pen/lasso taps close the bar; lasso-erase strokes, a heading, a text, a shape, a
  sticky (content comes back on undo), a link; undo/redo each; page flip closes the bar; the
  sticky editor's eraser re-tap works and its undo puts the ink back; process death (`am crash`)
  reopens on the pen with nothing armed.

### ⬜ LE3 — Scratch pad + calendar (Opus/Sonnet on a Fable brief · walk by hand)

**Questions to resolve at phase start:** app version.

- D5: `InkScreenActivity.onLassoErased`, both toolbars' re-tap, one `EraserBar` per screen, the
  exclusion rects. Extension APKs rebuilt and installed with the host (same signature).
- **Walk:** pad — re-tap, lasso-erase, undo/redo, page flip closes the bar, Send afterwards still
  works; calendar — the same on Month/Week/Day, the events editor's note surface untouched (it has
  no eraser re-tap unless its bar is `PaperToolbar` — read at phase start); the handoff chain
  notebook → pad → notebook still reclaims the pen.

### ⬜ LE4 — Docs, ledger, freeze (Sonnet docs in parallel · Fable read-back · no code review, no code)

- `docs/notebook.md`: the tools table (a fourth row), the eraser re-tap + sub-bar under Toolbar,
  the undo table's `LassoErased` row, the frame-silence ledger entry, the JVM test list.
  `docs/scratchpad.md` + `docs/calendar.md`: the tool + the `InkAction.Erased` note.
  `docs/objects.md`: the whole-object sentence gains the lasso eraser. `docs/sn-screen.md`:
  `EraserBar`. g-paper's own docs landed in LE1.
- `PARITY_BACKLOG.md` item 4 → DONE; `RATTA_PLAN.md` header + ledger line; app `CLAUDE.md` arc
  line + pin; root `CLAUDE.md` branch line; memory. Freeze.

---

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- The sub-bar anchors **under the eraser button**, left edge aligned, clamped to the root — not
  centred under the bar. Two buttons at `toolbar_button_size` with the bar's 1.5 dp border.
- The x-trail uses the lasso's `LASSO_TRAIL_EMR` (300) — one chrome size for both trails.
- `Log.i` counts only — never ids of content, never text.
- The lasso eraser does not consult `snapToGuides` or `snapMarginPx` (nothing moves).
- Walks are **by hand on the Nomad** (the Haiku wander trap); adb cannot draw a loop.

## Standing traps that bind this arc

- **Two exhaustive `when`s over `Action`** in `NotebookActivity` (undo + redo) — a new kind that
  misses one is a compile error, a widened field that misses one is a silent no-op.
- **The host must not repaint from the erase callback** (arc 14) — the engine re-records itself;
  `notifyContentChanged()` for content only, exactly the scribble path.
- **The engine commit lands with the host commit** — a pin at an uncommitted engine is a tree a
  fresh clone cannot resolve. g-paper is committed first.
- **`releaseRender()` gated on `!isPenActive`** in every bar handler; `isPenActive` counts hover
  — never idle-gate the sub-bar's show/hide (a deliberate tap).
- **A later sibling at `match_parent` sits ON TOP of an earlier button in a `FrameLayout`** —
  the sub-bar is added last / margined clear.
- **A 1 dp hairline at 1.875 density is a coin flip** — `round(density)` px on integer edges.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **GONE, never disabled**; Toast confirms, dialog explains — neither is needed here.
- **Check og's `drawable/` before drawing a "fresh" icon** — `ic_lasso_eraser` exists there.
- **Walk-agent false failures** — every walk in this arc is by hand.
- **The four overlay laws** (`clearAll`+frame, eaten clears → retry ladder, suppress from the
  HOVER stream, re-arm on pen approach) — the x-trail rides the same `releaseGestureTrace` ladder
  as the dash trail; a trail that stays on the panel is a g-paper fix, never a host one.
- **Backing out of a live notebook through the app before installing** keeps the EPD pin from
  leaking.

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

One phase per session; read this file whole at phase start, flip the phase to 🔄, ask its
phase-start questions **one at a time**, then code. Fable plans / seams / reviews / the engine
(LE1); Opus features; Sonnet scaffold, layouts, resources, docs; ≤ 5 background agents. JVM tests
for every pure piece; the user gets a **short numbered checklist** only for what needs a hand or
an eye. **Nomad only** (SNN `SN078D10012852`); the Manta only on explicit ask. Commit + push only
when every suite is green (or the user's all-clear), after docs / memory / CLAUDE.md are in; then
the user runs `/clear`. A long explanation and an `AskUserQuestion` never share one turn —
explain, wait, then ask.

## Ledger

*(one Outcome entry per phase as it closes)*

### LE1 — Outcome (2026-09-06) ✅ (g-paper `a0796d1` = 0.1.28 · SN `f0ffe408`)

- **Engine:** `Tool.LASSO_ERASER`, `PaperListener.onLassoErased(strokeIds, contentIds)` with the
  forwarding default, `CanvasPaperView.completeLassoErase` on the scribble-consume recipe over the
  shared `outlineHits` (selection builder + eraser now hit-test through one function), tool setter
  drops a standing selection when the eraser is armed. Ratta: `SupernoteInk.Pen.CROSS` at
  `LASSO_TRAIL_EMR`, contact marked as an outline so the lift runs `releaseGestureTrace`. Onyx:
  `capturesOutline` widening, lasso trail style, **not hardware-tested**. g-paper docs (`api.md`
  tool + listener tables + "Lasso eraser (0.1.28)" section, `host-responsibilities.md` undo row +
  host-content sentence, `PLAN.md` Phase 16).
- **Host:** pin 0.1.27 → 0.1.28; the debug eraser re-tap door in `NotebookToolbar` (LE2 removes
  it). All JVM suites green (`./gradlew test`, exit 0).
- **Walk (by hand on the Nomad, the user, 2026-09-06): all nine items passed** — x-trail live and
  retracted, one-frame erase, whole-object for heading / shape / sticky / link, undo in place, no
  paste on a bare tap, empty loop leaves nothing, barrel point-erases, arming drops a selection,
  return to point eraser and pen clean.
- **Next:** LE2 — `EraserBar` in `:sn-screen`, `PaperToolbar` + `NotebookToolbar` re-tap and icon
  swap, `Action.LassoErased` + `EraseKind`, the notebook and sticky editor overrides.

### LE2 — Outcome (2026-09-06) ✅

- **Phase-start answers:** version stays `0.1.0-ratta`; the sub-bar hangs under the eraser button
  through `AnchoredBar` (its `SelectionAnchor.placeUnder` centring kept — the planner's
  "left-aligned" wording was not carried; the three existing floating bars centre).
- **`:sn-screen`:** `AnchoredBar` **moved** there (same package, `R` repointed — the three `:app`
  callers untouched); new `EraserBar` (Point `ic_eraser` · Lasso `ic_lasso_eraser`, og's icon
  byte-for-byte; a pick arms the tool pen-gated then `onPicked`; `show()` presses the armed one);
  `PaperToolbar` gains `onEraserReTap` + `onToolTapped` (both defaulted — `ScratchToolbar` /
  `CalendarToolbar` untouched until LE3), the re-tap rule (a tap on the eraser under **either**
  eraser is the re-tap), public `arm(tool)`, and `sync` selecting the eraser under both kinds and
  swapping its glyph **only on a change of kind** (review fix — every `onToolChanged` lands in
  `sync`, and re-setting the same drawable invalidates the button for nothing).
- **`:app`:** `NotebookToolbar` the same three changes, LE1's debug door removed;
  `Action.LassoErased` (`ScribbleErased`'s shape, its own kind) with both replay arms;
  `EraseKind { ERASER, SCRIBBLE, LASSO }` replaces `eraseEntry`'s boolean; `onLassoErased` =
  the scribble body, no extra repaint; the notebook's eraser bar follows the Insert bar's whole
  lifecycle (show/hide, newest-tap-wins between the four floating bars, page swap, outside
  contact with the eraser button excluded, exclusion rects, `overChrome`); the sticky editor
  gets the same bar (last child of its root), `onToolTapped` → hide, dismissal on every
  pointer-down, hidden on `exit()` and `reload()`, and `onLassoErased` = its point-eraser body
  (no new `StickyInk.Action` kind). `NotebookUndoTest` +2. `:app` 1470 → **1472**, total
  2830 → **2832**; `./gradlew test` + `:app:assembleDebug` exit 0.
- **Walk (by hand on the Nomad, the user, 2026-09-06): all eight items passed** — re-tap
  toggle, Lasso/Point picks with the glyph swap, pen/lasso taps close the bar, one-frame whole
  erase of ink / heading / text / shape / sticky / link with undo and redo, page flip closes the
  bar, bare pen tap closes it and pastes nothing, the sticky editor's re-tap + erase + undo, and
  `am crash` reopens on the pen with nothing armed.
- **Not done here, deliberately:** `onSelectionCreated` does not hide the eraser bar — it cannot
  be up when a selection is created (every road to `Tool.LASSO` is a tool tap, which already
  hides it). `NotebookActivity.kt` is now ~3860 lines — long before this arc, not restructured.
- **Next:** LE3 — `InkScreenActivity.onLassoErased`, `ScratchToolbar` / `CalendarToolbar`
  wiring `onEraserReTap` + `onToolTapped` through `PaperToolbar`, one `EraserBar` per screen
  (root `FrameLayout`, added last), exclusion rects.

