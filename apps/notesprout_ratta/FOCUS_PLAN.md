# FOCUS_PLAN.md — Arc 33 "Focus" (Notesprout SN, branch `ratta`)

**Standalone plan for the double-tap chrome toggle and full-page paper** — a fresh user decision
(2026-09-09), not a `PARITY_BACKLOG.md` item (the backlog is closed). This file is the
cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this
file is enough. `RESUME_PLAN.md` is the shape this file copies.

**Status: 🔄 PLANNED 2026-09-09 — F1 ⬜ · F2 ⬜ · F3 ⬜ · F4 ⬜ · F5 ⬜.**
Baseline before the arc: 1606 `:app` / 2987 JVM tests, g-paper 0.1.28, `API_VERSION` 9, fourteen
modules, version `0.1.0-ratta`. No point, no API bump (one compatible Intent extra), no schema
change, no g-paper change, no new module.

**Phase code:** **F** — one letter (no earlier arc used a bare F; the post-arc fixes F1–F5 of
2026-08-26 are ledgered in `RATTA_PLAN.md` as "post-arc fixes", never as a phase code).

---

## What this arc is

A **single-finger double-tap on every paper screen hides or shows all of its chrome.** While
hidden the whole screen is writable paper; the bars come back, floating over the paper, on the
next double-tap. Because the toggle is the same gesture everywhere, "how do I get the full page"
and "how do I get the tools back" have one answer on every surface.

The **calendar's Month / Week / Day grids expand to the full page** — the template no longer
stops at the bars. Writing that lands where a bar sits is simply covered by the bar's white when
the bar is shown, and export (HV4's file export, HV5's papered send) renders the full-page grid
with every stroke.

The **sticky editor's paper goes full-bleed** like the other three screens, and a new sticky's
content is the full window.

**Three facts that shape it (surveyed 2026-09-09):**

1. **Three of the four screens already have full-bleed paper with the bars as overlays**
   (`activity_notebook.xml`, `activity_calendar.xml`, `activity_scratch_pad.xml` — root
   `FrameLayout`, `paperContainer` first, bars with `layout_gravity`, floating bars as later
   siblings). The notebook's page size is a stored row value minted from the full display metrics;
   the pad's and the calendar's page size is the full surface. **No page-size change anywhere, no
   schema change.** Only the sticky editor lays its paper *below* its bar.
