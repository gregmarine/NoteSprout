# `:sn-screen` — the shared paper-screen library

*Arc 11 / J1. Read this before adding, moving, or removing anything in the module.*

SN grew a second paper surface in arc 11: the Scratch Pad, which lives in its own extension APK,
in its own process, with its own g-paper canvas. Both surfaces need the same e-ink design
resources and the same handful of screen helpers, and the alternative to sharing them is the
sibling-copy trap og Notesprout still carries — `RattaNotebookView` is a hand-maintained copy of
`GenericNotebookView`, and every fix to shared logic has to be applied to both files or one of them
silently rots. `:sn-screen` is that trap refused up front.

## What the module is allowed to depend on

g-paper (`api`, so `PaperView` and `Stroke` reach both consumers transitively) and androidx
(`core-ktx`, `appcompat`). **Never `:app`** — the module has to build with the host absent — and
**never `:extension-api`**. That second exclusion is deliberate and load-bearing: keeping the
contract out of here is what makes the host's transfer mapping and the extension's own ink mapping
two twin translations rather than one shared class that quietly becomes part of the wire format.
No Room, no SQLCipher, no serialization: nothing here knows what a `.soil` is.

`:app` depends on `:sn-screen`; so do `:ext-scratchpad`, `:ext-document`, `:ext-tags`,
`:ext-calendar` and — since arc 23 / Y1 — **`:ext-ink`**, the ink-on-rows library the pad and the
calendar share (`InkWire`, `StrokeRows`, `StoreBatches`, `StrokeReadPlan`, `InkDocument`,
`InkAction`, the `InkStore` base, and — since Y4's review — the shared stroke SQL/DDL (`InkSql`),
the `InkPage` contract, the transfer session (`InkTransferSession<P, R>`) and the abstract tier-2
ink screen (`InkScreenActivity`)). `:ext-ink` is the one module that depends on **both** this and
`:extension-api` (`api` on each), which is exactly why those helpers could not live here: they are
extension-side code over the contract's `Statement` and `WireStroke` — and, since Y4,
`InkScreenActivity` extends this module's own `AppCompatActivity`, which is why `:ext-ink` also took
on `api(appcompat)` (a version both consumers already declared, no new library on the graph). It
never depends on `:app`.
g-paper is **not** declared in any consumer — it arrives through this module's `api(...)`, and the
version pin lives here.

## The namespace and the R-class flag

The module's Android namespace is `com.symmetricalpalmtree.notesproutsn.screen` — deliberately not
the app's, because two modules sharing a namespace would collide on `R` and `BuildConfig`. The
Kotlin **packages** of everything that moved are unchanged (`…notesproutsn.core`,
`…notesproutsn.notebook`), which is why the move needed no import sweep in `:app`; the only three
lines that changed are the `R` / `BuildConfig` imports of `Dialogs`, `ActionSheetDialog` and
`Slog`, which now point at the module's own.

`gradle.properties` sets **`android.nonTransitiveRClass=false`**. AGP 8.11 defaults it to
non-transitive, and without the line every moved resource falls out of `:app`'s `R` — hundreds of
compile errors. Do not remove it.

`Slog` gates on **this module's** `BuildConfig.DEBUG` (`buildFeatures.buildConfig = true`). The
app's debug build consumes the library's debug variant, so the gate means exactly what it meant in
`:app`. Any module whose tested code reaches `Slog` also needs
`testOptions.unitTests.isReturnDefaultValues = true`.

## What lives here

