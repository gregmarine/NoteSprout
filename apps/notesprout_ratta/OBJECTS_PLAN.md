# OBJECTS_PLAN.md — Arc 28 "Objects" (Notesprout SN, branch `ratta`)

**Standalone plan for the content objects** — item 3 of `PARITY_BACKLOG.md`: sticky notes, text
objects, and six hand-placed shapes. This file is the cross-session memory for the arc: read it
whole at every phase start, together with the root `CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`.
**Do not load `RATTA_PLAN.md` for this arc** unless a standing trap needs checking; its protocol and
traps are summarized at the end so this file is enough. `RESTORE_PLAN.md`, `ENCRYPTION_PLAN.md` and
`DRIVE_PLAN.md` are the shapes this file copies.

**Status: H4 ✅ landed 2026-09-06 — H5 ⬜ next.** H1 ✅ · H2 ✅ · H3 ✅ · H4 ✅ · H5 ⬜ · H6 ⬜ ·
H7 ⬜. When the arc closes, `docs/objects.md` is the reference.

**Phase letter:** **H** — the last free letter in `RATTA_PLAN.md`'s A–Z (L went to arc 27). After
this arc every letter is spoken for; the next arc picks a two-letter code.

---

## What this arc is

An SN page carries **strokes, headings and links** — the whole object catalog since arc 6. og
Notesprout (`apps/notesprout_android` — reading reference, **no code copied**) also has sticky
notes, on-page Markdown text, shapes and lines. The user chose three of the four at the 2026-09-05
gap review: **sticky notes, text, and some shapes**. Line objects are out; the dwell-triggered
shape *recognizer* is out ("that never worked well" — disabled in og too).

Arc 3 made headings **core** (an additive row type on the universal `notebook` table, a pure
mapper, a store on the shared `SoilWriter`, a g-paper `ContentRenderer`, undo actions, clipboard
and page-copy arms, export through `PagePreview`). **That is the shape every object in this arc
follows.** Nothing here is an extension, nothing crosses a seam except one compatible tail on the
PDF page bundle (D7), and there is **no NINTH extension point** and **no `API_VERSION` bump**.

Three things make this arc more than three copies of arc 3, and each is a decision below:

1. **Sticky notes have an editor with its own paper surface.** SN's precedents are the scratch pad
   (a tier-2 extension in another process) and the calendar's event note (arc 24 / Z3 — the first
   *second* paper surface in one process). The editor here is a **host Activity in `:app`**, the
   second second-surface in one process, sharing the notebook's open `.soil` connection.
2. **Shapes need resize and rotate, and g-paper's selection is move-only.** Decision 8 puts a
   **transform mode in the engine** (`~/git/g-paper`), pinned like every other g-paper bump. That
   is the arc's engine phase (H3) and its headline risk.
3. **Sticky content exports as PDF endnotes** (og's treatment), which means the host-renders /
   extension-assembles page bundle grows a compatible trailer for link annotations (D7).

Host-only otherwise: `notebook/`, `data/soil/`, `data/clip/`, `export/`, the notebook layout, and
`:ext-pdf`'s assembly for the endnote annotations. Version stays `0.1.0-ratta` (a phase-start
question, as always).

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| The universal row (`SoilObjectEntity`: `id parentId type order createdAt updatedAt deletedAt text refId x y width height color strokeWidth style flags blob`) + `SoilSchema.TYPE_*` | strokes, headings, links, document | three additive `TYPE_*` (D1–D3); **no `SOIL_VERSION` bump, no migration** — Paper ignores unknown types |
| `HeadingRows` / `LinkRows` pure mappers, `HeadingStore` / `LinkStore` on the one `SoilWriter` | the pattern | `TextRows`+`TextStore`, `ShapeRows`+`ShapeStore`, `StickyRows`+`StickyStore` |
| g-paper `ContentRenderer` (`draw`, `draw(excluded)`, `drawObject`, `hitTargets`, `layer`) | `HeadingRenderer`, `LinkRenderer` | `TextRenderer`, `ShapeRenderer`, `StickyRenderer` — all `BELOW_STROKES` |
| `Selection.contentIds` + `SelectionMode` + `SelectionToolbar` (Snap · Copy · Cut · H · Link · Edit · Unlink · Pad · Calendar · Tag · Delete) | five modes | modes grow (D5); Pad/Calendar hide when a new kind is selected |
| `NotebookUndo.Action` + the two exhaustive replay `when`s in `NotebookActivity` | 14 kinds | new kinds per D6; `Deleted` / `ScribbleErased` / `Moved` / `ObjectsPasted` gain id lists |
| `ObjectClip` (envelope, `plan`, id map, per-type order rebase over `NotebookSession.ORDERED_TYPES`), `PageClip`, `NotebookRemap` | strokes/headings/links | the three kinds + sticky children (D6) |
| `PagePreview.drawContent` — the one page-layering recipe (PDF bake + link-picker preview) | headings → links → strokes | text and shapes drawn after headings; sticky icons after links; **never** sticky content |
| `PageBundle` v1 (host renders pages, `:ext-pdf` assembles) + `ExporterInfo` compatible-tail precedent (`resultKind`) | pages only | v2 trailer: endnote pages + link annotations (D7) |
| The H1–H6 floating sub-toolbar recipe (`AnchoredBar`, `SelectionAnchor.placeSub`) | headings' level picker | the **Insert** sub-bar (D4) |
| `onPaperTapped` tap-to-place (clipboard) | paste centred on the tap | unchanged; insert lands at page centre (og) |
| `:markdown` — `MarkdownParser`, `MarkdownRenderer`, `MarkdownDraw`, `MarkdownFormatter`, `TextBuffer` | headings single-line; the document editor | the on-page text object renders through it multi-line |
| `HeadingEditDialog` (one `AppCompatEditText`, blank Save = delete, Ratta IME rules) | headings | `TextEditDialog` copies its shape, multi-line |
| The recognizer flow `HeadingConvert.run` (extension recognizer, readiness, `InkPayload`) | H conversion | the lasso bar's **Text** conversion reuses it verbatim, minus the prefix and the single-line collapse |
| The Z3 second-surface finding: g-paper's process-local `inkOwner` guard covers a second `PaperView` in one process; the child surface releases before every `finish()` | `EventEditorActivity.NoteSurface` | the sticky editor's surface (D2) |
| `FakeSoilDao`, `HeadingRowsTest`, `ObjectClipTest`, `PageClipTest`, `NotebookUndoTest`, `FamilyConstantsTest` | the test pattern | every new kind gets the same files |