2. **Only the calendar's template is inset by the bars** — `CalendarGeometry.month/week/day(…,
   topInsetPx, bottomInsetPx)` fed from the measured `topBar.height` / `bottomBar.height`, mirrored
   view-free by `CalendarBars` for the export render. The page rows are already full-surface.
3. **The gesture already exists.** `PageGestures.Listener.onFingerDoubleTap(x, y)` has its own
   history (`evaluateDoubleTap`), rides the 350 ms escrow behind `gateOpen()`
   (`!isPenActive && !standDown`), and a sequence that starts on chrome, from a stylus or under
   a stand-down is dropped at DOWN. Only `CalendarActivity` consumes it today (cell → Day).

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `PageGestures.onFingerDoubleTap` (`:sn-screen`) — escrowed, pen-gated, own history | the calendar's day-open | the toggle on all four screens; the calendar routes by zone (D4) |
| `PaperToolbar.rectOf(v)` (`:sn-screen`) — size-only rect | pad / calendar / sticky editor exclusions and `overChrome` via `PaperChrome`; the notebook's twin `NotebookActivity.rectOf` | gains a **visibility** check (trap 1); the notebook delegates to it |
| `AnchoredBar.rectOf` — already visibility-aware | the sub-bars | the rule promoted, not copied |
| `chromeBand()` ×3 (`NotebookActivity`, `InkScreenActivity`, the sticky editor's two lambdas) | floating-bar placement band between the bars | replaced by one pure `ChromeBand` (trap 2) |
| `pushExclusions()` per screen + root layout-change listeners | exclusion re-push on any child relayout | untouched shape; the toggle pushes once more after its own relayout |
| `SnapPrefs` (`data/prefs`, `sn_snap`, one global boolean) | snap-to-guides | the shape of `ChromePrefs` (D2) |
| `ExtensionScreenEntry.open` / `decorateIntent` / `onResult` | the pad's and calendar's doors on both host screens | the extra out, the result extra in (D3) |
| `EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` (a shape boolean on the screen Intent, no content / id / path) | | the precedent for `EXTRA_CHROME_HIDDEN` (D3) |
| `InkScreenActivity.finishWithHandoff(resultCode)` — bare `setResult(code)` | every pad / calendar exit | carries the one boolean back (D3) |
| `CalendarGeometry` + `CalendarGeometryTest` (107-px inset fixture) + `CalendarBars` + `CalendarRender` | inset grid on screen and in export | insets removed everywhere; `CalendarBars` deleted (D4) |
| `StickyDefaults.contentSize(w, h, topBarPx)` (arc 28 D2) | "window minus bar" | `contentSize(w, h)` (D5) |
| `StickyFlow.openAt(): Boolean`, `LinkFollowFlow.followAt()` | the notebook's single-tap consumers | `followAt` answers a hit Boolean; both feed the collision rule (D1) |
| `docs/notebook.md` § frame-silence ledger (seven exceptions) | | one new entry (D1) |

## Decisions (wizard 2026-09-09 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Target | **Notesprout SN** only. |
| 2 | Calendar double-tap | **Day-open stays on a Month/Week cell double-tap.** The Notes band below the grid is the toggle's double-tap zone on Month and Week; on a Day page (no band) a double-tap anywhere toggles. |
| 3 | Existing calendar ink | The grid goes **full page (insets 0)**; ink already on calendar pages shifts up one bar height — **accepted: no migration, no per-page inset, no schema step**. |
| 4 | Surfaces | **Notebook · Scratch pad · Calendar · Sticky editor.** The events editor's `NoteSurface` (a paper view inside a form) is **out**. |
| 5 | Sticky size | **Full window for new stickies.** An existing (shorter) sticky lays out top-left as now, with the band below its page blocked from ink. |
| 6 | Persistence | **One global persisted boolean**, shared by all four surfaces, default shown. Extensions receive it as a launch extra and echo the final state on their result Intent; the host persists it. |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **Everything chrome hides:** the notebook's top bar **and** bottom strip (name + page
  indicator), the pad's and calendar's top and bottom bars, the sticky editor's top bar. The
  page indicator has no hidden-mode home; the page sheet (long-press) still names the page.
- **Contextual floating bars keep working while hidden** — the selection toolbar and its sub-row,
  the transform bar, `FloatingSelectionBar` — because a lasso is a deliberate act with nothing
  else to answer it. **Button-anchored popups go down at hide** (lasso popup, tags popup, insert
  bar, eraser sub-bar): their button is gone and they would float over bare paper.
- **A shown bar covers the ink beneath it** (its background is opaque `paperWhite`); the pen
  refuses under a shown bar (exclusion), inks there when hidden. Nothing is redrawn or moved.
- **`GONE`, never `INVISIBLE`** — an attached Ratta paper view keeps the pen claimed whatever a
  sibling's visibility, and an `INVISIBLE` bar keeps its rect. Trap 1 below is why `GONE` alone
  is still not enough.
- **The flip is a chrome frame at a deliberate act** — it rides frame-silence exception 6's
  justification (the act passed `PageGestures.gateOpen()` + the escrow) and is **never
  `whenPenIdle`-gated** (`isPenActive` counts hover; the bars would arrive long after the taps).
  `paper.releaseRender()` precedes the flip, as every chrome handler does.
- **The snap margin stays "one toolbar" whether shown or hidden** — an object snapped while
  hidden must still clear the bar when it comes back; the margin is page-space, not chrome state.
  (F1 phase-start question; the recommendation is recorded here.)
- **The preference is device-local, never backed up or restored** — it is not in the index and
  not in any `.soil`; a way of working, not a property of a page (the `SnapPrefs` argument).
- **The extra is a compatible tail, not an API bump and not a floor.** The "nothing rides the
  Intent" rule (`docs/extensions.md`) is about user content, ids, paths and secrets; the pad's
  and calendar's Intents already carry four shape booleans. An old extension ignores the extra
  and returns a result with no data → the host writes nothing. This is the **first datum ever on
  this seam's result Intent** — one boolean, nothing else; the extension still writes nothing to
  disk.
- **Inset parameters are removed, not passed as 0** — a parameter that is always 0 under a
  confident KDoc is the "dead code with a confident doc comment" trap. `CalendarBars` is deleted
  with them.
- **Templates, Backup, Export, Tags, the pickers, the events list and editor are not paper
  screens** and get no toggle.

## Three traps the design is built around (verified in source 2026-09-09)

1. **A `GONE` view keeps its last `width` / `height`.** `PaperToolbar.rectOf`
   (`sn-screen/…/notebook/PaperToolbar.kt`) and `NotebookActivity.rectOf` check size only, so a
   hidden bar would keep excluding ink and keep swallowing gestures where it was. `AnchoredBar`'s
   private `rectOf` already checks `visibility != VISIBLE` — that rule is promoted into
   `PaperToolbar.rectOf` and the notebook delegates to it.
2. **`chromeBand()` returns null when either bar height is 0** (`NotebookActivity`,
   `InkScreenActivity`, the sticky editor's two lambdas) → `AnchoredBar.show` returns false and
   `FloatingSelectionBar.show` / `SelectionToolbar.show` return early: **every floating bar would
   silently refuse to show while hidden.** Replaced by `ChromeBand` (D1).
3. **`CalendarActivity.awaitLaidOut()` waits for `topBar.height > 0 && bottomBar.height > 0`.**
   A calendar launched with the extra set to hidden would `GONE` its bars before first layout and
   **hang on "Opening…" forever.** Reduced to root width / height in F3, before the calendar ever
   reads the extra.

Two more that shape the gesture:

4. **The second tap's `onFingerTap` escrow is posted before `onFingerDoubleTap`'s**
   (`PageGestures.kt` `ACTION_UP` → `escrow { onFingerTap }` then `evaluateDoubleTap`). On the
   notebook both taps of a pair reach `onFingerTap` (sticky open / link follow) and the double
   fires last — the collision rule (D1) leans on exactly that order.
5. **The escrow re-checks `gateOpen()` at fire**, and DOWN drops stylus / over-chrome / stand-down
   sequences. The toggle inherits the pen gate; adding `whenPenIdle` on top would break it (hover).

## Design (binding unless a phase-start question reopens it)

### D1 — The shared mechanism (`:sn-screen` `notebook/`; F1)

One copy for four screens — a fix to shared screen logic goes to `:sn-screen`, never a consumer.
The notebook keeps its own inline `pushExclusions` (it deliberately does not use `PaperChrome`)
and adopts only these three pieces, as it already adopts `AnchoredBar` / `EraserBar`.

- **`PaperToolbar.rectOf(v)`** — first line `if (v.visibility != View.VISIBLE) return null`.
  Fixes `PaperChrome.pushExclusions` / `overChrome` (pad, calendar), `FloatingSelectionBar.rects`,
  the sticky editor's `overChrome`, and the notebook once its `rectOf` is `= PaperToolbar.rectOf(v)`.
- **`ChromeBand`** (new, pure, JUnit-tested): `of(rootHeight: Int, top: Bar?, bottom: Bar?):
  IntRange?` with `Bar(shown: Boolean, edge: Int, laidOut: Boolean)`. A hidden bar contributes the
  root edge (0 / `rootHeight`); a shown bar that is not laid out → null; `rootHeight == 0` → null;
  an empty or inverted range → null. A tiny `View.asBar(edge: Int)` beside it builds a `Bar` from
  a view (Android-typed, untested — the rule is what is tested). Replaces the three `chromeBand()`s
  and the sticky editor's two lambdas.
- **`ChromeToggle`** (new): `ChromeToggle(paper: PaperView, root: View, bars: List<View>,
  beforeHide: () -> Unit, afterLayout: () -> Unit)`; `val hidden` (private set); `apply(hidden:
  Boolean, initial: Boolean = false)`; `toggle()`. **The flip order, once:**
  1. `paper.releaseRender()` unless `initial` (nothing is on the glass in `onCreate`);
  2. if hiding → `beforeHide()` (the consumer takes down its button-anchored popups);
  3. every bar `GONE` / `VISIBLE`;
  4. `root.doOnNextLayout { afterLayout() }` → the consumer's `pushExclusions()`, which re-reads
     the band (`ChromeBand`), the rects (visibility-aware `rectOf`) and, on the notebook, the
     snap margin. One binder call per flip — never per event.
  One `Slog.d` per flip (`chrome hidden=…`). No `whenPenIdle`. The consumers' existing root
  layout-change listeners still fire on the relayout and are kept for moves; the toggle pushing
  its own is what keeps four screens from drifting (the sticky editor's listener is on
  `paperContainer`, which after F2 no longer changes size on a flip).
  Nothing touches `setPageSize`: the paper view never resizes on any screen, so
  `RattaPaperView.onSizeChanged` never fires; the only stale thing is the firmware's screen-space
  disable bands, and `setExclusionRects` in `afterLayout` refreshes them.
- **`ChromePrefs`** (`app/…/data/prefs/ChromePrefs.kt`, host): `SharedPreferences("sn_chrome")`,
  key `hidden`, default `false`; `SnapPrefs` byte-for-byte in shape.
- **The notebook's collision rule** (`app/…/notebook/DoubleTapToggleRule.kt`, pure, tested):
  `StickyFlow.openAt` already answers a hit Boolean; `LinkFollowFlow.followAt` gains one (hit or
  not, decided before the async follow). The listener keeps a **two-deep hit history**; by trap 4
  the double fires with `[tap1Hit, tap2Hit]` known → **toggle iff neither tap hit** a sticky or a
  link. Timing-free; a link followed on tap 1 never toggles the new page; a stale entry ages out
  by being overwritten (worst case one refused toggle, never a wrong one).
- **`onResume` re-sync** (notebook, sticky editor): before `paper.resumeDrawing()`, `if
  (chromeToggle.hidden != prefs.hidden) chromeToggle.apply(prefs.hidden)` — another screen may
  have flipped it. `initial`-style (no release: nothing on the glass yet).
- **Frame-silence ledger:** a new entry in `docs/notebook.md` — the chrome toggle at a finger
  double-tap rides exception 6's justification; `releaseRender` first; not idle-gated because
  `isPenActive` counts hover. Numbering is an F1 phase-start question.

### D2 — The sticky editor (F2)

- `activity_sticky_editor.xml` → root `FrameLayout`, `paperContainer` full-bleed first child,
  `topBar` `layout_gravity="top"` as a later sibling, `selectionBar` / `eraserBar` last (the
  later-sibling-sits-on-top trap). Header comment rewritten.
- `StickyDefaults.contentSize(windowW, windowH)` — `topBarPx` dropped; KDoc: the note's content
  is the full window. `NotebookActivity`'s `contentSize()` call updated. `docs/objects.md` D2
  amended (F5 writes it; F2 leaves one sentence).
- New pure `StickyPageRects.offPage(pageW, pageH, viewW, viewH): List<Rect>` (tested): the band
  below and, if narrower, right of an old note; g-paper leaves the area beyond the page white
  **and writable**, so the exclusion is what keeps ink inside the note.
- `StickyEditorActivity`: `pushExclusions` = `rectOf(topBar)` + `selectionBar.rects()` +
  `eraserBar.rects()` (paper px) + the off-page rects; bands via `ChromeBand.of(root.height,
  topBar.asBar(bottom), null)`; the layout listener moves to `root`; `chromeToggle` over
  `listOf(topBar)`, `beforeHide = { hideEraserBar() }`; `onFingerDoubleTap` → toggle + persist —
  no stickies or links here, no collision rule. The snap seed stays the dimen.

### D3 — The handoff (F3)

- `ExtensionContract.EXTRA_CHROME_HIDDEN = "chromeHidden"` under a "Chrome (arc 33)" block: a
  boolean on the launch Intent of the pad and calendar screens **and** on their result Intent;
  absent = shown; no version gate, no floor; pinned in the contract test.
- `ExtensionScreenEntry.open()`: after `decorateIntent`, `putExtra(EXTRA_CHROME_HIDDEN,
  ChromePrefs(activity).hidden)` — entry-level, so every door (library and notebook, pad and
  calendar) carries it with no per-entry code. `onResult`: **synchronously at the top, right after
  `stack.pop`, before the launched coroutine** — `ChromeResult.read(result.data)?.let {
  prefs.hidden = it }` — so the calendar → pad chain (`onClosed` → `scratchPad.open()`) reads
  the value the calendar just reported. `ChromeResult` is a tiny pure object (tested).
- `InkScreenActivity`: `protected lateinit var chromeToggle` (assigned by the subclass in
  `onCreate`, the class's existing rule); `initChrome()` = `apply(intent.getBooleanExtra(
  EXTRA_CHROME_HIDDEN, false), initial = true)`; `toggleChrome()` guarded by `opened && !closing`,
  `beforeHide = { hideEraserBar() }`; `chromeBand()` → `ChromeBand.of`; `finishWithHandoff` →
  `setResult(code, Intent().putExtra(EXTRA_CHROME_HIDDEN, chromeToggle.hidden))`.
- `ScratchPadActivity`: construct + `initChrome()`; `onFingerDoubleTap → toggleChrome()`.
- `CalendarActivity`: **`awaitLaidOut` reduced to root width / height** (trap 3) — correct anyway
  once geometry stops reading the bars (F4); construct + `initChrome()`; `onFingerDoubleTap`
  stays `openDay` until F4. Net for F3: the calendar honours and echoes the flag, no toggle yet.

### D4 — The calendar (F4)

- `CalendarGeometry.month/week/day(widthPx, heightPx, density)` — the two inset params
  **removed**; `headerTop = 0`, `cellsTop = 0`, `rowsTop = 0`, `bottom = heightPx`; KDoc rewritten
  ("the page is the whole surface; the bars overlay it and the person hides them to write under
  where they were"). Month's square cells still come from the width; the height slack still
  becomes the Notes band (now one bar height taller); Day's 24 rows share the whole height, the
  remainder to the last row, no closing hairline.
- **`CalendarBars` deleted.** `CalendarRender` (HV4 file export) bakes with the new signatures —
  which also covers HV5's papered send (the host's `renderPaper` passes only width / height,
  untouched). `docs/export.md` § Calendar mode's "drawn at the screen's bar insets" becomes
  "full page".
- `CalendarActivity`: geometry calls drop the args; `BakeKey` drops the two bar heights (a flip
  must not re-bake a page-sized bitmap); `awaitLaidOut` already reduced in F3.
- New pure `CalendarDoubleTap.decide(kind, x, y, month: Month?, week: Week?, date): Decision`
  with `sealed Decision { OpenDay(date) · Toggle · Nothing }`: Month / Week → `hitTest` hit →
  `OpenDay`; `y` inside the Notes band → `Toggle`; header, side margins, hairlines → `Nothing`
  (as today); Day → `Toggle`. The listener runs it inside `runPageOp` (serialised against a
  flip's `showPage`).
- `CalendarGeometryTest`'s 107-px fixture (~21 literal sites) → the no-inset signatures, expected
  values **re-derived from the formulas**, never copied from a run. New `CalendarDoubleTapTest`.

### D5 — Docs, ledger, freeze (F5)

No code. `docs/notebook.md` (§ Layout — bars are floating overlays and the toggle; § Gestures
row; § Frame-silence final wording; § Close & lifecycle — the `onResume` re-sync),
`docs/calendar.md` (§ The three pages without insets, `CalendarBars` gone, the zone rule,
§ Gestures, § Export, § Traps — the `awaitLaidOut` hang), `docs/scratchpad.md` (§ The screen,
§ Entry points — the extra in and out), `docs/objects.md` (D2 amended: content = full window; old
notes top-left with the off-page band blocked; `StickyDefaults` row), `docs/sn-screen.md`
(`ChromeBand`, `ChromeToggle`, the `rectOf` visibility rule), `docs/export.md` § Calendar mode,
`docs/extensions.md` (a boundary-audit row for `EXTRA_CHROME_HIDDEN` both directions; the pad /
calendar Intent prose — a fifth boolean on the calendar, the first result datum), both
`CLAUDE.md`s, `RATTA_PLAN.md` header, this file's Outcomes + FROZEN, memory.

## Phases

### ⬜ F1 — The seam + the notebook (Fable: the EPD flip order, the `rectOf` trap, the collision rule · Sonnet adb walk)

**Goal:** D1 built and the notebook toggling; the pad, calendar and sticky editor untouched.

**Files:** `sn-screen/…/notebook/PaperToolbar.kt` (rectOf) · new `ChromeBand.kt` +
`sn-screen/src/test/…/notebook/ChromeBandTest.kt` · new `ChromeToggle.kt` · `PageGestures.kt`
(KDoc table row only) · new `app/…/data/prefs/ChromePrefs.kt` · `app/…/notebook/NotebookActivity.kt`
(`rectOf` delegates; `chromeBand()` → `ChromeBand.of(root.height, topBar.asBar(bottom),
bottomStrip.asBar(top))`; `chromeToggle` over `topBar` + `bottomStrip`, `beforeHide` = hide
lasso / tags / insert / eraser popups, `afterLayout = ::pushExclusions`; `apply(prefs.hidden,
initial = true)` in `onCreate`; the `onResume` re-sync before `resumeDrawing()`;
`onFingerDoubleTap` → the rule → `toggle()` + `prefs.hidden = …`; the snap-margin line keeps
`topBar.height.takeIf { it > 0 }` — a `GONE` bar's stale height **is** the wanted one-toolbar
margin — with a comment, because it reads like trap 1) · new `DoubleTapToggleRule.kt` + test ·
`LinkFollowFlow.followAt(): Boolean` · `docs/notebook.md` frame-silence entry + gestures row
(F5 polishes).

**Gates:** JVM (`:sn-screen` + `:app`, +≈10), debug + release build, NUL scan.

**Sonnet adb walk (Nomad, `.dev`):** open a notebook → `adb shell "input tap X Y & input tap X Y;
wait"` mid-page → `uiautomator dump` has no `topBar` / `bottomStrip`, screencap shows paper to
both edges · again → both back · `force-stop` + relaunch → opens hidden (persisted) · double-tap
on a sticky icon → the editor opens, no toggle on return · hidden: swipe flips, long-press page
sheet, two-finger swipe-down Recents all still work · crash buffer empty.

