# CORNER_PLAN.md — Arc 36 "Corner" (Notesprout SN, branch `ratta`)

**Standalone plan for the collapsed chrome: a corner tool button with a mini toolbar** — a fresh
user decision (2026-09-11), not a `PARITY_BACKLOG.md` item (the backlog is closed). This file is
the cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_sn/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end.
`FOCUS_PLAN.md` (arc 33) is the shape this file copies and the arc this one grows.

**Status: ✅ ARC COMPLETE + FROZEN 2026-09-11 — C1 ✅ · C2 ✅ · C3 ✅.** The references are `docs/notebook.md` (§ Layout, § Gestures, § Frame-silence), `docs/sn-screen.md` (`CollapsedChrome` / `CollapsedTools`, the `ChromeToggle` / `AnchoredBar` additions), `docs/scratchpad.md`, `docs/calendar.md` (§ Gestures), `docs/objects.md` (§ Sticky notes); this file is the ledger. Final 1662 `:app` / 3097 JVM tests, API 10, fourteen modules, g-paper 0.1.28, `0.1.0-ratta`. No next arc without a fresh user decision.
Baseline before the arc: 1654 `:app` / 3079 JVM tests, g-paper 0.1.28, `API_VERSION` 10,
fourteen modules, version `0.1.0-ratta`. No point, no API bump, no schema change, no g-paper
change, no new module, no new Intent extra — the one global flag (`ChromePrefs` /
`EXTRA_CHROME_HIDDEN`) is unchanged in meaning: **"hidden" now means "collapsed to the corner".**

**Phase code:** **C** — three phases C1–C3.

---

## What this arc is

Arc 33 made a finger double-tap hide every bar, leaving bare paper. That left the tools one
gesture away and the doors two. This arc replaces the bare state: while the chrome is hidden a
**single floating button sits at the top-right corner wearing the armed tool's icon**. Tapping it
opens a **mini toolbar** hung under it — the tools in a fixed order with the armed one bordered,
the notebook's Insert after them, and a `…` button that opens a **second icon row** holding Back
and the screen's doors and actions. Picking a tool arms it, closes the mini toolbar and repaints
the corner button. A tap anywhere else closes it. A double-tap while collapsed brings the full
bars back (and the corner button goes); a double-tap while shown collapses to the corner button.

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `ChromeToggle` (`:sn-screen`) — the flip order once, bars `GONE`/`VISIBLE` | all four screens | gains `whileHidden` views (shown while the bars are hidden) and a `beforeShow` hook |
| `AnchoredBar` — a bordered row of icon buttons hung under an anchor, measured-then-placed, clamped to `[0, rootWidth]` | lasso popup, tags popup, Insert bar, eraser sub-bar | the mini toolbar **and** the overflow row; gains `show(anchor)` so a sub-bar can hang under a mini-toolbar button |
| `PaperToolbar.rectOf` (visibility-aware), `ChromeBand`, each screen's `pushExclusions` / `overChrome` / outside-tap dismissals | | the corner button and both rows join every list |
| `PaperToolbar.sync` / `NotebookToolbar.sync` from `onToolChanged` | the bars stay honest under a component-initiated tool change | the corner button and mini toolbar are synced from the same call |
| `ActionSheetDialog` | long-press sheets | **not used** — the user chose an icon row over a sheet (decision 4) |

## Decisions (wizard 2026-09-11 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Surfaces | **All four** — notebook, scratch pad, calendar, sticky editor. |
| 2 | Mini toolbar contents | **All tools in a fixed order, the armed one shown selected**: Pen · Point eraser · Lasso eraser · Lasso (· Insert on the notebook) · `…`. |
| 3 | The two erasers | **Two buttons in the mini toolbar**; no nested Point · Lasso sub-bar while collapsed. The full bar keeps its re-tap sub-bar. |
| 4 | Overflow form | **A second icon row** (an `AnchoredBar` hung under the `…` button), icon-only with long-press hints — not an action sheet. |
| 5 | Overflow contents | **Back first, then the screen's doors and actions** in bar order. Notebook: Back · Contents · Document · Tags · Recents · Calendar · Scratch Pad (each absent when its bar button is). Calendar: Back · Today · Month · Week · Day · Send/Export · Events · Scratch Pad. Pad: Back · Send and sticky editor: Back — **on the mini toolbar itself, no `…`** (the user's calls after the C2 walks: one or two overflow entries are not an overflow; `CollapsedTools.overflowInline`, `INLINE_MAX = 2`; three or more go behind `…`). No pager — swipe flips pages while collapsed. |
| 6 | Housekeeping | Version stays `0.1.0-ratta`; **`/code-review high` over the arc range at the freeze** (C3). |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **The corner button is the armed tool, always** — `Tool.PEN` → pen, `ERASER` → eraser,
  `LASSO_ERASER` → lasso eraser, `LASSO` → lasso (the clipboard-loaded lasso glyph when the
  notebook's clipboard holds objects, as the bar's button does); `Tool.NONE` wears the pen.
  Synced from the same `onToolChanged` that syncs the bar, so a smart-lasso arm/restore repaints it.