## Decisions (wizard 2026-09-06 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Sticky editor's home | **A core Activity in `:app`** — `StickyEditorActivity`, its own g-paper surface, same process as the notebook. Headings-are-core precedent + Z3's second-surface finding. No ninth point, no seam crossing, **one `SoilWriter`** (D2). |
| 2 | Sticky create / open flow | **og's flow.** The Insert sub-bar's Sticky inserts a **72 dp** square icon at page centre (clamped), records the undo, and **opens the editor immediately**; on close from an initial create the icon lands **selected under the lasso** so it can be dragged into place. Reopen later with a **finger tap** on the icon (stylus taps stay ink — the link-follow gate). |
| 3 | Sticky editor screen | **Full-screen, notebook's tools, plus paste.** Full-bleed paper at the note's content size, a top bar with Back/✓, **pen · eraser · lasso only** (fixed 3 px / 15 px, black, like the notebook), 2/3-finger undo/redo, lasso bar = Snap · Copy · Cut · Delete. The editor **pastes from the global clipboard** (tap-to-place) and **copies to it** — ink moves between a note and a page. No shapes, text or stickies inside a note (D2 says what pastes). |
| 4 | Sticky export | **PDF endnotes, og's treatment.** The page shows the icon; each note gets an endnote page after the last page with the caption `Note N — from page P`, linked **both ways** with PDF link annotations. Host renders the endnote bitmaps and the link table; `:ext-pdf` adds pages + annotations (D7). Text export **excludes** sticky content; `.soil` export carries the rows verbatim. |
| 5 | Text object creation | **Both paths.** (a) The lasso bar gains **Text** beside **H**: recognize the ink through the extension recognizer and replace the strokes with one text row — **recognition failure creates nothing** (the heading rule; og's unrecognized-ink fallback state is deliberately absent). (b) The Insert sub-bar's **Text** inserts an empty text object at page centre and opens the edit dialog at once; Cancel or blank Save on that first dialog **removes it** (nothing blank ever exists); Save lands it selected. |
| 6 | Text edit dialog | **A plain markdown box, no format bar.** One multi-line `AppCompatEditText` holding raw Markdown source, Save / Cancel, blank Save = delete (`HeadingEditDialog`'s shape). The object renders on-page through `:markdown` (`MarkdownRenderer` → `MarkdownDraw`) at **24 sp**, black, multi-line, wrapping at a width capped by the page (D1). A stylus tap on a lone selected text object opens it (the heading gesture). |
| 7 | Shape types | **Six:** `RECTANGLE`, `ELLIPSE`, `TRIANGLE`, `ARROW`, `LINE`, `STAR`. Square and circle are rectangle / ellipse with the aspect lock — **not** types. og's diamond, trapezoid, pentagon, hexagon and arch are not built (backlog, a fresh decision each). |
| 8 | Shape sizing / editing | **Transform mode in g-paper.** An engine-owned overlay — oriented dashed box, **8 resize handles** (corners + edge midpoints), a **rotate knob** above top-centre, aspect lock, rotation snap within 5° of 0/90/180/270, minimum size 24 dp — entered by the host from the lasso bar's **Transform** on a lone shape, reported back with before/after geometry. A new g-paper version, pinned per the protocol (SN pinned 0.1.23; g-paper was at 0.1.24 when this was written — **H3 landed on 0.1.27**, Paintsprout having taken 0.1.25/0.1.26 for raster pages the same day). Live handles are drawn by the engine, so the EPD sees them. |
| 9 | Parity depth | **Full in-notebook parity, no extension transfers.** All three kinds: lasso select / move / delete, eraser + scribble-erase **whole-object**, undo/redo, Copy / Cut / Paste within and across notebooks (a sticky's children travel with it, fresh ids), cross-notebook page copy, Contents untouched. **Send to Scratch Pad / Calendar stay ink-only** — both buttons hide when the selection holds a sticky, text or shape (the way the Pad button hides for non-ink today). `:ext-ink`'s `InkWire` is not widened. |
| 10 | Toolbar | **One `Insert` button opening a sub-bar** (the H1–H6 recipe): Sticky · Text · Rectangle · Ellipse · Triangle · Line · Arrow · Star. One slot on the top bar, after the lasso, **measured against the Nomad first**. |
| 11 | Phases / review | **Seven, H1–H7, this standalone `OBJECTS_PLAN.md`.** `/code-review high` on the arc range in **H6** (with the PDF endnotes) and its fixes; **H7 is docs-and-freeze with no code review.** |
| 12 | App version | Stays `0.1.0-ratta` (a phase-start question every phase, as always). |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **Nothing blank ever exists.** A text row always has non-blank `text`; a sticky always has its
  icon box; a shape always has its type. The heading rule, extended: a failed recognition, a
  cancelled first dialog, a blank Save — each leaves no row (or soft-deletes the one it made).
- **A sticky's children are in the note's LOCAL space** (og) — `(0,0)` is the content's top-left.
  A sticky drag rewrites **one row**, unlike a link whose page-absolute children all move. Only
  `stroke` rows may be children of a sticky (decision 3); the editor pastes only the stroke rows of
  whatever the clipboard holds, and says so when it left something out.
- **Sticky content never draws on the page** — not in the notebook, not in covers, not in
  `PagePreview`, not in the link-picker preview. The icon is the page's whole knowledge of it.
- **The eraser takes a whole object** (heading/link precedent): a swept text, shape or sticky icon
  goes as one, never a part. The eraser never reaches inside a sticky from the page.
- **Hit-testing a rotated shape uses its AABB**, not the rotated outline (og's accepted caveat).
- **Both Sends hide, never disable** (J4: GONE, never disabled). A selection holding any new kind
  offers neither Pad nor Calendar; Copy/Cut/Snap/Delete stay offered in every mode.
- **Contents is untouched.** Text objects are not headings and do not appear in the outline; the
  document seed (arc 19) reads ink, and this arc does not feed text objects into it (backlog note).
- **`DocumentDao`'s staleness whitelists gain the three kinds** — a page with a new text object,
  shape or sticky is "edited" for the notebook document's staleness, the same as new ink.
- **Rows must fit the 18 universal columns** — SN has none of og's `shapeType` / `centerX` /
  `rotationDeg` / `contentW` columns and **adds no column** (the format lock). Packing rules are D1–D3;
  og-byte-compatibility for these rows is **not** a goal (headings/links were compatible with
  *Paper*, which has none of these either).

---

## Design (binding unless a phase-start question reopens it)

### D1 — The text row (`SoilSchema.TYPE_TEXT = "text"`, `notebook/TextRows`, H1)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `text` | raw Markdown source — **always non-blank** |
| `x` / `y` / `width` / `height` | the box in page px; `width` = measured natural width capped at `availableWidth = pageWidth − x` (og's rule — **never** page width unconditionally); `height` = the laid-out height |
| `"order"` | z-order among the page's **text** rows (own counter, `maxOrder(parent, type)`) |
| `flags` / `style` / `blob` / `refId` / `color` / `strokeWidth` | null |

`TextRows.toText` returns `null` for a blank/missing `text` (dropped, never crashes).
`TextRenderer.measure(text, availableWidthPx, density, scaledDensity)` is the **one** sizing
function — creation, edit, and `remeasureForDevice` on every page load (the heading N3 finding:
position is authored, size is derived). Renders via `MarkdownRenderer.render(blocks, widthPx,
paint, density, gap)` on a 24 sp black `TextPaint` (`TextTypography.BASE_SP`), `MarkdownDraw`
multi-line, **no `maxLines`**, transparent background. Live-drag pair implemented (`drawObject`).

### D2 — The sticky row + its editor (`TYPE_STICKY = "sticky_note"`, `notebook/StickyRows`, H5)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `x` / `y` / `width` / `height` | the **icon box** in page px (72 dp × density at creation, square) |
| `flags` | `StickyFlags.pack(contentW, contentH)` — bits 0–19 content width px, bits 20–39 content height px (both ≤ 1,048,575) |
| `"order"` | z-order among the page's sticky rows |
| children | `stroke` rows with `parentId = <sticky id>`, geometry in **local content px** |

`contentW × contentH` is fixed at creation to the creating device's editor paper area (the screen
minus the measured top bar). The editor calls `setPageSize(contentW, contentH)` on every open,
so a note authored on a Nomad opens registered on a Manta (the notebook's own foreign-page rule).

**`StickyEditorActivity`** (host, `exported="false"`, launched by `NotebookActivity` with an
`ActivityResultLauncher`): full-bleed `PaperView` + one top bar (`[←] [✓] [pen] [eraser] [lasso]`,
Back = ✓ = save-and-close; there is no cancel — every stroke is already durable), 2/3-finger
undo/redo on its own `UndoRedoStack`, lasso bar Snap · Copy · Cut · Delete, clipboard paste via
`onPaperTapped`. **No `.soil` open of its own**: the notebook's `NotebookSession` stays alive behind
it and the editor writes through a host-side `StickyEditorTransfer` singleton (og's
`persistToHost`) — `input` (sticky id + child strokes), `sink` (a `StickyStore` handle bound to the
notebook's `SoilWriter`), `output` (the final child set for the host's one undo action). **Nothing
in an Intent extra but the notebook/page/sticky ids.** Writes are debounced ~600 ms and flushed in
`onStop`; the host's `Action.StickyContentEdited(before, after)` is recorded **once per showing**
from the result callback (og's shape — the editor's own stack is live inside the showing only).

**EPD handoff chain** (Z3's rule, Fable writes it): `NotebookActivity.releaseForHandoff()`
immediately before launch; the editor's surface `releaseForHandoff()`s before **every** `finish()`;
the notebook reclaims at the **top** of the result callback (result callbacks run before
`onResume`). Then the host re-reads the sticky's children (og's re-read rule) — needed for nothing
on the page but for the clipboard capture and the undo snapshot.

`StickyRenderer` draws the Tabler `sticker-2` glyph scaled into the icon box (`ic_sticker_2`,
stroke 1.5 — check og's `drawable/` before drawing anything). Layer `BELOW_STROKES`, registered
**after** links (z: headings · text · shapes · links · stickies · strokes — D8).

**Finger tap** → `PageGestures.onFingerTap` hit-tests stickies **before** links (topmost first;
a sticky over a link opens the note). Stylus taps are ink.

### D3 — The shape row (`TYPE_SHAPE = "shape"`, `notebook/ShapeRows`, H1 rows / H4 behaviour)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `style` | the type name: `RECTANGLE` `ELLIPSE` `TRIANGLE` `ARROW` `LINE` `STAR` (unknown → row dropped, never crashes) |
| `x` / `y` | the **centre**, page px (the one row kind whose `x/y` is not a top-left) |
| `width` / `height` | **un-rotated local extents**, page px |
| `strokeWidth` | the outline width in **px** (SN strokes are px; og's dp is not copied — one unit in the file) — fixed at creation to `NotebookToolbar.PEN_WIDTH_PX` (3 px) |
| `flags` | `ShapeFlags.pack(aspectLocked, pointCount, rotationTenths)` — bit 0 aspect lock · bits 8–15 point count (STAR only, default 5, 5–12) · bits 16–31 rotation in **tenths of a degree**, 0–3599 clockwise |
| `"order"` | z-order among the page's shape rows |

`ShapeGeometry.pathFor(shape): Path` — pure, absolute page coordinates, the rotation matrix about
the centre applied last: rectangle 4 `lineTo`; ellipse `addOval`; triangle apex at top-centre;
star skip-pattern `(i*2) % n`, inner ratio 0.5; line `(L,cy)→(R,cy)`; arrow = line + two arms at
±150°. **Stroke-only, no fill**, on screen and in PDF alike. `ShapeRender.aabb(shape)` = the rotated
path's bounds inflated by `max(strokeWidth/2, 4 dp)` — what `hitTargets()` reports and what the
lasso box shows. Defaults at insert (og): closed shapes 72 dp square, aspect locked for
rectangle / ellipse / star; line and arrow `50 % page width × 1 px`, unlocked; everything at page
centre, landing **selected** with the lasso bar up.

### D4 — The Insert sub-bar (H1 scaffold; buttons wired per phase)

`btnInsert` on the notebook top bar directly after `btnLasso` (icon: Tabler `plus` or `square-plus`
— check `drawable/` first). Tap → `InsertBar`, a floating `AnchoredBar` under the button (the H1–H6
sub-toolbar geometry via `SelectionAnchor.placeSub`) holding, left to right: **Sticky · Text ·
Rectangle · Ellipse · Triangle · Line · Arrow · Star**. Dismisses on a pick, any other bar button,
or an outside touch; the paper's `setExclusionRects` unions its bounds while it is up (og's
exclusion lesson). Every button carries a long-press hint naming it. **Measured on the Nomad
first** (P2P-narrowness rule); if eight do not fit at `toolbar_button_size`, the sub-bar wraps to
two rows rather than shrinking a target. Insert is a **command**, not a tool: the armed tool is
unchanged by an insert; the inserted object lands selected and the engine's lasso lifecycle takes
over (the host calls `paper.setSelection` by hand, N2's host-initiated selection).

### D5 — Selection modes and the lasso bar (H2 text · H4 shapes · H5 stickies)

`SelectionMode` grows: `TEXT` (a lone text), `SHAPE` (a lone shape), `STICKY` (a lone sticky), and
`MIXED` absorbs any combination that is not one of the lone kinds (`MIXED_WITH_LINK` unchanged).
Per-mode offers:

| Mode | Snap Copy Cut Delete | H | Text (convert) | Link | Edit | Transform | Tag | Pad · Calendar |
|---|---|---|---|---|---|---|---|---|
| `STROKES` | ✓ | ✓ | **✓ new** | ✓ | | | ✓ | ✓ |
| `HEADING` | ✓ | ✓ | | ✓ | (tap) | | ✓ | |
| `TEXT` | ✓ | | | ✓ | (stylus tap opens the dialog) | | | |
| `SHAPE` | ✓ | | | ✓ | | **✓** | | |
| `STICKY` | ✓ | | | ✓ | (finger tap opens the editor — no bar button) | | | |
| `LINK` / `MIXED_WITH_LINK` | as today | | | | | | | |
| `MIXED` | ✓ | | | ✓ (link-free) | | | | **hidden** if any new kind is inside |

**Link** stays offered on any link-free selection (a link may wrap the new kinds — `LinkStore`
re-parents whatever ids it is handed; `PageLink` children lists grow `texts`/`shapes`/`stickies`
so a wrapped set moves, captures and restores whole). `TagSelection.offered` is unchanged (ink
alone or a lone heading).

### D6 — Undo, clipboard, page copy, erase (per phase; the pure parts land in H1)

- `NotebookUndo.Action` gains `TextCreated(text, strokeIds)` (strokeIds empty for an insert),
  `TextEdited(before, after)`, `ShapeInserted(shape)`, `ShapeTransformed(before, after)`,
  `StickyInserted(sticky)`, `StickyContentEdited(stickyId, before: List<Stroke>, after)`; and
  `Deleted`, `ScribbleErased`, `Moved`, `ObjectsPasted` gain `textIds` / `shapeIds` / `stickies`
  (a sticky snapshot carries its children, as a link's does). Both replay `when`s stay exhaustive.
  Rule unchanged: mutate store → `drain()` → `refreshToPage(pageId)`. New rows revive **in place**.
- `ObjectClip.plan`: `when (out.type)` gains the three; `NotebookSession.ORDERED_TYPES` =
  `[stroke, heading, link, text, shape, sticky_note]`; `captureObjects` reads a sticky's children
  (as it reads a link's); a sticky's children keep local coordinates through a paste (only the
  parent shifts); `payloadBounds` uses `ShapeRender.aabb` for shapes; the 6 MiB cap stands.
- `PageClip` is kind-agnostic already (one link arm) — **verify by test**, add none unless a test
  shows a gap. `NotebookRemap` rewrites link payloads only — untouched.
- `SoilDao` kind lists by hand: `liveContentIds` (`stroke, heading` → + the three),
  `liveDescendantIds` (page level + **a sticky's children** as a second grandchild branch),
  `DocumentDao`'s three whitelists. `SoilCompactor`'s cascade is type-agnostic — verify by test.
- `removeContent(contentIds)` splits four ways; erase and scribble-erase repaint ownership
  unchanged (eraser tool `notifyContentChanged`s, scribble must not).
- `FamilyConstantsTest` pins the three new literals.

### D7 — PDF endnotes (H6; host `export/` + `:ext-pdf` assembly + `PageBundle` v2)

- **`PageBundle.VERSION` 1 → 2, backward-readable.** A v2 reader accepts v1 streams (no trailer).
  v2 = the same header and pages, then a trailer: `int linkCount`, then per link
  `int fromPage · float l t r b (px, from-page space) · int toPage`. `pageCount` **includes** the
  endnote pages. The host writes **v1 whenever there are no links to write**, so an older
  `:ext-pdf` still opens a sticky-free bundle.
- `ExporterInfo` gains a compatible tail `bundleVersion: Int = 1` (the `resultKind` precedent —
  **no `API_VERSION` bump**). Facing a v1-only exporter with stickies present, the host exports
  icons only and the Export screen says so in one line.
- Host side (`ExportRender`): after the pages, for each live sticky in page order — render its
  children onto a white `contentW × contentH` bitmap (no template), append a **60 px caption strip**
  `Note N — from page P` (32 px sans, black), encode like a page; record two links (icon rect →
  endnote page; caption strip → source page). Content taller than one page is **not** split (og's
  deferred item, kept deferred).
- `:ext-pdf` `PdfAssembly`: pages as today, then each link → `PDAnnotationLink` + `PDActionGoTo` +
  `PDPageFitDestination`, border width 0, `lly = pageH − b`, `ury = pageH − t`. **Annotate before
  encrypt** (the password-protect option's order). With no stickies the PDF is byte-identical to
  today's — pinned by a test on the assembly's inputs.

### D8 — Draw order and the render frame (H1 fixes it; every renderer obeys)

Registration order = z, all `BELOW_STROKES`: **headings · text · shapes · links · stickies**, then
the engine's strokes. `PagePreview.drawContent` mirrors it exactly (loose headings, texts, shapes;
per link its wrapped headings/texts/shapes/strokes; sticky icons; loose strokes). Every renderer
re-records only on `notifyContentChanged` / the engine's data-in calls; one Main block = one EPD
frame; renderer content is set **before** `loadStrokes` on a page load (the K1 ordering).

### D9 — g-paper transform mode (H3, Fable, `~/git/g-paper` **0.1.27**)

Engine-owned, host-agnostic — it knows nothing about shapes:

- `PaperView.beginTransform(contentId, box: OrientedBox, aspectLocked: Boolean, minSizePx)` /
  `endTransform()` / `setTransformAspectLocked(locked)` / `transformingContentId` / `transformBox`;
  `PaperListener.onTransformChanged(contentId, box)` **live** (throttled to the lasso cadence
  during a drag, once more at the lift — the host updates its working copy only; the engine
  repaints through `drawObject`) and `onTransformEnded(contentId, before, after)` **exactly once
  per mode on every exit, the host's `endTransform` included** — the one persistence + undo +
  chrome-teardown point; `OrientedBox(cx, cy, w, h, rotationDeg)` (clockwise, `[0, 360)`).
  `beginTransform` requires `Tool.LASSO` (a no-op otherwise — `armLassoForLanding()` first) and
  dismisses the selection **without** `onSelectionDismissed`; nothing is selected after an exit
  (the host `setSelection`s the shape back under the lasso).
- Overlay: dashed 1 dp box drawn **oriented**, 8 handles (10 dp, 22 dp touch), rotate knob 36 dp
  above top-centre, `round(density)` px hairlines on integer edges (the hairline trap). Grab
  classification at down: BODY / ROTATE / handle / NONE (outside = end). Resize anchors the
  opposite handle, clamps to `minSizePx`, honours the lock; rotate snaps within 5° of the four
  cardinals. The overlay lives on the selection layer; the live shape is repainted by the host's
  renderer on each `onTransformChanged` (`drawObject` at the new box — the live-drag pair).
- Exits: `endTransform` from the host (Done on the bar), a contact outside the grab region (it
  then proceeds as an ordinary lasso contact and never reports `onPaperTapped`), a tool change,
  any data-in call (`loadPageRaster` and `setSelection` included — 0.1.25's raster calls are
  data-in too), an erase contact. `isPenActive` semantics unchanged; `releaseRender` gated as today.
- **Ratta needs no engine change**: the mode rides the shared lasso entries
  (`selectionBoxContains` answers for the grab region, so the law-3 hover suppress covers a handle
  drag; a transform contact counts as a selection drag for the firmware suppress).
- Pinned in `sn-screen/build.gradle.kts`; **the engine commit lands with the host commit** (a pin at
  an uncommitted engine is a tree a fresh clone cannot resolve). Demo app in g-paper gets a
  transform button so the overlay is checked on the Nomad before SN consumes it.

---

## Phases

### ✅ H1 — The substrate (Fable seams + Opus; Sonnet scaffold)

Rows, mappers, stores, kind lists, the Insert sub-bar shell, draw order. **No new object is
creatable by the user at the end of H1**; every enumeration site already knows the three kinds.

- `SoilSchema.TYPE_TEXT/SHAPE/STICKY` + column contracts (D1–D3) · `TextRows` / `ShapeRows` /
  `StickyRows` + `StickyFlags` / `ShapeFlags` pure packers · `TextStore` / `ShapeStore` /
  `StickyStore` on the shared `SoilWriter` (`FakeSoilDao` grows as needed).
- `ShapeGeometry.pathFor` + `ShapeRender.aabb` (pure, tested on every type at 0° and 37°).
- `PageContent` gains `texts` / `shapes` / `stickies`; `PageReads.content`; `PagePreview.drawContent`
  in D8 order (drawing through the three renderers' static draw functions).
- `NotebookUndo.Action` new kinds + widened fields (D6), both replay `when`s exhaustive and
  implemented against the new stores (nothing stubbed — the stores exist from H1); the three
  renderers registered in D8 order with empty working copies.
- `ObjectClip` / `ORDERED_TYPES` / `captureObjects` / `SoilDao` + `DocumentDao` kind lists /
  `FamilyConstantsTest`.
- `btnInsert` + `InsertBar` scaffolded with all eight buttons in the layout, every one hinted, and
  **every one `GONE` until its phase lands** (J4: a control that does nothing does not exist — no
  "coming soon" toasts). At the end of H1 the Insert button opens an empty bar in debug only, so
  the geometry can be measured; in release it is `GONE` too.
- Nomad: the bar measured (D4), a page with hand-inserted rows (via a debug-menu "Insert sample
  objects" entry, debug build only) renders in order and survives close/reopen; both Sends hide.

**Questions to resolve at phase start:** app version · whether the debug-menu sample-insert entry
stays after H1 (default: removed in H7).

### ✅ H2 — Text objects end to end (Opus code on a Fable brief · Fable review · walk by hand)

- `TextRenderer` (D1) + `remeasureForDevice` · `TextEditDialog` (D6 of the wizard) · Insert →
  Text (insert at centre → dialog → Save lands selected / Cancel removes) · lasso bar **Text**
  conversion via `HeadingConvert`'s machinery (a shared `InkConvert` if the split is clean, else a
  sibling) · `SelectionMode.TEXT` + stylus tap opens the dialog · move / delete / erase /
  scribble-erase / undo / redo · Copy / Cut / Paste within and across notebooks · link-wrap of a
  text · PDF via `PagePreview`.
- Tests: `TextRowsTest`, `TextRendererMeasureTest` (pure parts only — no `StaticLayout` under
  `returnDefaultValues`), `ObjectClipTest` text arms, `NotebookUndoTest`, `SelectionModeTest`.
- Nomad walk by hand + user checklist (dialog IME on Ratta, wrap width, drag under the pen).

**Questions to resolve at phase start:** app version · dialog title wording ("Text") · whether
Cancel on a *re*-edit of an existing object is offered as a button or Back only.

### ✅ H3 — g-paper transform mode (Fable; g-paper 0.1.27)

D9 as written, in `~/git/g-paper`: `OrientedBox`, `beginTransform` / `endTransform`, the two
listener callbacks, the overlay, the demo button. JVM tests for the pure geometry (grab
classification, anchored resize, lock, snap, min-size clamp). `publishToMavenLocal`, re-pin
`sn-screen/build.gradle.kts` (both artifacts), SN builds green with **no behaviour change** (the
host calls nothing new yet). Nomad: the g-paper demo's transform overlay walked by hand (handles
visible on the EPD, rotate knob, snap, tap-outside exit).

**Questions to resolve at phase start:** app version · g-paper version number (default 0.1.25 — became 0.1.27) ·
whether the rotate knob is offered for `LINE`/`ARROW` only or every type (default: every type).

### ✅ H4 — Shapes on the page (Opus code on a Fable brief · Fable review · walk by hand)

- `ShapeRenderer` (D3, `drawObject` for the live drag and the live transform) · Insert → the six
  shape buttons with og's default sizes, landing selected · `SelectionMode.SHAPE` + the bar's
  **Transform** → `paper.beginTransform` with the row's box → `ShapeStore.transform` on
  `onTransformEnded` + `Action.ShapeTransformed`; **Done** on the bar + the aspect-lock toggle
  (labels: ellipse "Circle"/"Oval", rectangle "Square"/"Rect", others "1:1"/"Free" — og's doc,
  not og's code) · move / delete / erase / scribble-erase / undo / redo · Copy / Cut / Paste
  within and across notebooks · link-wrap · PDF via `PagePreview` (`ShapeGeometry`, stroke-only).
- Tests: `ShapeRowsTest`, `ShapeFlagsTest` (pack/unpack round-trips incl. 359.9°), `ShapeGeometryTest`,
  `ObjectClipTest` shape arms, `NotebookUndoTest`.
- Nomad walk by hand + user checklist (handles under the pen, a star at 37°, line rotated to
  vertical, hairlines).

**Questions to resolve at phase start:** app version · star point count fixed at 5 or a bar
control (default: fixed at 5 — a control is a backlog item).

### ⬜ H5 — Sticky notes (Fable: surface + handoff chain + transfer · Opus: the rest · Fable review · walk by hand)

- `StickyRenderer` + `ic_sticker_2` · Insert → Sticky (D2 flow) · `StickyEditorActivity` +
  `StickyEditorTransfer` + the debounced writer through the notebook's `SoilWriter` · the handoff
  chain · finger tap reopen (before links in the hit order) · `SelectionMode.STICKY` · move /
  delete / erase / scribble-erase / undo (`StickyContentEdited` once per showing) / redo · Copy /
  Cut / Paste within and across notebooks **with children** · the editor's own Copy/Cut/Paste
  (strokes only; a left-out toast) · link-wrap of a sticky · `liveDescendantIds`' second grandchild
  branch (page delete + undo carry the children) · PDF shows the icon (endnotes are H6).
- Process death inside the editor: the notebook is recreated behind `IndexGuard`; the editor's
  transfer singleton is gone → the editor finishes to the notebook with nothing lost beyond the
  debounce window. Walked by `am kill`.
- Tests: `StickyRowsTest`, `StickyFlagsTest`, `StickyStoreTest` (children local, parent-only move),
  `ObjectClipTest` sticky arms (children travel, ids fresh, local coords kept), `NotebookUndoTest`,
  `liveDescendantIds` on `FakeSoilDao`.
- Nomad walk by hand + user checklist (EPD handoff both ways, ink inside the note, reopen, drag the
  icon, paste page ink into a note and back).

**Questions to resolve at phase start:** app version · editor top-bar wording for ✓ ("Done") ·
whether the icon shows a "has content" mark (default: no — og's deferred item stays deferred).

### ⬜ H6 — PDF endnotes + `/code-review` on the arc range + fixes (Fable review · Opus fixes · Sonnet tests)

- D7 as written: `PageBundle` v2 + reader compatibility tests (v1 stream through a v2 reader; a v2
  trailer round-trip; a v1 write when `linkCount == 0`) · `ExporterInfo.bundleVersion` tail ·
  `ExportRender` endnote pages + link table · `PdfAssembly` annotations, annotate-before-encrypt ·
  the Export screen's one-line notice for a v1-only exporter · byte-identical-without-stickies test.
- Then **`/code-review high` on the arc range** (H1 → H6's code), fixes applied by severity, every
  JVM suite green in every module, a Nomad re-walk of whatever the fixes touched.

**Questions to resolve at phase start:** app version · review level (default high) · whether the
endnote caption also names the notebook (default: no — og's wording verbatim).

### ⬜ H7 — Docs, ledger, freeze (Sonnet docs in parallel · Fable read-back · **no code review, no code**)

`docs/objects.md` (the reference: the three rows, the Insert bar, the transform mode, the sticky
editor and its handoff/transfer, the endnotes, the failure table) · `docs/notebook.md` (Insert bar,
selection modes, undo kinds, draw order) · `docs/clipboard.md` (the three kinds + sticky children)
· `docs/export.md` (`PageBundle` v2, `bundleVersion`, endnotes) · `docs/extensions.md` (the tail,
the boundary audit row) · `docs/links.md` (wrapping the new kinds) · both `CLAUDE.md`s + the root
pointer · `PARITY_BACKLOG.md` item 3 DONE · `RATTA_PLAN.md` header sentence · this file's status +
ledger · memory. **Doc-agent briefs must say: never run any git command; never revert files you
did not create** (the Z6 trap). Remove the H1 debug sample-insert entry unless H1's answer kept it.

**Questions to resolve at phase start:** app version only.

---

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- Text objects render at **24 sp regular**, headings inside a text object's Markdown scale as the
  document does (`HeadingTypography.scaleFor`), lists / blockquotes / rules as `MarkdownRenderer`
  already draws them; nothing new in `:markdown` unless a renderer gap is found (then it is fixed
  **in `:markdown`**, one engine).
- A text object's `x/y` is its **top-left** and stays fixed through an edit (og); the box grows down
  and right; the paste clamp keeps it on the page.
- The Insert sub-bar **does not remember** anything; nothing is armed after an insert.
- Shape outline width is fixed at 3 px (the pen's) — no width control this arc.
- The sticky editor uses the notebook's white paper with **no template**; the note's own content
  size is the only page.
- The sticky's finger-tap gate copies `LinkFollowFlow`'s thresholds exactly (single pointer, below
  long-press, no move).
- Cover snapshots pick up text, shapes and sticky icons for free (`renderToBitmap` walks the
  committed layer) — verified by eye in H2/H4/H5, no cover code.
- Walks are **by hand on the Nomad** (Haiku wander trap); adb cannot lasso, drag, rotate or ink.

## Standing traps that bind this arc

- **Two exhaustive `when`s over `Action`** (undo 1425–1483, redo 1488–1533 in `NotebookActivity`
  at the time of writing) — a new kind that misses one is a compile error, a widened field that
  misses one is a silent no-op. Test every new action both ways.
- **Raw kind-string SQL** in `SoilDao` (`liveContentIds`, `liveDescendantIds`) and `DocumentDao`
  (three whitelists) — edited by hand; `FakeSoilDao` must mirror each.
- **`"order"` is per parent AND type**; pasted sets rebase after max, relative order preserved.
- **Renderer content before `loadStrokes`** on a page load; **never repaint from
  `onScribbleErased`**; one Main block = one EPD frame.
- **ActivityResult callbacks run BEFORE `onResume`** — the sticky editor's reclaim latches at the
  **top** of the result callback.
- **`releaseRender()` gated on `!isPenActive`** in every bar handler.
- **A 1 dp hairline at 1.875 density is a coin flip** — `round(density)` px on integer edges, for
  the transform overlay and the text box alike.
- **`Stroke.bounds` is point-tight** — a sticky's content bitmap and the union used for clipboard
  bounds grow by width/2.
- **`StaticLayout` cannot be JVM-tested** under `returnDefaultValues` — measure logic that needs it
  stays thin and is walked, not unit-tested; the pure arithmetic around it is.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Drain the shared `SoilWriter` before any capture / gather / raster** — including the endnote
  render and the sticky editor's read on open.
- **A `<shape>` stroke has no padding — an opaque custom dialog root hides every border**
  (`Dialogs.style` for `TextEditDialog`).
- **Raise the IME from `onWindowFocusChanged` behind a once-per-showing latch, explicit flag 0** —
  the text dialog on Ratta; hardware keys type only while the IME is shown.
- **GONE, never disabled**; Toast confirms, dialog explains.
- **The engine commit lands with the host commit** (H3's pin).
- **Check og's `drawable/` before drawing a "fresh" icon** — `ic_sticker_2`, `ic_convert_shape`,
  `ic_shape_*`, `ic_text_recognition` all exist there.
- **Walk-agent false failures** — re-drive any FAIL by hand; every walk in this arc is by hand.

## Working protocol (summary — the full text is `RATTA_PLAN.md` § Working protocol)

One phase per session; read this file whole at phase start, flip the phase to 🔄, ask its
phase-start questions **one at a time**, then code. Fable plans / seams / reviews / the complex
code (H3's engine work, H5's surface + handoff + transfer); Opus features; Sonnet scaffold, layouts,
resources, docs; ≤ 5 background agents. JVM tests for every pure piece; the user gets a **short
numbered checklist** only for what needs a hand or an eye. **Nomad only** (SNN `SN078D10012852`);
the Manta only on explicit ask. Commit + push only when every suite is green (or the user's
all-clear), after docs / memory / CLAUDE.md are in; then the user runs `/clear`. A long explanation
and an `AskUserQuestion` never share one turn — explain, wait, then ask.

## Ledger

*(one Outcome entry per phase as it closes)*

### H1 — Outcome (2026-09-06)

- **Phase-start answers:** version stays `0.1.0-ratta`; the debug "Insert sample objects" entry
  stays through H2–H6 and is removed in H7 (the default).
- **Landed:** `SoilSchema.TYPE_TEXT/SHAPE/STICKY` (og's literals `text` / `shape` / `sticky_note`,
  pinned in `FamilyConstantsTest`) · `TextRows`+`PageText`, `ShapeRows`+`PageShape`+`ShapeType`+
  `ShapeFlags`, `StickyRows`+`PageSticky`+`StickyFlags` · `ShapeGeometry` (pure `outline` /
  `tightBounds` / `aabb` + the thin `pathFor`; the plan's `ShapeRender.aabb` lives here as
  `ShapeGeometry.aabb(shape, density)` — one object, not two) · `TextStore` / `ShapeStore` /
  `StickyStore` on the one `SoilWriter` (`StickyStore.remove` reads and soft-deletes the note's
  children itself; `restore` revives the snapshot's `childIds`, so every delete snapshot of a sticky
  — loose or wrapped in a link — is taken with `withContent` **before** the row goes; the activity's
  `recordWithStickies` defers exactly those deletes into one page op, still one gesture = one
  entry) · `TextRenderer` / `ShapeRenderer` / `StickyRenderer` registered **headings · texts ·
  shapes · links · stickies** with `PagePreview.drawContent` (now taking a `PagePreview.Paints`)
  and `LinkComposite.build` mirroring D8 · `PageObjects` (the three renderers + working copies, a
  view-model beside the activity) · `PageContent` / `PageLink` / `LinkRows` / `LinkStore` /
  `PageReads` grown for the three kinds (a wrapped sticky's content stays under the sticky) ·
  `ObjectClip` three arms + sticky children at zero translation, three levels deep through a link;
  `ORDERED_TYPES` = 6; `captureObjects` gathers note content · `SoilDao.liveContentIds` /
  `liveDescendantIds` (sticky content reached on the page and inside a link), `setTextContent`,
  `setShapeGeometry`, `stickiesOf`; `DocumentDao`'s three whitelists (a note's content strokes
  deliberately **not** counted — an edit inside a note is not "the page has changed") ·
  `NotebookUndo` six new kinds + the four widened ones, both replay `when`s implemented ·
  `SelectionMode`: a selection holding any new kind is `MIXED` in H1 (H/Pad/Calendar/Tag hidden,
  link-free Link offered) — D5's `TEXT`/`SHAPE`/`STICKY` modes land with their phases ·
  `btnInsert` (`ic_plus`, after the lasso) + `InsertBar` with all eight hinted buttons behind
  `InsertBar.offer(kind, true)`; **debug builds offer all eight so the bar could be measured**
  (a tap only closes the bar), release keeps the button GONE · debug `SampleObjects` +
  the "Insert sample objects (debug)" entry (refuses an open, locked or missing notebook).
- **Nomad (by adb, driven from the debug menu):** sample rows written to `20260905_142626`;
  text (heading + wrapped paragraph), all six shapes (star at 37°), and the sticky icon render in
  D8 order under the existing link; the page survives close → reopen; **the eight-button Insert
  bar fits in ONE row on the Nomad** (~940 of 1404 px at `toolbar_button_size`) — D4's two-row
  wrap is not needed there. "Both Sends hide" confirmed by the user by hand (2026-09-06): a lasso over a sample text /
  shape offers neither Pad nor Calendar.
- **Tests:** `:app` 1194 → **1337** (+143), every module green.
- **Planner calls recorded:** star outline = alternating outer/inner vertices from the top, inner
  ratio 0.5 (og's skip pattern not copied); arrow arms = `min(0.3·width, 48 px)` at ±150°; a
  shape's AABB pad = `max(strokeWidth/2, 4 dp)`; `TextRenderer.measure` floors the wrap column at
  48 px; `PageObjects` re-measures texts on every load and writes nothing back (N3).
- **Open for H2:** `NotebookActivity` is now 3472 lines (3150 before) despite `PageObjects` — H2's
  brief should keep pulling per-kind flows into their own files.

### H2 — Outcome (2026-09-06)

- **Phase-start answers:** version stays `0.1.0-ratta`; dialog title **"Text"**; Cancel is a
  **button** on every showing (create and re-edit alike — one dialog shape).
- **Landed:** `TextEditDialog` (multi-line raw Markdown, `HeadingEditDialog`'s shape, IME asked for
  on the way in and never hidden, no `IME_ACTION_DONE`, `onCancel` on button/Back/outside behind a
  latch) · `TextLines` (pure: `normalize` for recognized ink — collapse, per-line trim, ≤ 1 interior
  blank; `typed` for the dialog — per-line `trimEnd` + outer blank lines only, the interior is the
  author's) · `TextPlacement.centred` (pure) · `SelectionModes.classify` (D5's table pulled out of
  the activity and pinned by test: lone shape / sticky stay `MIXED` until H4 / H5) · `TextFlow` +
  `TextFlow.Host` (insert / convert / edit out of the activity on the `LinkPickFlow` pattern —
  **nothing exists until Save** on an insert: no placeholder row, no id minted, Cancel and blank
  Save leave nothing) · `HeadingConvert.run(multiLine = true)` (heading and tag callers byte-identical)
  · `SelectionMode.TEXT` + the lasso bar's **Text** button directly after H, ink-only · `TagSelection`
  refuses `TEXT` · `onSelectionTapped` opens a lone selected text's dialog after the heading lookup
  misses · Insert bar offers Text in every build (the other seven stay debug-only), `btnInsert`
  visible in every build.
- **Two review/walk fixes:** (1) the Insert bar's `releaseRender` was still H1's pen-idle-gated
  lambda — a dialog opened by a hovering pen would have waited for the pen to leave; ungated like
  the tags popup. (2) **The walk's one failure:** an inserted text landed selected under a PEN tool
  — drawn selected, but the pen inked through it and could neither drag nor tap it (the eye-check
  #5 round-2 finding again). Fixed by `armLassoForLanding()` — the transfer paste's arm-lasso /
  remember-prior-tool / restore-at-dismissal recipe, now one shared helper the paste and the insert
  both call **before** `setSelection` (the O2 ordering). That is what D4's "the armed tool is
  unchanged by an insert" means in practice: the lasso is armed for the selection's life, the prior
  tool returns at dismissal.
- **Nomad (by hand, the user, 2026-09-06):** all eight checklist items pass after fix (2) —
  insert / cancel / blank save / lasso → Text with line breaks kept / failure leaves ink / stylus
  tap opens the dialog / blank Save deletes / drag / wrap at the right edge / undo-redo ×3 / copy +
  paste across a flip / survives close-reopen.
- **Tests:** `:app` 1337 → **1369** (+32: `TextLinesTest`, `SelectionModesTest`,
  `TextPlacementTest`, `TagSelectionTest` TEXT row); every module green.
- **`NotebookActivity`:** 3472 → 3531 (+59, the `Host` object and the two landing helpers); the
  flows themselves are in `TextFlow.kt`. H4's brief keeps the same rule.
- **Planner calls recorded:** `TextLines` has two rules on purpose (a recognizer's spacing is a
  guess, a person's is deliberate); the lasso bar's Text shares `ic_text_recognition` with the
  Insert bar's; the "nothing recognized" dialog wording is shared by all three convert callers.
- **Open for H4:** the ink-selection bar is now ten buttons with Pad · Calendar · Tag installed —
  it fits one row on the Nomad; H4's Transform button lands on the SHAPE row only, not this one.

### H3 — Outcome (2026-09-06)

- **Phase-start answers:** version stays `0.1.0-ratta`; g-paper **0.1.27** (Paintsprout Onyx had
  taken 0.1.25 raster pages and 0.1.26 pixel eraser the same day — both opt-in behind
  `pageMode`, a no-op for SN); the rotate knob is offered for **every** type.
- **g-paper review before coding:** the two Paintsprout phases touched `CanvasPaperView`'s
  data-in and erase paths and the Onyx module only; the Ratta module was untouched between
  0.1.23 and 0.1.27. Re-pinning pulls in 0.1.24 (pencil hairline, Onyx) as well — nothing SN
  calls changed shape. Two additions to D9 fell out: `loadPageRaster` / `setSelection` are
  transform exits, and the Ratta law-3 hover suppress keys on `selectionBoxContains`, so the
  grab region had to answer there (it does — no Ratta change).
- **Landed in g-paper (commit `921cd9b`, Phase 15 in its `PLAN.md`):** `model/OrientedBox` ·
  `geometry/TransformGeometry` + `TransformGrab` (pure, `TransformGeometryTest` 19) ·
  `PaperView.beginTransform / endTransform / setTransformAspectLocked / transformingContentId /
  transformBox` · `PaperListener.onTransformChanged` (live) + `onTransformEnded` (once, every
  exit) · `canvas/TransformOverlay` (10 dp axis-aligned handles, 14 dp knob on a 36 dp stem,
  `round(density)` px outlines on integer edges) · the mode rides the shared lasso entries, so
  **neither device module changed** · demo **Xform** / **Lock** · `docs/api.md`,
  `docs/host-responsibilities.md`, `CLAUDE.md`. g-paper core suite 193 green.
- **SN:** `sn-screen/build.gradle.kts` pinned 0.1.23 → **0.1.27** (both artifacts); the host
  calls nothing new; `:app` **1369** green (unchanged), `:sn-screen` 69.
- **Nomad (by hand, the user, 2026-09-06):** all eight demo items pass — handles legible, pen
  handle-drag with no firmware trail, free + locked corner, knob snap at the cardinals, body
  move, pen tap-outside, finger drag + finger tap-outside, tool-change exit. **One finding, host
  side:** the demo's finger handler kept consuming finger events in transform mode (it yielded
  only while a selection was active), so a finger tap outside moved the object instead of
  ending the mode. Fixed in the demo; the rule is now in g-paper's `host-responsibilities.md`
  and binds H4: **`NotebookActivity`'s finger gates must yield while
  `paper.transformingContentId != null`, exactly as while a selection is active.**
- **Binding for H4 (the host contract as built):** arm the lasso before `beginTransform`
  (`armLassoForLanding()` — the mode is a no-op under a pen tool); `onTransformChanged` updates
  the `ShapeRenderer` working copy only (the engine repaints via `drawObject`); persist +
  `Action.ShapeTransformed(before, after)` + bar teardown happen in `onTransformEnded` **only**,
  which also fires for the bar's own Done; nothing is selected afterwards, so Done re-`setSelection`s
  the shape with `ShapeGeometry.aabb`; `setTransformAspectLocked` is the bar's toggle; a
  rotated shape's lasso hit stays the AABB (D3).

### H4 — Outcome (2026-09-06)

- **Phase-start answers:** version stays `0.1.0-ratta`; star point count **fixed at 5**
  (`ShapeFlags.DEFAULT_POINTS`) — a bar control is a backlog item.
- **Landed:** `ShapeDefaults` (pure — og's insert numbers: closed shapes 72 dp square, locked for
  rectangle / ellipse / star, triangle free; line and arrow ½ page width × 1 px, free;
  `MIN_SIZE_DP` 24) · `ShapeBox` (pure `PageShape` ↔ `OrientedBox`, rotation normalised through
  `ShapeFlags.normalizeDeg`) · `ShapeTransformLabels` (pure: Circle/Oval · Square/Rect · 1:1/Free —
  the label names the **current** state) · `ShapeFlow` + `ShapeFlow.Host` (insert + the whole
  transform lifecycle, out of the activity on `TextFlow`'s pattern) · `ShapeTransformBar` (a
  floating bar of its own — a word-labelled aspect latch wearing the selected border when locked,
  plus ✓ Done — placed off the shape's AABB grown by the overlay's reach (36 + 14 + 22 dp) so it
  never sits under the knob, and re-placed **only** when the live overlay reaches it) ·
  `ic_resize` (Tabler) for the lasso bar's **Transform**, directly after Text, `SHAPE` only ·
  `SelectionMode.SHAPE` (`SelectionModes.classify(isShape)`; a lone sticky stays `MIXED` until
  H5; `TagSelection` refuses SHAPE; Link offered) · the six shapes offered on the Insert bar in
  every build (Sticky stays debug-only) · `PageGestures.standDown` widened to
  `selectionActive || paper.transformingContentId != null` (H3's finding) · `transformBar` in
  the exclusion rects and `overChrome` · `endTransformIfRunning()` at **eight** sites — `close()`,
  `onStop`, `navigateTo` (at the top, then `drain()`, so a same-page refresh reads the written
  geometry), both extension `beforeLaunch` handoffs (`releaseForHandoff` is a silent release),
  and the three other floating bars' `show`s (another bar taking its place ends the mode).
- **The host contract as built (binds H5+ and any later shape work):** `beginTransform` after
  `armLassoForLanding()` and after `dismissSelectionChrome()` by hand (the engine dismisses
  without `onSelectionDismissed`); a declined `beginTransform` (id not adopted) rolls back and
  re-selects, since no `onTransformEnded` will come; `onTransformChanged` → working copy only
  (`objects.put`), never a frame; `onTransformEnded` compares **`PageShape`s** (a lock flip with no
  drag is an entry), persists + records `ShapeTransformed(pageId captured at begin)`, and then
  **Done re-selects** while every other exit calls `restoreToolAfterTransferPaste()`; the
  working-copy / selection half is guarded on `alive && pageId == host.pageId`, the row write is
  not.
- **Nomad (by hand, the user, 2026-09-06):** all nine checklist items pass first time — insert ×6,
  drag, handles + knob legible, aspect labels per type, star at 37°, line snapped vertical,
  tap-outside exit, undo/redo incl. a lock-only entry, fingers idle in the mode, eraser + scribble,
  copy/paste across a flip, link-wrap/unlink, close-reopen, PDF stroke-only.
- **Tests:** `:app` 1369 → **1393** (+24: `ShapeDefaultsTest`, `ShapeBoxTest`,
  `ShapeTransformLabelsTest`, `InsertBarKindsTest`, SHAPE rows in `SelectionModesTest` /
  `TagSelectionTest`, a lock-only `ShapeTransformed` in `NotebookUndoTest`, a rotated star through
  `ObjectClipTest`); `:sn-screen` 69; every module green.
- **`NotebookActivity`:** 3531 → 3676 (+145: the `Host` object, `selectAsShape`,
  `loneSelectedShapeId`, `endTransformIfRunning` and its call sites, the two listener overrides).
- **Planner calls recorded:** `ObjectClip.payloadBounds` uses the density-free
  `ShapeGeometry.tightBounds` + `strokeWidth/2` for a shape (a payload is page px, not one
  screen's), not `aabb(shape, density)` — the plan's D6 wording is corrected by this entry;
  `InsertBar.shapeType(kind)` is the one routing table (tested); the lock button is styled field by
  field (a style cannot be applied to a code-built view — `ExportPanel`'s finding).
- **Open for H5:** nothing new; H5's sticky insert follows `ShapeFlow.insertAtCentre`'s one-block
  shape and calls `armLassoForLanding()` before `selectAsSticky`.