**User checklist:** (1) hidden: ink to the very top and bottom edge, show → the bars cover the
ink and the pen refuses under a shown bar; (2) hidden: lasso → the selection toolbar appears and
Snap / Copy / Delete work; (3) double-tap with the pen hovering over the page → nothing until the
pen leaves, then a clean double-tap flips.

**Questions to resolve at phase start:** (a) ledger numbering — "exception 8" or "arc 33 added no
new exception; the toggle rides 6"; (b) snap margin: constant "one toolbar" (recommended) or 0
while hidden; (c) version stays `0.1.0-ratta` (every arc so far).

### ⬜ F2 — The sticky editor (Opus on a Fable brief · Fable review · Sonnet adb walk)

**Goal:** D2 — full-bleed paper under a floating bar, full-window stickies, old notes fenced.

**Files:** `app/src/main/res/layout/activity_sticky_editor.xml` · `StickyDefaults.kt` +
`StickyDefaultsTest.kt` (new) · new `StickyPageRects.kt` + test · `StickyEditorActivity.kt` ·
`NotebookActivity.kt` (the `contentSize()` call) · `docs/objects.md` (one sentence).

**Gates:** JVM (`:app` +≈8), builds, NUL scan.

**Sonnet adb walk:** insert a new sticky → the editor opens full-bleed (the `Slog` "note shown
W×H" line = the window) · double-tap → the bar goes, again → back · Back → the notebook is in the
state the editor left (re-sync) · open a pre-arc note → `H < window` in the line, exclusion count
one higher.

**User checklist:** (1) old note: the pen refuses in the band below the page, shown and hidden;
(2) new note: ink to the top edge hidden, show → the bar covers it; (3) hidden: lasso → the
selection bar appears.

**Questions at phase start:** none expected beyond confirming the walk data (a pre-arc sticky must
exist on the Nomad — make one under the F1 build if not).

### ⬜ F3 — The handoff + the scratch pad (Fable: the extension-boundary seam · Sonnet adb walk)

**Goal:** D3 — the flag crosses to the pad and calendar and back; the pad toggles; the calendar
honours and echoes it but keeps day-open on every double-tap until F4.

**Files:** `extension-api/…/ExtensionContract.kt` + its test · `app/…/extension/
ExtensionScreenEntry.kt` · new `app/…/extension/ChromeResult.kt` + test · `ext-ink/…/
InkScreenActivity.kt` · `ext-scratchpad/…/ScratchPadActivity.kt` · `ext-calendar/…/
CalendarActivity.kt` (`awaitLaidOut` + `initChrome()` only).

**Gates:** JVM (`:extension-api`, `:app`, `:ext-ink`), all fourteen modules debug + release, the
three release APKs sign, NUL scan.

**Sonnet adb walk:** hide in the notebook → open the pad → the pad opens hidden · double-tap the
pad → shown · Back → the notebook is shown (the result wrote the pref, `onResume` re-synced) ·
library → calendar → opens per the pref, no hang · `am force-stop` **only the pad process** while
it shows → `RESULT_CANCELED` with null data → the pref unchanged · exactly one "chrome hidden="
line per flip · crash buffer empty.

**User checklist:** (1) pad hidden: lasso → the floating bar shows; (2) ink to the pad's top edge
hidden, show → covered.

**Questions at phase start:** (a) the extra's name (`chromeHidden`) and its home (`ExtensionContract`
vs a per-point constant) — recommended: one constant, both screens read it in one base class.