- **A tap on the armed tool in the mini toolbar closes the mini toolbar and changes nothing** —
  the bar's re-tap meanings (clipboard popup, eraser sub-bar) are not carried into the mini
  toolbar; the erasers are two buttons and tap-to-place paste needs no popup.
- **Insert stays a command**: it opens the Insert bar under the mini toolbar's Insert button
  (the mini toolbar stays up beneath it); a pick lands selected and closes everything. Tags
  likewise opens its popup under the overflow row's Tags button.
- **Overflow entries mirror their bar buttons**: visibility (a door absent from the bar is absent
  here), the selected look (the calendar's Month/Week/Day latches), the icon (the calendar's
  Send/Export glyph) — read at every open, so an entry can never show a door the bar would not.
  Tapping one closes the collapsed chrome and performs the bar button's own click.
- **The corner button sits inside the root at `top|end` with an 8 dp margin**, background
  `shape_dialog_bordered` (a floating bar of one button), size the toolbar-button dimen. It is
  declared in each layout before the opening overlay — the overlay stays topmost.
- **Exclusions and `overChrome`**: the corner button and both rows are chrome — the pen refuses
  under them, a finger on them is not a page gesture, and their rects are pushed on every
  open/close (`onChanged`).
- **Outside-tap dismissal is the existing rule**: any pointer landing outside the corner button,
  the rows and a sub-bar hung off them (Insert, Tags) takes the rows down; the corner button is
  excluded so its own re-tap toggles instead of close-then-reopen.
- **The rows go down at every place the other floating bars do**: a page swap, a hide → show
  flip (`beforeShow`), a tool pick, an overflow action.