| Kotlin | What it is |
|---|---|
| `core/StrokeCodec`, `core/InkColorCodec` | the format-B stroke blob and the ink-colour token — the family's byte-compatible encodings |
| `core/Slog` | the debug-gated logger |
| `core/Dialogs` | the bordered-window `AlertDialog` helpers (`problem`, and the window styling every dialog routes through) |
| `core/ActionSheetDialog` | the "what do you want to do with this?" sheet — hairline-separated rows built in code, because the row *count* is the content |
| `core/TopGuard` | the top-edge guard — **0 on Ratta**, where chrome sits flush at the top |
| `core/Immersive` | system bars hidden, transient by swipe |
| `notebook/PageMath` | page-index arithmetic |
| `notebook/SelectionAnchor` | where a floating bar may sit relative to a selection |
| `notebook/PageGestures` | the finger vocabulary — flips, inserts, the two swipes, the multi-finger undo/redo taps, the long-press, and (arc 23 / Y2) `Listener.onFingerDoubleTap` — a second, independent history over the same qualifying bare taps, so a consumer can add a double-tap without touching `onFingerTap`, which stays byte-identical. Pen-gated throughout |
| `core/SwipeMath` | the one horizontal-flip rule, in pure arithmetic — shared by `PageGestures` and `ListSwipe` so a page turn means the same travel everywhere. JVM-tested |
| `core/ListSwipe` | the one-finger flip for a **paginated list** (F3): `SwipeMath` applied to a region rather than the screen, armed only inside it, finger-only, observer-only |
| `notebook/UndoRedoStack<A>` | the generic LIFO history plus its `generation` counter. The notebook's fourteen action kinds stay in `:app` as `NotebookUndo.Action` |
| `notebook/AnchoredBar` | arc 29 / LE2 — **moved here from `:app`** (same package, `R` repointed; the three `:app` callers — the lasso popup, the tags popup, the Insert bar — untouched): the floating-bar placement primitive, one bordered row of buttons anchored under a view and clamped to the root |
| `notebook/EraserBar` | arc 29 / LE2–LE3 — the eraser button's **Point · Lasso** sub-bar, built on `AnchoredBar`; one implementation shared by all four paper surfaces (the notebook, the sticky editor, the scratch pad, the calendar) rather than a fourth top-bar button (an eleventh 62 dp button already fills the Nomad's bar with every extension installed; a twelfth falls off the edge) |
| `notebook/PaperToolbar` | back + the three tool buttons, **binding-free**; since arc 29 / LE2 also carries `onEraserReTap` (a second tap on the armed eraser opens `EraserBar` rather than doing nothing), `onToolTapped` (an actual tool change, so a consuming screen can close floating chrome), and public `arm(tool)` (the sub-bar's pick lands here — a host-set tool is never echoed back as `onToolChanged`, so `sync` has to be called by hand); `sync` selects the eraser button under **either** eraser kind and swaps its glyph only on a change of kind (frame silence — every `onToolChanged` lands in `sync`, and re-setting the same drawable would invalidate the button for nothing). **Its companion `rectOf(v)` gained a visibility check at arc 33 / F1** — `if (v.visibility != View.VISIBLE) return null`, ahead of the existing size check — because a `GONE` view keeps its last measured width and height (trap 1): without the check a hidden bar would keep excluding ink and swallowing gestures exactly where it used to sit. `PaperChrome.pushExclusions`/`overChrome`, `FloatingSelectionBar.rects`, the sticky editor's own `overChrome` and the notebook's own `rectOf` (now `= PaperToolbar.rectOf`) all inherit the fix from this one place; `AnchoredBar`'s private `rectOf` already carried the same check independently — the rule was **promoted** into the shared function, not copied into it |
| `notebook/PaperChrome` | exclusion rects and the over-chrome hit test, with the host-specific parts as suppliers |
| `notebook/FloatingSelectionBar` | an extension screen's floating selection bar (arc 23 / Y1 — the pad's own, shared so the calendar's is not a sibling copy): a row of buttons built to the one recipe, placed by `SelectionAnchor` next to the lasso box; the consumer says which buttons. `buttonAt(index)` (arc 28 / H5) hands back one built button for a consumer whose button carries **state** the bar itself cannot know — the sticky editor's Snap latch, which wears the selected border and re-words its hint exactly as the notebook's own Snap button does |
| `notebook/PenIdle` | arc 23 / Y4 — the two pen-activity gates every paper-hosting screen writes against: `whenIdle` (the frame-silence gate, re-posting at `PaperView.PEN_ACTIVE_TAIL_MS` while the pen is active) and `releaseRenderIfIdle` (`PaperView.releaseRender`'s own pen-gated contract); one copy rather than the four that had grown across the pad's and the calendar's toolbars and screens — `PaperToolbar` is trimmed to call it too |
| `notebook/InkSelectionBar` | arc 23 / Y4 — the ONE Send-then-Delete floating bar an ink-on-paper extension screen puts over a lasso selection, replacing the pad's and the calendar's own `*SelectionToolbar` copies; built on `FloatingSelectionBar`, Send absent (never disabled) with no notebook behind the caller |
| `notebook/ChromeBand` | arc 33 / F1 — the pure rule for the free band between a screen's two chrome bars: `of(rootHeight, top: Bar?, bottom: Bar?): IntRange?`, `Bar(shown, edge, laidOut)`. A hidden bar contributes the root's own edge (0 / `rootHeight`) rather than withholding the band; only a **shown** bar that has not laid out yet returns null (trap 2 — the pre-arc `chromeBand()`s each returned null whenever either bar's height was 0, which is exactly what a `GONE` bar reports, so every floating bar — `AnchoredBar.show`, `FloatingSelectionBar`/`SelectionToolbar`'s `show` — would have silently refused to show while the chrome was hidden). `View.asBar(edge)` builds a `Bar` from a real view — Android-typed and untested on purpose, since the rule under it is what `ChromeBandTest` (12) covers. Replaces the three per-screen `chromeBand()` lambdas (the notebook, `InkScreenActivity`, the sticky editor's two) with one pure copy |
| `notebook/ChromeToggle` | arc 33 / F1 — hides/shows a paper screen's chrome bars in one written-once flip order: `paper.releaseRender()` (skipped when `initial`, since `onCreate` has nothing on the glass) → hiding only: `beforeHide()` (the consumer's button-anchored popups — lasso, tags, insert, eraser — come down because their button is about to go) → every bar `GONE`/`VISIBLE` (**never `INVISIBLE`** — an attached Ratta paper view keeps the pen claimed whatever a sibling's visibility, and an `INVISIBLE` bar would keep its rect) → `root.doOnNextLayout { afterLayout() }`, the consumer's `pushExclusions()`, re-reading the band and the now-visibility-aware rects. One `Slog.d` per flip, deliberately never `whenPenIdle`-gated (`isPenActive` counts hover — the bars must answer the double-tap that asked for them, not wait for the pen to leave). One copy for all four paper screens (notebook, sticky editor, scratch pad, calendar) |

Resources: `values/{colors,dimens,styles,themes}`, `values-sw720dp/dimens`,
`values-sw960dp/dimens` (the Manta's card-grid minimum only — see `docs/library.md` § The grid), 59
chrome `ic_*.xml` (grown one arc at a time since J1's move; the latest is arc 29 / LE2's
`ic_lasso_eraser` — copied byte-for-byte from og's `drawable/` rather than drawn fresh (the standing
"check first" trap), the eraser sub-bar's Lasso button and the eraser top-bar button's own glyph
while that eraser is armed; before it arc 28 / H4's
`ic_resize` (Tabler, the lasso bar's **Transform** button — a lone selected shape's one verb, D9);
before it arc 24 / Z5b's
`ic_backspace` — Tabler's own, the keypad's rub-out key — before it arc 24 / Z2's
`ic_calendar_event` (Tabler `calendar-event`, the calendar's own Events door), and before that arc
23 / Y4's `ic_calendar_star`, `ic_calendar_month`, `ic_calendar_week` and `ic_calendar_day` (Tabler
`calendar` with two ruled lines — a derivative, the `ic_notebook_plus` precedent), the calendar's
Today button and its three view latches — `ic_calendar` itself dates to Y1 and is the extension's
door on both host bars),
the button/border/radio drawables the moved styles reference, `Widget.Notesprout.Toggle` +
`toggle_pill` (arc 24 / Z5b — the yes/no pill; the style's 56dp width **is** the drawable's geometry,
since the knob insets are computed from it, so change both or neither), `Widget.Notesprout.DialogButton`
(arc 24 / Z3, the user's eye on the discard dialog — a 16dp `layout_marginStart` plus 16dp of side
padding, so AppCompat's own 8dp button-bar spacing no longer reads as one control; every two-button
dialog in the family inherits the air),
and a `strings.xml` holding `ok`
and `cancel` — the two strings the moved helpers reference themselves — plus, since arc 29 / LE2,
`eraser_point` / `eraser_lasso`, the `EraserBar`'s two long-press hints (here, not in `:app`,
because all four paper surfaces share the one bar). Every other string stays in `:app`.

**`ic_launcher_foreground.xml` and every `mipmap-*` stay in `:app`.** The launcher glyph is the
host's identity, not shared chrome; the Scratch Pad extension draws its own.

## Two helpers that were written fresh, not moved

- **`PaperToolbar`** is not `NotebookToolbar` relocated. The notebook's is hard-bound to
  `ActivityNotebookBinding` and carries the clipboard-loaded icon swap and the lasso re-tap, so it
  stays in `:app`. `PaperToolbar` takes the views themselves and does only what a spartan second
  surface needs. Both obey the same two rules: release the render first but **pen-gated**, and
  `sync` is the truth rather than our taps (g-paper arms and restores tools on its own).
- **`PaperChrome`** is not `NotebookActivity.pushExclusions` relocated. The notebook's also carries
  `paper.snapMarginPx` (arc 9) and reads its Contents and Recents flows by name; it stays exactly
  where it is, and adopting the helper in the notebook was explicitly not arc 11's business. What
  the two share is the *shape*, so the host-specific parts arrive as `extraRects` /
  `extraContains` / `blockAll` suppliers.

**Test count.** `:sn-screen`'s own JVM suite sat at **69** tests as of arc 28 — the pure geometry
(`PageMathTest`, `SelectionAnchorTest`, `SwipeMathTest`) and `PageGestures`'/`ListSwipe`'s pure
rules, unchanged by the arc. Arc 28's own additions here are small and structural rather than
tested in this module: `FloatingSelectionBar.buttonAt` and the `ic_resize` glyph above — the three
new object kinds themselves, their stores and their JVM suites, are core, in `:app` — see
[`docs/objects.md`](objects.md) and [`docs/notebook.md`](notebook.md). Arc 33 / F1 raised it to
**81** — `ChromeBandTest` (12), the whole of `ChromeBand`'s rule (a hidden bar's edge, a shown-but-
unlaid bar withholding, `rootHeight` 0, an empty or inverted range, both bars at once). `ChromeToggle`
and the `rectOf` visibility check are Android-typed and have no JVM suite of their own — the pure
rule under each is what is tested (`ChromeBand`'s own trap 2, and `AnchoredBar`'s already-covered
visibility rule that `rectOf`'s check now shares).

## When you change something here

A change to a shared helper reaches two screens in two processes. The notebook is the older
consumer and the one with a full test suite behind it — check it as well as the pad, and keep
anything notebook-specific in `:app` rather than growing a parameter here for it.

`ChromeBand` and `ChromeToggle` reach **four** consumers rather than two: `:app`'s
`NotebookActivity` and `StickyEditorActivity` build their own `ChromeToggle` directly, and
`:ext-ink`'s `InkScreenActivity` builds one in its own base class (`initChrome()`) that both the
scratch pad and the calendar inherit unchanged — a fifth copy was exactly the sibling-copy trap
this module exists to refuse, so the toggle's one flip order is checked against all four screens,
not the notebook alone, before it changes.