### ⬜ F4 — The calendar (Opus on a Fable brief · Sonnet background agent for the geometry-test sweep · Fable review · Sonnet adb walk)

**Goal:** D4 — full-page grids on screen and in every render; the zone rule; the calendar toggles.

**Files:** `ext-calendar/…/CalendarGeometry.kt` · **delete** `CalendarBars.kt` · `CalendarRender.kt`
· `CalendarActivity.kt` (geometry calls, `BakeKey`, the listener) · new `CalendarDoubleTap.kt` +
test · `CalendarGeometryTest.kt` (the sweep) · grep `CalendarTemplate` for any other inset reader.

**Gates:** JVM (`:ext-calendar`), builds, NUL scan.

**Sonnet adb walk:** Month → screencap: the weekday header at y = 0 under the bar · cell
double-tap → Day · Day double-tap anywhere → hides; again → shows · Month, double-tap in the Notes
band → toggles; on a cell while hidden → opens the day, no toggle · Export… → Month PNG (the SAF
pick is the user's — trap) → `adb pull` → the grid starts at y = 0 · notebook ← whole-page Send →
the received page's grid matches the calendar's · crash buffer empty.

**User checklist:** (1) pre-arc calendar ink sits one bar higher than it did — accepted, eyeball
once; (2) Day: write in the first row hidden, show → the bar covers it.

**Questions at phase start:** none expected; the Day page's first and last rows living under the
shown bars is decision 2's consequence — say it in the Outcome.

### ⬜ F5 — Docs, ledger, freeze (Sonnet docs fan-out ≤ 5 · Fable read-back, ledger, CLAUDE.md, memory · no code)

D5 as written. **Gates:** the full JVM run (≈ 2987 + the arc's additions), all modules debug +
release, NUL scan of every file the arc touched. **Question at phase start:** `/code-review` on
the arc range or not (every arc since 29: no).

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- The toggle lives on the **paper**, never on a button: no chrome button hides chrome (there is
  no way back without the gesture, and the gesture is the whole point).
- A hidden-mode Back is the hardware / gesture Back the device already has; nothing new.
- The calendar's `openingOverlay` and the notebook's "Opening…" overlay are unaffected — they are
  whole-screen and come down when the page lands, shown or hidden.
- The Contents and Recents dialogs and every `ActionSheetDialog` open as they do today; they are
  windows, not chrome, and `pushExclusions`' `BLOCK_ALL` branch for Contents / Recents is untouched.
- `PageGestures` gets **no code change** — the recogniser, escrow and histories are already what
  the feature needs; a KDoc row is the whole edit.
- The walk's double-tap is `adb shell "input tap X Y & input tap X Y; wait"` (two sequential
  `input tap`s miss the 300 ms window — arc 23 / Y2).

## Standing traps that bind this arc

- **`GONE` keeps the last measured size; `INVISIBLE` keeps the pen claim.** Every rect reader on
  the flip path must check visibility (trap 1); no bar is ever `INVISIBLE`.
- **A later sibling at `match_parent` sits ON TOP of an earlier button in a `FrameLayout`** —
  the sticky editor's restructure adds the bars last (F2).
- **Frame-silence:** `releaseRender()` before every chrome frame; the toggle is a ledgered
  exception, never `whenPenIdle`; `isPenActive` counts hover.
- **ActivityResult callbacks run BEFORE `onResume`** — the result's pref write at the top of
  `onResult`, the re-sync in `onResume`; never the other order.
- **A Binder call cannot be cancelled** — no new timeout; the extra rides existing calls.
- **EPD handoff** unchanged: entries still `releaseForHandoff` in `beforeLaunch`; the screens
  release before every `finish()`; the flip never touches the handoff.
- **Two handlers reading one piece of state: order the write after the read** — the two-deep
  hit history is written by `onFingerTap` and read by `onFingerDoubleTap`, in that escrow order.
- **`am start` onto an already-RESUMED activity fires no `onResume`** — HOME or `force-stop`
  first in every walk step; **force-stop the host first, then the extensions** when a device
  death is the thing under test (RS2); for F3's "pad killed under a live host" step the reverse
  is the point.
- **Walk the `.dev` package**; check `mCurrentFocus` (PIN sleep) and `dumpsys activity
  activities | grep mResumedActivity` before any screencap conclusion; the Supernote drops off
  adb — re-plug.
- **A SAF pick cannot be driven by adb** — the F4 PNG export's folder pick is the user's hand.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **GONE, never disabled; a screen that explains itself and then leaves, leaves on DISMISS.**
- **Doc agents never run git, never revert files they did not create.**
- **`Widget.Notesprout.TextButton` / `LatchButton` set no `layout_width`** — any new
  `AppCompatButton` in the sticky editor's XML needs its own.

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

*(one Outcome entry per phase as it closes)*