- **Render release**: opening a row is one chrome frame at a deliberate tap with the pen still
  hovering — ungated `paper.releaseRender()` (the Insert bar's rule); a tool pick is the eraser
  sub-bar's pen-gated `PenIdle.releaseRenderIfIdle`.
- **Nothing new is persisted**: the flag is the one boolean; whether a row is open is not state.
- **Gestures unchanged**: `PageGestures`, the collision rule, the calendar's zone rule — none
  change. The double-tap still toggles the flag; what "hidden" looks like is the only difference.

---

## Design

### D1 — The seam (`:sn-screen`)

- `ChromeToggle(paper, root, bars, beforeHide, afterLayout, onChanged, whileHidden = emptyList(),
  beforeShow = {})` — `whileHidden` views take the inverse visibility of `bars` in the same flip;
  `beforeShow` runs on a hide → show flip (the consumer dismisses the rows).
- `AnchoredBar.show(anchor: View = this.anchor)` — the default anchor stays the constructor's.
- New pure `CollapsedTools` object: `iconFor(tool, clipboardLoaded): Int`, `selectedFor(tool)`,
  `outsideTapDismisses(showing, onKnob, onChrome, keep): Boolean` + `CollapsedToolsTest`.
- New `CollapsedChrome(root, knob, miniBar, overflowBar, paper, bandBottom, canOpen, penHint,
  lassoHint, commands, overflow, onOpen, onArmed, onChanged)`:
  - `Entry(iconRes, hint, mirrors: View? = null, onTap: ((View) -> Unit)? = null)` — the mini
    toolbar's commands (after the tools) and the overflow row's entries. A mirrored entry with no
    `onTap` dismisses and `performClick()`s its bar button; `onTap` receives the entry's own
    button as an anchor and owns dismissal.
  - `sync(tool)`, `showClipboardLoaded(loaded)`, `rects()`, `contains(x, y)`,
    `dismissOnContact(x, y, keep)`, `dismiss()`, `isShowing`.
  - Strings `collapsed_tools` ("Tools") / `collapsed_more` ("More") in `:sn-screen`.

### D2 — The notebook (C1)

`activity_notebook.xml`: `collapsedKnob` (`AppCompatImageButton`, `top|end`, 8 dp margin,
`shape_dialog_bordered`), `collapsedBar`, `collapsedOverflow` — before `openingOverlay`.
`NotebookActivity`: construct after the toolbar and the popups; `commands = [Insert → showInsertBar(anchor)]`;
`overflow = [Back, Contents, Document, Tags → showTagsPopup(anchor), Recents, Calendar, Scratch Pad]`
mirroring the bar buttons; `onOpen` hides the four popups + ends a transform; `onArmed =
{ hideInsertBar(); hideTagsPopup(); toolbar.arm(it) }`; `onChanged = ::pushExclusions`;
`onToolChanged` → both syncs; `showClipboardLoaded` at every site; `ChromeToggle(whileHidden =
[knob], beforeShow = collapsed::dismiss)`; the page-swap hide list, `pushExclusions`, `overChrome`
and `dispatchTouchEvent` gain the collapsed chrome; `dismissInsertBarOnContact` /
`dismissTagsPopupOnContact` leave a contact inside the collapsed chrome alone (the mini
toolbar's own buttons toggle their sub-bars); the Insert / Tags pick lambdas dismiss the rows.

### D3 — The other three (C2)

`InkScreenActivity` (pad + calendar): abstract `collapsedKnobView` / `collapsedBarView` /
`collapsedOverflowView`, `collapsedCommands()` / `collapsedOverflow()` suppliers (default: Back
only), `initChrome` builds `CollapsedChrome` before the toggle, `floatingRects` /
`floatingContains` / `dispatchTouchEvent` gain it; the subclass's `syncTool` calls both syncs.
Pad overflow: Back · Send. Calendar overflow: Back · Today · Month · Week · Day · Send · Events ·
Scratch Pad. Sticky editor: the notebook's wiring without commands; overflow = Back.

### D4 — Review, docs, freeze (C3)

`/code-review high` over the arc range, fix what is confirmed. Docs: `docs/notebook.md` (§ Layout
— the collapsed state, § Gestures), `docs/sn-screen.md` (`CollapsedChrome`, `CollapsedTools`, the
`ChromeToggle` / `AnchoredBar` additions), `docs/scratchpad.md`, `docs/calendar.md`,
`docs/objects.md` § Sticky notes, both `CLAUDE.md`s, `RATTA_PLAN.md` header, this file's ledger,
memory.

## Phases

### ✅ C1 — The seam + the notebook (Fable · Sonnet adb walk · user checklist)

**Files:** `sn-screen/…/notebook/ChromeToggle.kt`, `AnchoredBar.kt`, new `CollapsedTools.kt` +
test, new `CollapsedChrome.kt`, `sn-screen/res/values/strings.xml` · `app/res/layout/
activity_notebook.xml` · `app/…/notebook/NotebookActivity.kt`, `InsertBar.kt`, `TagsPopup.kt`.

**Gates:** JVM (`:sn-screen` + `:app`), debug build, NUL scan, install on the Nomad (`.dev`).

**Walk (Nomad):** double-tap → bars gone, the corner button shows the pen · tap it → the mini
toolbar (Pen bordered) · tap Lasso eraser → row closes, corner button = lasso eraser · tap the
corner button, tap `…` → the overflow row with Back and the doors · tap paper → both rows close ·
double-tap → full bars, corner button gone, bars show lasso eraser armed · Insert from the mini
toolbar → the Insert bar under it, a shape lands selected and every row closes.

**User checklist:** (1) the pen refuses under the corner button and an open row, inks beside them;
(2) a lasso while collapsed still raises the selection toolbar; (3) a pick from the mini toolbar
arms without a stray frame.

### ✅ C2 — The scratch pad, the calendar, the sticky editor (Opus on a Fable brief · Fable review · Sonnet adb walk)

D3. **Gates:** JVM (`:ext-ink`, `:ext-scratchpad`, `:ext-calendar`, `:app`), all modules debug,
NUL scan, Nomad walk of all three.

### ✅ C3 — Review, docs, freeze (Fable review · Sonnet docs fan-out ≤ 5 · Fable read-back)

D4. **Gates:** the full JVM run, all modules debug + release, NUL scan of every touched file.

## Standing traps that bind this arc

- **`GONE` keeps the last measured size; `INVISIBLE` keeps the pen claim.** Every rect reader
  uses `PaperToolbar.rectOf`; the corner button is `GONE` while the bars show.
- **A later sibling at `match_parent` sits on top in a `FrameLayout`** — the corner button and
  rows go before the opening overlay and after every bar they may overlap.
- **Frame-silence:** `releaseRender()` before every chrome frame; never `whenPenIdle` for a row.
- **`AnchoredBar.show` refuses before the root is laid out** — a toggle stays honest.
- **A sub-bar hung under a `GONE`-parented anchor lands where the bar used to be** — hence
  `show(anchor)` with the mini toolbar's own button, never the bar's.
- **The outside-tap dismissal must exclude the button that toggles** (close-then-reopen).
- **`onToolTapped` fires only on an actual change** — the mini toolbar's pick goes through
  `toolbar.arm`, which does not fire it; the consumer hides the sub-bars itself in `onArmed`.
- **Walk the `.dev` package on the Nomad only**; double-tap over adb = `adb shell "input tap X Y &
  input tap X Y; wait"`; check `mCurrentFocus` before any screencap conclusion.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Doc agents never run git, never revert files they did not create.**

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

One phase per session where possible; read this file whole at phase start; Fable plans / seams /
reviews, Opus features, Sonnet scaffold / docs / walks; ≤ 5 background agents; JVM tests for every
pure piece; the user gets a short numbered checklist only for what needs a hand or an eye; Nomad
only; commit + push only when every suite is green (or the user's all-clear), after docs / memory /
CLAUDE.md are in. A long explanation and an `AskUserQuestion` never share one turn.

## Ledger

*(one Outcome entry per phase as it closes)*

### C1 ✅ 2026-09-11 — The seam + the notebook

**Landed (Fable).** `:sn-screen`: `ChromeToggle(whileHidden, beforeShow)` — the inverse-visibility
list flipped in the same pass as the bars, the hide → show hook; `AnchoredBar.show(anchor = this.anchor)`;
pure `CollapsedTools` (`ORDER`, `iconFor(tool, clipboardLoaded)`, `selectedFor`,
`outsideTapDismisses`) + `CollapsedToolsTest` (10); `CollapsedChrome` (corner button + mini
toolbar + overflow row, both rows `AnchoredBar`s hung under the corner button / the `…` button;
`Entry(iconRes, hint, mirrors, onTap)` — mirrored entries copy visibility / selected / glyph at every
open and `performClick()` the bar button; `sync`, `showClipboardLoaded`, `rects`, `contains`,
`dismissOnContact(x, y, keep)`, `dismiss`); strings `collapsed_tools` / `collapsed_more`. Host:
`activity_notebook.xml` gains `collapsedKnob` (`top|end`, 8 dp, `shape_dialog_bordered`) +
`collapsedBar` + `collapsedOverflow` before the opening overlay; `InsertBar.show(anchor)` /
`TagsPopup.show(anchor)`; `NotebookActivity` — `collapsed` built after the popups with Insert as
the one command (its bar under the mini toolbar's button) and Back · Contents · Document · Tags
(popup under the overflow's button) · Recents · Calendar · Scratch Pad mirrored; `onOpen` hides the
four popups + ends a transform; `onArmed = { hideInsertBar(); hideTagsPopup(); toolbar.arm(it) }`;
`markClipboard()` replaces the five `toolbar.showClipboardLoaded` sites; `onToolChanged` syncs
both; the page-swap hide list, `pushExclusions`, `overChrome`, `dispatchTouchEvent`
(`dismissCollapsedOnContact`, keep = Insert / Tags bars) and the Insert / Tags dismissals (a
contact inside the collapsed chrome is its own) all know it.

**Numbers:** `:app` 1654 → **1662**, `:sn-screen` +10; debug build; NUL scan clean.

**Nomad walk (adb, `.dev`), all green:** double-tap → bars gone, `collapsedKnob` at
[1273,15][1389,131] · tap → the mini toolbar right-aligned under it (Pen bordered) · Lasso eraser
pick → rows close, `armed LASSO_ERASER` from both the bar and the collapsed chrome · reopen →
Lasso eraser bordered · `…` → the overflow row (Back … Scratch pad) · outside tap → both rows
down · Insert → its bar under the mini toolbar; Rectangle → lands selected, every row closed ·
double-tap → full bars, eraser button armed with the lasso-eraser glyph, corner button gone ·
crash buffer empty. **User checklist passed by hand (3/3).**

**Walk trap (new, environmental):** the Nomad carries BOTH the release and `.dev` build of every
extension, and the `.dev` host binds the release scratch pad first (`ExtensionRegistry … ignoring
additional scratch pad …dev`) → `SecurityException: caller is not the host` — the pad and calendar
doors are dead from the dev host until the release extensions are disabled (`pm disable-user`).
Not arc 36's.

### C2 ✅ 2026-09-11 — The scratch pad, the calendar, the sticky editor

**Landed (Opus on the brief, Fable review).** `InkScreenActivity` (pad + calendar): abstracts
`collapsedKnobView` / `collapsedBarView` / `collapsedOverflowView` / `backButtonView` / `penHint` /
`lassoHint` / `armTool`, open `collapsedOverflow()` (default Back alone via `backEntry()`, hint =
the button's own content description), `initCollapsed(root)` called by `initChrome` **before** the
toggle (all three views null → no collapsed chrome, every site `isInitialized`-guarded),
`ChromeToggle(whileHidden = knob, beforeShow = dismissCollapsed)`, `beforeHide` also
`collapsed.sync(paper.tool)`, `syncArmed(tool)` = `syncTool` + `collapsed.sync` at every by-hand
sync site and from `onToolChanged`, `floatingRects` / `floatingContains` / `dispatchTouchEvent` /
`exit()` know it; the file's over-800 reason written in its KDoc. Pad: overflow Back · Send
(mirrored). Calendar: Back · Today · Month · Week · Day · Send/Export · Events · Scratch Pad, all
mirrored through `mirrorEntry(icon, button)` — the view latches and the out-door's glyph come off
the bar at every open; the zone rule untouched. Sticky editor: the notebook's wiring, overflow =
Back. Layouts: the three views before `openingOverlay` (pad, calendar), last (sticky, no overlay).

**Review fix folded in (Opus's doubt 1, kept):** a bar tool tap sets `paper.tool` host-side and is
never echoed as `onToolChanged`, so the corner button is synced from `paper.tool` in every
screen's `beforeHide` — the moment it is about to appear. The notebook got the same line.

**User calls after the sticky and pad walks:** *one or two overflow entries are not an overflow*
— `CollapsedTools.overflowInline(count) = count in 1..INLINE_MAX` (2; +1 test); `CollapsedChrome`
puts them on the mini toolbar itself and builds no `…`. The sticky editor's mini toolbar is Pen ·
Point eraser · Lasso eraser · Lasso · Back; the pad's ends Back · Send. Notebook and calendar keep
the `…` (seven and eight doors).

**Numbers (before C3's fixes):** `:sn-screen` 92 (11 collapsed tests), `:app` 1662, `:ext-calendar`
322, `:ext-scratchpad` 54, `:ext-ink` 52 — 3098 across the modules; all modules debug; NUL clean.
**User checklist passed by hand (all four screens, 2026-09-11)** with two calls folded in: the
sticky editor's Back and the pad's Back · Send inline (`INLINE_MAX = 2`).

**Nomad walk (adb, `.dev`), all green:** sticky editor — collapse, mini toolbar (five buttons),
Lasso pick bordered on reopen, Back returned to a notebook that re-synced itself collapsed · pad —
collapse, mini + overflow Back · Send, outside tap, overflow Back home with the state carried ·
calendar — Notes-band double-tap collapses, overflow of eight with Month bordered, Week from the
overflow → the view flipped and Week bordered on reopen, overflow Back home · crash buffer empty.
(The sticky walk ran before the inline-Back change; that variant is the user's to eyeball.)

**Environment:** the release `ext.scratchpad` / `ext.calendar` packages were `pm disable-user`'d
on the Nomad (user's go-ahead) so the dev host binds the `.dev` extensions — **re-enable with
`pm enable` when the user asks**; nothing uninstalled.

### C3 ✅ 2026-09-11 — Review, docs, freeze

**`/code-review high` over the arc's working tree (eight finder angles, verified by Fable).**
Confirmed and fixed:
- **Orphaned sub-bars** — the corner button's re-tap, the `…` re-tap, a mirrored overflow entry
  and the hide → show flip took the rows down but not the Insert bar / tags popup hung off them
  (the notebook's per-bar dismissals now leave a contact inside the collapsed chrome alone).
  Fix at the seam: `CollapsedChrome.onClose` fires before either row goes down by any path (the
  notebook passes raw `insertBar.hide(); tagsPopup.hide()`; one `onChanged` push follows).
- **Insert under the overflow row** — both hang off the mini toolbar's bottom edge and the later
  sibling painted over the Insert bar. `CollapsedChrome.hideOverflow()`; the notebook's Insert
  command takes the overflow down first; `showTagsPopup` also hides the Insert bar (the bar's own
  dismissal cannot, from a contact inside the collapsed chrome).
- **Two by-hand arms never repainted the corner button** (`armLasso`,
  `restoreToolAfterTransferPaste`) — the per-site fan-out (`syncArmed` wrappers, a `beforeHide`
  sync line ×3, a second `onToolChanged` line ×2) had already missed two sites. Root fix:
  `PaperToolbar` / `NotebookToolbar` gained **`onSynced`**, fired at the end of every `sync` — the
  one funnel every tool change passes through — and `CollapsedChrome.sync()` **caches nothing**
  (it reads `paper.tool`). Every wrapper, every `beforeHide` sync line and every second
  `onToolChanged` line deleted; `InkScreenActivity.syncCollapsed()` is what the pad's and
  calendar's toolbars wire in. Walked: Insert → Rectangle lands under LASSO, the corner button
  wears the lasso.
- **Paste on dismissal** — a pen tap spent closing the rows under an armed, clipboard-loaded
  lasso also pasted. `dismissOnContact` answers whether it fired; the notebook sets
  `tapDismissedPopup` (the lasso popup's latch, whose second meaning this is).
- **Simplifications taken:** `outsideTapDismisses(showing, onChrome, keep)` (the corner button is
  part of `contains`, so `onKnob` was dead and the knob rect was computed twice — and the idle path
  is now one flag read); `Entry.mirroring(iconRes, button, onTap)` (the hint is the button's own
  content description — replaces the calendar's `mirrorEntry`, `backEntry`'s body, the notebook's
  seven `getString` hints and the pad's Send); `penHint` / `lassoHint` plumbing removed (two ctor
  params, two abstracts, four overrides → `tool_pen` / `tool_lasso` in `:sn-screen`);
  `refresh` per row with a constant-state identity check before any drawable clone (steady state:
  flag compares, zero allocation); one render release per pick (`toolbar.arm`'s); `ChromeToggle`'s
  hooks fire only on an actual transition; `AnchoredBar.show` refuses a non-`VISIBLE` anchor
  (`rectOf`, the arc-33 rule) so a no-arg show while collapsed is a loud no-op, never a bar hung
  under stale edges; the construction-time `showClipboardLoaded` (redundant with the open path's
  `markClipboard`) deleted; `docs/sn-screen.md`'s "every other string stays in `:app`" corrected.
- **Declined (do not re-raise):** `iconFor` as the single glyph rule for the three pre-arc bars
  (pre-arc code, no behaviour); a `<merge>` include for the three collapsed views (`:sn-screen` has
  no `res/layout`; the eraser-bar precedent copied the same way); `ChromeToggle` owning the
  collapsed chrome (two lines per screen, and the toggle stays ignorant of what it flips);
  `Tool.NONE` wearing the pen (nothing arms NONE; the pen names what a tap brings back).

**Numbers:** `:sn-screen` 91 (`CollapsedToolsTest` 10), `:app` 1662, `:ext-calendar` 322,
`:ext-scratchpad` 54, `:ext-ink` 52 — **3097** across the modules; every module debug; NUL clean.

**Nomad re-walk (adb, `.dev`), all green:** `…` open → Insert → the overflow closed, the Insert bar
alone under the mini toolbar · corner re-tap → rows and Insert bar all down · Insert → Rectangle →
selected, corner button wears the lasso (screencap) · crash buffer empty.

**Docs:** `docs/notebook.md`, `docs/sn-screen.md`, `docs/scratchpad.md`, `docs/calendar.md`,
`docs/objects.md` (Sonnet fan-out, Fable read-back + the post-review corrections), both
`CLAUDE.md`s, `RATTA_PLAN.md` header, this ledger, memory.

**Left on the Nomad:** the release `ext.scratchpad` / `ext.calendar` packages are
`pm disable-user`'d (re-enable with `pm enable` on request); the test notebook carries one page of
pad ink the C2 walk's Send pasted in.
