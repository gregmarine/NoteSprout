# HARVEST_PLAN.md — Arc 31 "Harvest" (Notesprout SN, branch `ratta`)

**Standalone plan for the export and import extras** — item 6 of `PARITY_BACKLOG.md`: pages as
images, page-to-template, export presets, and calendar export in both halves. This file is the
cross-session memory for the arc: read it whole at every phase start, together with the root
`CLAUDE.md` and `apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc**
unless a standing trap needs checking; its protocol and traps are summarized at the end so this
file is enough. `PAGE_PLAN.md` is the shape this file copies.

**Status: 🔄 HV4 ✅ 2026-09-09.** HV1 ✅ · HV2 ✅ · HV3 ✅ · HV4 ✅ · HV5 ⬜ · HV6 ⬜.
Baseline before the arc: 1487 `:app` / 2847 JVM tests, g-paper 0.1.28, `API_VERSION` 8, thirteen
modules, version `0.1.0-ratta`.

**Phase code:** **HV** — two letters, the arc-29/30 precedent.

---

## What this arc is

Four sub-efforts the user named in one breath, all of them about getting things *out* of the
garden, hence the name. Each rides machinery SN already has:

- **Pages as images.** A fourth exporter, `NSE · Image Export` (`:ext-image`, PNG), on the existing
  `SOURCE_PAGES` bundle. At page scope it is one PNG through the normal picker; at whole scope the
  host writes **one PNG per page into a folder** — a SAF tree locally, the picked folder on the
  cloud leg — calling the exporter once per page with a one-page bundle, so the seam's
  one-call-one-file contract and `ExportVerification` stay single-file.
- **Page-to-template.** A page-sheet row **Save as template**: the host rasters the displayed page
  as the export would (paper + ink, page-sized WEBP q100 — already the byte shape an index template
  row holds), names it, picks a folder, and lands it in the template library. No close-export-reopen.
- **Export presets.** Named, saved combinations of exporter + option values + Source + destination
  (cloud folder included). Never the secret, never the scope (og's rule, kept). Stored as an
  **additive index row type** so they ride backup and restore. A radio row at the top of the Export
  panel.
- **Calendar export, both halves on one seam growth.** `ICalendar` grows a **`render`** call that
  writes a `PageBundle` of calendar pages (grid · ink · today ring · event marks as flags) to a
  host-owned fd. The host becomes a fourth bundle producer, so PDF and PNG of a calendar view come
  free from the exporters that exist. **File export:** an Export button on the calendar's bar → the
  host opens the Export screen in calendar mode. **Send page to notebook:** the whole-page send
  inserts a **new page** papered with the grid (rendered without ring or marks so its `IMG#` token
  dedupes) with the ink on top; the lasso's send-selection stays ink-only onto the displayed page.

**What crosses the seam.** `ExtensionContract.API_VERSION` **8 → 9**: an `ExporterInfo` compatible
tail (`delivery`: one file · one file per page) and two `ICalendar` methods (`render`,
`outgoingTarget`). Every floor in `MIN_API_VERSIONS` is unchanged; the render is offered only when
the installed calendar declares 9. **No ninth point**, no `.soil` version bump, no g-paper change.
Module count goes to **fourteen** (`:ext-image`).

## What SN already has (do not rebuild)

| Piece | SN today | This arc |
|---|---|---|
| `INotebookExporter` (`describe` · `export(source fd, destination fd, spec)`), `ExporterInfo` with its two compatible tails (`sourceKind`, `bundleVersion`) | three exporters | a fourth exporter; a third tail `delivery` (D1) |
| `ExportRender.bake` → `PageBundle.Writer` (page-sized RGB_565 → WEBP q100; endnotes only at bundle v2) | one bundle per export | baked **once**, then split into one-page bundles for a per-page exporter (D1); the same raster feeds page-to-template (D2) |
| `PageBundle.Reader` / `Writer` in `:extension-api` (one page at a time) | | the host's splitter and `:ext-image`'s decode (D1); `:ext-calendar`'s writer (D4) |
| `ExportScope` — host-side page-id filter, `lists` / `offerable` | This page · Whole | `Calendar(targets)` as a third scope; the image exporter inherits page scope for free (D1, D4) |
| `ExportNaming.pageStem` — `<notebook> - <heading>` else `<notebook> - page N` | the page-scope filename | every per-page PNG's name; the template's default name (D1, D2) |
| `ExportActivity` — chooser, `ExportPanel.choice` rows (Scope · Source · Destination), `described` vs `candidates`, `reselect()`, `runExport`, cloud leg, `finish()` override | | the Preset row (D3), the folder destination for per-page exporters (D1), calendar mode (D4) |
| `ExportPrefs.lastExporter` (`sn_export`) | the one remembered thing | untouched; presets are index rows (D3) |
| Additive index row types (`naming`, `clipboard`, `backup`) — `name` + `flags` + `blob` JSON, identity hash untouched | | `export_preset` (D3) |
| `TemplateTransfer.ingest` → fit sheet → name dialog → `repo.createTemplate(name, parentId, KIND_IMAGE, fit, bytes)`; `TemplateImport.overCap` (6 MiB); `TemplateLibrary.isReservedName` | SAF picture import | the tail from `Loaded.Ok` reused with fit pinned (D2) |
| `FolderPickerActivity` in `TEMPLATE_FOLDER` mode (the browser's Move) | | the save-as-template folder pick (D2) |
| `NotebookSession.mintOrReuse` — reuse before mint by `token + page size`; `TemplateToken.ofImage(bytes, fit)` | re-paper | the calendar grid paper on the new page (D5) |
| `ICalendar` held bind (`begin` · `receiveInk` · `takeOutgoing` · `end`), `CalendarTarget`, `HeldInkClient.drainOutgoing`, `ExtensionScreenEntry.onResult` | ink both ways | `render` + `outgoingTarget` (D4, D5) |
| `CalendarTemplate.month/week/day` + `CalendarGeometry` (pure, Context-free, insets as parameters), `BakeKey`, `DayMark` | the screen's bake | the render's painter — insets 0, ring and marks by flag (D4) |
| `ITagManager`'s bind-per-call shape (the store rides the call) | | `render(store, …)` (D4) |
| `ExtensionStores.open` + `ExtensionStoreBinder` minted per bind | | the store lent to the render call (D4) |
| `CalendarToolbar` (`btnSend` GONE unless `sendEnabled`), `RESULT_CALENDAR_*` codes, `reopenCalendarAfterPad` latch | | an Export button + `RESULT_CALENDAR_EXPORT`; reopen after export (D4) |
| `NotebookActivity.pasteTransferred` (mint ids, append after max order, lasso armed, one `ObjectsPasted`) | the ink landing | selection sends unchanged; page sends go through a new-page road (D5) |
| `CloudClient.upload` replace-by-name into a picked folder; `CloudBrowserDialog PICK_FOLDER` | one file | N files into the same folder (D1) |

## Decisions (wizard 2026-09-08 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Name / code | **Arc 31 "Harvest"**, phase code **HV**, this standalone `HARVEST_PLAN.md`. |
| 2 | Multi-page images | **One PNG per page into a folder.** Page scope = one file through the normal picker. Local whole scope = SAF tree pick; cloud = the folder already picked. Host calls the exporter once per page. Zip declined; page-scope-only declined. |
| 3 | Image module | **New `:ext-image`, "NSE · Image Export"**, fourteen modules, the one puzzle icon. |
| 4 | Page-to-template door | **Page-sheet row "Save as template"** → name dialog (seeded heading or "page N") → **folder picker** (`FolderPickerActivity`, template-folder mode, root allowed). Fit pinned to Fit, 6 MiB cap kept, no undo. og's export-destination door declined. |
| 5 | Presets | **Additive index row type `export_preset`**; the panel's captioned **radio row first** (`None · names`); a **Save preset…** action; long-press a name → rename / delete; any hand change drops back to None. SharedPreferences declined; a sheet instead of a row declined. |
| 6 | Calendar seam | **`ICalendar.render`, `API_VERSION` 8 → 9.** The calendar writes a `PageBundle` to a host-owned fd; not a ninth point; the screen floor stays 7. Host-side repaint of the grid declined (painter would move, `CalendarSchema` would become a contract, audit row "the host computes no calendar arithmetic" would break). |
| 7 | Send with paper | **Send page = a new page after the displayed one, papered with the grid, ink on top, one undo entry. Selection sends stay ink-only** onto the displayed page. Re-papering the displayed page declined; both-sends-carry-paper declined. |
| 8 | Phases / review | **Six phases HV1–HV6, no code review** (the arc 27–30 shape). HV6 is docs + freeze. |

### Derived rules (not separately asked — recorded so they are not re-litigated)

- **The API bump lands in HV1**, not HV4: `API_VERSION = 9` with the `ExporterInfo.delivery` tail;
  HV4 adds the `ICalendar` methods under the same 9. `MIN_API_VERSIONS` is untouched (floors 1 / 6 /
  7 / cloud's). `:ext-image` declares 9 (born there); `:ext-calendar` re-declares 9 at HV4 so the
  host can offer render; every other extension keeps its declaration. The map-pinning test and the
  `API_VERSION` ledger in `docs/extensions.md` both change.
- **`delivery` is a descriptor fact, read by the host only from a service declaring ≥ 9**; absent =
  `DELIVERY_ONE_FILE` (every existing exporter). `DELIVERY_PER_PAGE` is legal only with
  `SOURCE_PAGES` (an `ExportOptions.isRenderable` rule, like the reserved-option bindings).
- **A per-page exporter at whole scope bakes once and splits.** `ExportRender` bakes the bundle
  as today; a pure host splitter reads it page by page and writes a one-page bundle per exporter
  call. No second `.soil` open per page. Endnotes never reach a per-page exporter: `:ext-image`
  declares bundle v1, so `ExportRender` plans none.
- **The image exporter's options:** `OPTION_PAGE_TEMPLATE` only (host-executed, as PDF's). No
  password (PNG has none), no keying. PNG at compression 100, RGB_565 decode like `:ext-pdf`.
- **Per-page delivery verification is per file** (`ExportVerification.verdict` unchanged per
  call); the done dialog says "N images were exported"; a failure mid-loop stops, keeps what was
  written, and reports "N of M images were exported" — never a silent partial success.
- **Filenames in the folder** come from `ExportNaming.pageStem` per page; SAF providers de-dupe
  collisions themselves (`(1)`), the cloud leg replaces by name after **one** confirmation naming
  the count.
- **Page-to-template rasters the page as it is on the glass, minus chrome:** page-sized, paper +
  ink, `PagePreview.drawContent` over the loaded template — **not** `PagePreview.render` (it draws
  a page edge) and not `CoverSnapshot` (cover size). `session.store.drain()` first. Encoded with
  `BuiltInTemplates.toWebp`; over the 6 MiB cap → the import's `TooBig` dialog.
- **Save as template never opens the `.soil` cold** — the session is open; it reads through the
  session. No `ExportOpen`, no close.
- **A preset row:** `type = "export_preset"`, `name` = the preset's name, `flags` = grammar
  version 1, `blob` = kotlinx JSON `{ exporter (package), values (map), documentSource (bool),
  destination (LOCAL | CLOUD), cloudPath (list) }`. Soft-deleted on delete. Listed by
  `aliveOfType`, ordered by name. A corrupt blob is skipped, never crashes the screen.
- **A preset whose exporter is not installed is not listed** (GONE rule) — it reappears when the
  exporter is. A preset naming the cloud with no provider connected applies as Local with a toast.
  A preset whose exporter is hidden by the current scope (Soil at page scope) is not listed at that
  scope.
- **Applying a preset sets the format, the option values, Source and Destination, then re-renders;
  a preset needing a secret leaves the password fields empty for the user** (never stored). The
  Preset row is absent when there are no presets; the Save action is always present.
- **Presets apply in calendar mode too** by the same listing rule.
- **The calendar render signature:** `void render(in IExtensionStore store, in CalendarTarget[]
  targets, int widthPx, int heightPx, int flags, in ParcelFileDescriptor out)` — bind-per-call
  (the tag manager's shape), `HostCallerCheck.enforce` first, one `PageBundle` v1 out (no links).
  `widthPx`/`heightPx` are the page size for a target with **no minted page**; a minted page keeps
  its own. Insets 0 — a full-page grid. Flags: `RENDER_GRID`, `RENDER_INK`, `RENDER_RING`,
  `RENDER_MARKS`. Every target validated by `CalendarTarget`'s own unmarshal.
- **File export renders the view on screen**: Month → one page, Week → one, **Day → both halves**
  (og's rule) whatever half is showing. Flags = grid (the page-template toggle) · ink · ring off ·
  marks on. Filename `Calendar - September 2026.<ext>` / `Calendar - Week of 2026-09-06` / `Calendar
  - 2026-09-08` — pure, tested.
- **Calendar mode on the Export screen:** `EXTRA_CALENDAR_TARGETS` (kind/date/half triples — not
  transfer content, not a secret), no notebook, **no `.soil` opened**, Soil and Document hidden
  (`ExportScope.Calendar` lists only `SOURCE_PAGES`), no Scope row, no Source row, the page-template
  toggle labelled as today (it means the grid). The door does **not** close a notebook: the
  calendar was launched for a result by the library or the notebook, the host receives
  `RESULT_CALENDAR_EXPORT`, ends the bind, starts Export; on Export's finish the caller **reopens
  the calendar** (the `reopenCalendarAfterPad` idiom). The notebook stays open throughout — no
  cold-file rule applies because no `.soil` is read.
- **The render's store is lent per call**: the host opens `Garden/<calendar pkg>.db` the normal
  `ExtensionStores.open` way, mints an `ExtensionStoreBinder` for the calendar's uid, passes it,
  revokes after. A render on the held bind (D5) passes the same binder the bind already holds.
- **Timeout for render is measured, not assumed** (the export trap): first cut `RENDER_TIMEOUT_MS`
  = `EXPORT_TIMEOUT_MS`; measure a Day pair and a full Month on the Nomad and record.
- **Send page with paper:** the calendar parks the ink as today and the host asks
  `outgoingTarget()` on the still-held bind (null = a selection send or nothing parked), then
  `render(store, [target], w, h, RENDER_GRID, out)` on the same bind before `end()`. The paper
  bytes are the bundle's one page, re-encoded host-side through `toWebp` only if not already WEBP
  (the calendar writes WEBP q100 — the F5 recipe), bounded-decoded first (untrusted bytes from an
  extension: `MAX_TEMPLATE_EDGE`, `MAX_PAGE_BYTES`). Token = `TemplateToken.ofImage(bytes, FIT)`;
  `mintOrReuse` dedupes a repeat send of the same month.
- **A page send with no ink sends the paper alone** (og's "Template only") — "Nothing to send"
  stays for selection sends only. A page send on a calendar opened from the **library** (no
  notebook) has no Send button, as today.
- **The new page** goes after the displayed page, sized to the calendar page's size (coordinates
  1:1, the standing rule), with the grid as its template and the ink appended. **One undo entry**
  — read `Action.Page(snapshot)` at HV5 start: if the page-insert snapshot already carries the
  page's children and template on revert/reapply, use it; else a `Action.PageReceived` kind that
  deletes / re-inserts the page by id. Lands with the lasso armed on the ink, as every landing.
- **Frame silence:** every new dialog and sheet row is deliberate-tap chrome; the per-page export
  progress dialog is the existing one.
- **GONE, never disabled:** the Export button on the calendar bar is absent when no exporter
  serving `SOURCE_PAGES` is installed (the host passes `EXTRA_CALENDAR_EXPORT_ENABLED`, a fourth
  boolean on the Intent — audit row 30 gains a word); the Preset row is absent with no presets;
  Save as template is absent when the page cannot be rastered (no size).
- **`lastExporter` is written on every export** (image, calendar, preset-driven alike).

---

## Design (binding unless a phase-start question reopens it)

### D1 — Images (HV1)

- **`:extension-api`:** `ExporterContract.DELIVERY_ONE_FILE = 0` / `DELIVERY_PER_PAGE = 1`;
  `ExporterInfo.delivery: Int = DELIVERY_ONE_FILE` as a third compatible tail (read with
  `dataAvail`, only meaningful when the service declares ≥ 9; the host reads it through the
  registry's version answer). `API_VERSION = 9`. Tests: the parcel round-trip both shapes, the
  version map pin.
- **`:ext-image`** (`…notesproutsn.ext.image`, "NSE · Image Export", puzzle icon, manifest
  `API_VERSION 9`, `HostCallerCheck.enforce` first): `ImageDescriptor` (`formatLabel` "PNG image",
  `fileExtension` "png", `mimeType` "image/png", options = `OPTION_PAGE_TEMPLATE`, `sourceKind =
  SOURCE_PAGES`, `bundleVersion = VERSION_1`, `delivery = DELIVERY_PER_PAGE`); `ImageExportSpec`
  (supported options = the one; unknown refused, PDF's rule); `ImageAssembly` — read the bundle
  (expect exactly one page; more is an `IllegalStateException` naming the count), decode RGB_565,
  dimension-check against the declaration, `compress(PNG, 100)` through the counting + fsync
  delivery (`SoilStreams`' rule), recycle. Module deps: `:extension-api` only. Settings +
  `build.gradle.kts` by the `:ext-pdf` template.
- **Host, per-page delivery:** `ExportDelivery` (pure rules) — `perPage(info, scope)`: a
  `DELIVERY_PER_PAGE` exporter at a one-page scope is single-file; at more than one page it is
  per-page. `ExportActivity.onExportTap`: per-page → `ACTION_OPEN_DOCUMENT_TREE` (local) or the
  cloud folder pick as today. `runExport` per-page branch: bake once (`renderedPages` unchanged) →
  `BundleSplit` (pure over `PageBundle.Reader`/`Writer`: page *i* → a one-page v1 bundle file in
  `cacheDir/export/`) → per page: `DocumentsContract.createDocument(tree, mime, name)` /
  `openCacheDestination` → `ExporterClient.export` → `verdict` → cloud upload → next. Progress
  dialog counts pages. Names from `ExportNaming.pageStem(displayName, notebookId, n, title)` — the
  titles come from the same `readOnce` that answers `PageFacts` today, widened to every page in
  scope (`PageLabels.titleOf` per page).
- **Cloud:** `confirmThenUpload` says "N files will be uploaded to <folder>; files with the same
  name are replaced" once; uploads loop.
- **Done wording:** `export_done_images_body` "N images were exported." / partial
  `export_done_images_partial_body` "N of M images were exported."
- Tests: descriptor tail round-trip; `ImageExportSpec`; `ExportDelivery.perPage`; `BundleSplit`
  (a 3-page bundle → three 1-page bundles, bytes identical); `ExportOptions.isRenderable` refuses
  `DELIVERY_PER_PAGE` on a non-pages source; naming per page; the version map.

### D2 — Save as template (HV2)

- Page-sheet row **Save as template** (`ic_template`? — no: that is the Page template row's icon;
  use Tabler `photo-plus` or `template` variant — check og's `drawable/` first) after Export page.
- `saveAsTemplate()` (through `runPageOp`): `session.store.drain()` → `PageRaster.of(session,
  pageId)` (new, host: page-sized RGB_565, white, template bitmap the session already holds —
  `loadTemplateFor` — then `PagePreview.drawContent` with the page's content read the way the
  neighbour prefetch reads it) → `toWebp` → `TemplateImport.overCap` → name dialog (`NameDialog`,
  seeded `PageLabels.titleOf` else "page N", `NameRules.validate`) → `FolderPickerActivity.intent(
  browseFolderType = TEMPLATE_FOLDER, rootLabel = templates_title)` → `isReservedName` /
  duplicate check (`TemplateLibrary.duplicateName` for a clash) → `repo.createTemplate(name,
  folderId, KIND_IMAGE, TemplateFit.FIT, bytes)` → toast `template_saved`. The launcher result
  callback latches at its **top** (the S2 rule); the bytes are held in the Activity across the
  picker (not instance state — a rebuilt screen refuses with a dialog, as the export secret does).
- No `.soil` write, no undo, no recents record, no library row for the page.
- Tests: `PageRaster` is Android-bound (no JVM test); the name seeding and the dedupe rule are
  pure and tested.

### D3 — Presets (HV3)

- `ObjectType.EXPORT_PRESET = "export_preset"` (additive; identity hash untouched — pinned by the
  existing hash test). `ExportPreset` (kotlinx, `data/export/`): `exporter`, `values`,
  `documentSource`, `destination`, `cloudPath`, `version`. `ExportPresets` (pure): `listable(presets,
  installed, scope, cloudAvailable)`, `apply(preset) → screen state`, `capture(screen state)`.
  `IndexRepository`: `exportPresets()`, `createExportPreset`, `renameExportPreset`,
  `deleteExportPreset` (soft).
- Screen: container `@id/presets` above `@id/scope`; `ExportPanel.choice` with `None` first; a
  **Save preset…** text button in the panel under the row (the panel's idiom; `TextButton` needs
  its own `layout_width` if it goes to XML — it does not, the panel builds it in code); long-press a
  preset radio → `ActionSheetDialog` Rename · Delete. Applying: `applyingPreset` latch around the
  widget writes so listeners do not clear the pick (og's shape); any hand change → None.
- Tests: `ExportPreset` JSON round-trip + corrupt blob skipped; `ExportPresets.listable` (not
  installed, hidden by scope, no cloud); `capture`/`apply` inverse.

### D4 — Calendar render + file export (HV4)

- **`:extension-api`:** `ICalendar` gains `void render(in IExtensionStore store, in
  CalendarTarget[] targets, int widthPx, int heightPx, int flags, in ParcelFileDescriptor out)` and
  `CalendarTarget outgoingTarget()` (appended — existing transaction codes unchanged). Constants
  `RENDER_GRID = 1`, `RENDER_INK = 2`, `RENDER_RING = 4`, `RENDER_MARKS = 8`;
  `MIN_API_VERSION_FOR_CALENDAR_RENDER = 9` (a method floor, not an action floor — the map is
  untouched); `EXTRA_CALENDAR_EXPORT_ENABLED`; `RESULT_CALENDAR_EXPORT = 3`; `RENDER_TIMEOUT_MS`.
- **`:ext-calendar`:** `CalendarRender` (extension side) — per target: `CalendarStore` header +
  strokes for the page (or the given size when unminted), `CalendarGeometry.*(w, h, density,
  0, 0)`, `CalendarTemplate.*` with `ring`/`marks` honoured (add a `ring: Boolean` parameter; marks =
  `emptyMap()` when the flag is off), ink drawn over it through the shared `:ext-ink` stroke painter
  (the one the screen's g-paper does not expose — read at phase start whether `:sn-screen` has a
  bitmap stroke painter; if not, a small `StrokePainter` in `:ext-ink` from `StrokeRows`'
  decoded points, round caps, 3 px, the notebook's fixed ink), composited on white RGB_565 → WEBP
  q100 → `PageBundle.Writer` v1. Density: the extension's own display density (the calendar page
  was sized under it). `CalendarService.render` = enforce → store guard → the loop → close the fd.
  `outgoingTarget` answers the parked send's target. Manifest `API_VERSION 9`. The bar gains
  `btnExport` (`ic_download`, GONE unless `EXTRA_CALENDAR_EXPORT_ENABLED`) → parks the current
  view's targets (Day = both halves) → `finishWithHandoff(RESULT_CALENDAR_EXPORT)`.
- **Host:** `CalendarClient.render(...)` bind-per-call with a lent store; `CalendarEntry.onClosed`
  handles `RESULT_CALENDAR_EXPORT` → the caller (library or notebook) starts `ExportActivity.intent(
  calendarTargets = …)` and latches `reopenCalendarAfterExport`; `ExportScope.Calendar(targets)` —
  `lists` = `SOURCE_PAGES` only, `offerable` = any such exporter installed **and** the calendar
  declares 9; `CalendarRender` (host side, a fourth producer beside `ExportRender` /
  `DocumentPdfRender` / `ExportText`): open the store, bind, `render`, verify the bundle header,
  `Outcome.Ready(file)`. `ExportNaming.calendarStem(target)`. `PageFacts` absent; `hasDocument`
  false; the header shows "Calendar".
- Tests: `ExportScope.Calendar` rules; `calendarStem`; `CalendarRenderPlan` (pure: targets →
  page list, Day doubles); the render flags; parcel round-trips; the map pin.
- **Walk (Nomad):** Month → Export → PDF to SAF → one page, grid + ink, no ring, marks present ·
  PNG → one file · Day → two files AM/PM · the calendar reopens after · with the toggle off → white
  ground + ink · no exporter → no button · the image exporter uninstalled but PDF present → still
  offered.

### D5 — Send page with paper (HV5)

- `ExtensionScreenEntry.onResult` on `RESULT_CALENDAR_SEND`: drain ink as today, then
  `outgoingTarget()`; non-null → `render(store, [target], w, h, RENDER_GRID, out)` on the held bind
  → `DrainedInk` gains `paper: ByteArray?` (null = selection send). Then `end()`.
- `NotebookActivity.receiveCalendarPage(drained)`: `session.insertPageAfter(displayed, width,
  height)` → `session.changeTemplate(PaperSource.Image(paper, FIT), dpi)` on the new page (mint or
  reuse) → `pasteStrokes` → one undo entry (D-rule above) → `refreshToPage(new)` → lasso armed on
  the ink. `pasteFromCalendar` keeps the ink-only road for `paper == null`.
- The empty-page send: the calendar's `sendPage()` no longer refuses an empty page — it parks zero
  chunks and the target; the host inserts the papered page with no ink. "Nothing to send" only for
  selections.
- Tests: `NotebookUndoTest` for the new kind (if any); `TransferCaps` unchanged; a pure
  `CalendarPaper.accept(bytes)` bound check.
- **Walk (Nomad, by hand — the pen):** write on a Month → Send page → a new page after the current
  one with the grid and the ink, lasso armed · undo → page gone · redo → back · send the same month
  twice → one template row in the `.soil` (`templateDigests`) · send an empty Week → grid alone ·
  lasso a fragment → Send → lands on the displayed page, no new page, as today · `am crash` after
  a send → the page persists.

---

## Phases

### ✅ HV1 — Images (Opus code on a Fable brief for `:ext-image` + host loop · Sonnet scaffold (module, manifest, icon, strings) · Fable seam + review · Sonnet adb walk up to the picker, SAF by hand)

**Phase-start answers (2026-09-08):** version stays `0.1.0-ratta`; PNG at compression 100 over an
RGB_565 decode (the `:ext-pdf` shape); per-page delivery at whole scope is offered on **both**
doors — the library door's whole-notebook export is exactly the folder case.

- D1. Read first: `ExtensionRegistry`'s per-service version answer (how the host learns a
  service declares 9); `ExportActivity.runExport`'s `finally` (the cache dir is one directory for
  every producer — the split files live under it); `CloudClient.upload`'s replace semantics for a
  loop.
- **Walk:** page scope PNG to SAF (one file, heading name) · whole notebook to a SAF folder (N
  files, names, `(1)` on a repeat) · cloud folder N files · template toggle off · Document source
  as PNG (the preview pages) · the PDF exporter unchanged · uninstall `:ext-image` → PNG gone.

### ✅ HV2 — Save as template (Opus on a Fable brief · Fable review · walk by hand)

**Phase-start answers (2026-09-08):** version stays `0.1.0-ratta`; the row is **last, after
Export page**; the folder picker's root breadcrumb is the existing **"Templates"**
(`templates_title`); a heading-less page seeds **"page N"** (a heading seeds its text).

- D2. Read first: how the neighbour prefetch reads a page's content for `PagePreview`
  (`PageReads.content` vs the session's cache); `TemplateTransfer`'s dialog chain to copy the
  tail; `FolderPickerActivity`'s template-mode result contract.
- **Walk:** page with ink + heading → Save as template → name seeded → folder → toast → the
  library shows it, its thumbnail is the page → apply it to a new notebook → paper = the page ·
  cancel at the name / at the folder writes nothing · a reserved name refused · a huge photo-paper
  page over 6 MiB → TooBig dialog.

### ✅ HV3 — Presets (Opus on a Fable brief · Sonnet strings · Fable review · Sonnet adb walk)

**Questions to resolve at phase start:** app version; the Save action's placement (planner: under
the Preset row in the panel); whether applying a preset with a stale cloud folder browses or
refuses (planner: applies the path; the upload's own failure explains).

- D3. Read first: the identity-hash test for the index; `ExportActivity`'s restore path (a preset
  pick survives rotation like the format pick); the `applyingPreset` latch's interaction with
  `reselect()`.
- **Walk:** save "Drive PDF" (PDF, template off, cloud folder) → reopen Export → row shows it →
  apply → every control matches → export → done · hand-change → None · rename · delete · a preset
  for PNG with `:ext-image` disabled → hidden, re-enabled → back · page-sheet door lists presets
  (Soil preset hidden) · backup → restore → presets present.

### ✅ HV4 — Calendar render + file export (Fable seam + `CalendarRender` both sides · Opus the Export screen's calendar mode · Sonnet strings/XML · Fable review · walk by hand)

**Phase-start answers (2026-09-09):** version stays `0.1.0-ratta`; a file export draws **marks
AND the ring** (the user's call over the planner's ring-off); the unminted page size is **portrait
display pixels** (what the library mints new notebooks at); the timeout was measured (ledger). A
fourth question the bar measurement forced: **a twelfth top-bar button overflows the Nomad on the
notebook door** (11 × 62 dp + margins = 726 of 749 dp with Send and the pad showing), so the Export
door is **the Send button made an out-door** — Send alone · Export alone (`ic_download`, the library
door) · a Send page / Export… sheet when both are open — the user's call over a bottom-bar button
(pager-only rule), a pad-slot swap, or a hidden long-press.

- D4. Read first: whether any shared module can paint strokes to a `Canvas` (the g-paper
  `renderToBitmap` is view-bound); `CalendarStore`'s read path for a page's strokes outside a
  showing; `ExtensionStoreBinder` minting outside `HeldInkClient.open`.
- Walk as under D4.

### ⬜ HV5 — Send page with paper (Opus on a Fable brief · Fable review · walk by hand)

**Questions to resolve at phase start:** app version; the undo shape (read `Action.Page`);
whether the new page inherits the notebook's default size or the calendar page's (planner: the
calendar's — 1:1).

- D5. Walk as under D5.

### ⬜ HV6 — Docs, ledger, freeze (Fable docs directly or Sonnet fan-out · no code, no code review)

- `docs/export.md`: § Images (per-page delivery, the splitter, folder destinations, naming), §
  Presets, § Calendar mode, the `EXTRA_*` table, failure rows, Related. `docs/extensions.md`:
  `API_VERSION` ledger (9 = the third tail + two `ICalendar` methods), the module table
  (`:ext-image`), the boundary audit rows (the render fd, the lent store, the Intent's fourth
  boolean), `:ext-image`'s identity. `docs/calendar.md`: § Export, § Send page with paper, the
  transfer table. `docs/templates.md`: § Save as template. `docs/notebook.md`: the page sheet's
  eight rows, the received-page undo row. `docs/library.md`, `docs/cloud.md` (N-file upload).
- `PARITY_BACKLOG.md` item 6 → DONE; `RATTA_PLAN.md` header; app + root `CLAUDE.md`; memory.
  Freeze.

---

## Planner calls the wizard didn't cover (implementer follows; the user can override at phase start)

- Sheet rows after this arc: Copy · Cut · [Paste] · Page template · Erase page · Delete page ·
  [Export page] · [Save as template].
- Image exporter label "PNG image", extension `png`; done wording above.
- Preset names follow `NameRules`; duplicates refused with the rename dialog's wording.
- Calendar filenames: `Calendar - <Month YYYY>` · `Calendar - Week of <Sunday ISO>` · `Calendar -
  <ISO day>` (+ ` AM` / ` PM` per file when the exporter is per-page).
- The calendar's Export button sits after `btnSend` on the top bar; measure the Nomad bar before
  adding (arc 29's twelfth-button lesson — the calendar bar is shorter than the notebook's).
- Walks: adb can drive sheets, the Export screen, the calendar's buttons and the SAF picker up to
  the file list; **the pen and the folder pick are by hand**.

## Standing traps that bind this arc

- **A new point needs both actions in the host's `<queries>`** — not a new point here, but
  `:ext-image` is a new *package* on an existing action: discovery is by action, so no manifest
  change; verify `queryIntentServices` lists four exporters before blaming signatures.
- **Bytes from an extension are untrusted** — bounded decode, dimension check against the
  declaration, size caps, never a path or content in an exception message.
- **A Binder call cannot be cancelled** — size the render timeout by the work (a full Month with
  ink), measure on the Nomad.
- **A SAF pick cannot be driven by adb** — tree picks included.
- **`ExportOpen` guard 2 refuses a held `.soil`** — the calendar mode never opens one; the
  notebook door still closes first.
- **ActivityResult callbacks run before `onResume`** — latch at the top of every result callback
  (the folder pick, the template folder pick, the calendar's export result).
- **`showSoftInput` from `onResume` is dropped** — the name dialogs use the standing
  `NameDialog` shape.
- **GONE, never disabled** — every new control above.
- **The dialog-border trap** — every new dialog is `Dialogs.*` or a plain `AlertDialog`.
- **`Widget.Notesprout.TextButton` sets no `layout_width`** — panel buttons are built in code.
- **File tools can land a raw NUL byte** — byte-scan changed files before calling a phase done.
- **Drain the shared `SoilWriter` before any raster** of the current notebook.
- **Reuse before mint; render at the page's own size; `applyTemplate` never decodes.**
- **`updatedAt` is sacred** — a preset row's rename bumps it; nothing else does.
- **Backing out of a live notebook through the app before installing** keeps the EPD pin from
  leaking; the calendar too.
- **Doc agents never run git, never revert files they did not create** (the Z6 trap).
- **Check og's `drawable/` before drawing a "fresh" icon.**

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

### HV1 — Images (2026-09-08, Fable seam + review · Opus `:ext-image` and the host loop in parallel · Sonnet adb walk)

**Outcome.** `ExtensionContract.API_VERSION` **8 → 9** with `ExporterInfo.delivery` as the third
compatible tail (`ExporterContract.DELIVERY_ONE_FILE` / `DELIVERY_PER_PAGE`, absent = one file; the
constructor refuses per-page on any source but `SOURCE_PAGES`, which is the `isRenderable` rule the
plan named — an unmarshal refusal, not a second check); `MIN_API_VERSION_FOR_DELIVERY = 9`, no
floor moved (`CloudContractTest` had pinned the cloud floor to *the current* `API_VERSION` — re-pinned
to 8). **`:ext-image`** — fourteenth module, `NSE · Image Export`, manifest 9, puzzle icon
byte-identical, `:extension-api` only: `ImageDescriptor` (PNG image · png · image/png · one
template toggle · pages · bundle v1 · per-page), `ImageExportSpec` (unknown ids and any secret
refused), `ImageAssembly` (`requireOnePage` — a multi-page bundle is an `IllegalStateException`,
never its first page; RGB_565 decode, dimension check, PNG 100 through a counting stream + the
`S_ISREG` fsync rule). **Host** — `ExportDelivery` (the tail is read only from a service declaring
≥ 9; per-page = `DELIVERY_PER_PAGE` at `ExportScope.Whole`, so a one-page notebook at Whole still
goes to a folder — Whole = folder, deliberately simple), `BundleSplit` (any bundle → v1 one-page
parts in the export cache dir, links dropped, one page in memory), `ExportRender.Outcome.Ready.
pageTitles` (`PageLabels.titleOf` read beside `PageReads.content` in the bake — the names come from
the bake, not a widened `readOnce`; document pages have no titles → `page N`),
`ExportActivity`: `Candidate.delivery`, a second `treeLauncher` (`ACTION_OPEN_DOCUMENT_TREE`, no
persistable grant), `Destination.SafTree` / `CloudFolder`, `exportPerPage` (split → per page:
stem/name/spec → `createDocument` or a cache file → `export` → verdict → upload → next; SHORT
deletes that one document and stops; UNCONFIRMED stops with check-the-file; every stop leads with
*N of M images were exported.*; `lastExporter` only after every file). **Deviation from D1:** the
cloud folder confirmation is asked **once, always, before any work** and names no count
("Each page will be uploaded as its own image. Files with the same names will be replaced." /
Upload) — the names are not known before the bake, and a confirmation after the bake would
interrupt the progress dialog. Strings: `export_exporting_image`, `export_cloud_folder_title/body`,
`export_upload_confirm`, plurals `export_done_images` / `export_cloud_done_images` /
`export_done_images_partial`. `docs/extensions.md` `API_VERSION` row → 9. Tests: `:extension-api`
+1 (230), `:app` +14 (**1501**), `:ext-image` 15 → **2877** total. Both doors offer PNG (phase-start
answer 3). Version stays `0.1.0-ratta`. **Nomad walk (Sonnet over adb, 2026-09-08) PASSED:** library door → 5 PNGs named by heading / page N · repeat → ` (1)` from the provider · page sheet → single-file picker, `Objects - page 5.png`, ` (2)` on the third collision · template off exports (inconclusive on a paper-less notebook) · PDF unchanged · cloud folder → the once-always confirmation → 5 uploads; no crash. The SAF picker turned out adb-drivable with tricks (memory `reference_supernote_documentsui_picker_adb`).

### HV3 — Presets (2026-09-09, Fable brief + review · Opus the row and the screen · Sonnet adb walk)

**Phase-start answers:** version stays `0.1.0-ratta`; *Save preset…* sits under the Preset row in the
panel; a stale cloud folder is **applied** and the upload's own failure explains (no verification
on apply). **Outcome.** `ObjectType.EXPORT_PRESET` (additive, identity hash untouched) · `ObjectDao.
allAliveRowsOfType` (the one blob-carrying listing, by name) · `IndexRepository.exportPresets` (a
row `ExportPreset.decode` cannot vouch for is skipped) / `createExportPreset` / `renameExportPreset`
(the only `updatedAt` bump) / `deleteExportPreset` (soft) · `data/export/ExportPreset` (kotlinx,
version 1, `exporter` = package, `values`, `documentSource`, `destination` as a string, `cloudPath`
nullable) · pure `export/ExportPresets` (`listable` over the screen's candidates — installation
**and** scope in one question; `capture`; `apply` with `cloudFallback`; `rowVisible`) ·
`export/ExportPresetRow` (every view and dialog: the caption + None + one radio per preset, the
code-built Save button, `NameDialog` save/rename with `NameRules` + `nameTaken` refusals that keep
the dialog up, the long-press `ActionSheetDialog` Rename · Delete with a confirm, `reload` at every
discovery and `recut` at a Scope flip) · `ExportPanel.choice` grew `onLongPress` ·
`ExportActivity` +~180 lines (the `Host` impl, the `applyingPreset` latch around `handChanged()` at
every hand write, **the cloud folder as screen state** `cloudPath` with a *Folder: …* value row under
the cloud radio opening the shared `browse(onFolder)`, and `listThenExport` — one `CloudClient.list`
behind *Checking the folder…* so the replace question is still asked; not-connected → the Connect
offer, network → problem, anything else → proceed with an empty listing) · layout `@id/presets`
above `@id/scope` · nineteen strings · `docs/export.md` § Presets + six failure rows. **Fable's one
review change over Opus:** a Scope flip is *not* a hand change — the armed preset stays armed unless
the new scope hides its exporter (`recut`). Tests: `:app` 1512 → **1538** (`ExportPresetTest` 8,
`ExportPresetsTest` 9, `ExportPresetStoreTest` 7 over `FakeObjectDao`, `ExportDestinationTest` +2),
**2914** total. Version stays `0.1.0-ratta`. **Nomad walk (Sonnet over adb, 2026-09-09) PASSED 11/11:** clean screen shows only *Save preset…* ·
"drive" saved (PDF, template off, Drive folder `Exports › Walk` via the browser's *Save here*) → toast,
row ticked · hand change → None, re-apply restores toggle / destination / folder exactly · duplicate
"drive" refused with the name dialog kept · "soil" saved (Keep encrypted, local) · long-press → Rename…
→ "drive2" · reopen: both persist, None ticked · apply "drive2" + Export → browser skipped → *Exported
/ Your notebook was exported to Google Drive* (the *Checking the folder…* stage too brief to capture; no
same-named file so no replace question) · page-sheet door: "soil" hidden at This page, back at Whole,
"drive2" stays ticked across both flips, "soil" armed at Whole → This page → None · Delete… with the
confirm, both gone, the caption and radios with them, *Save preset…* stays · crash log empty. Walk
notes: presets are library-wide (by design — a preset is an answer about *how*, not *which*
notebook); the page sheet is a long press on the canvas (arc-8 door, unchanged).

### HV4 — Calendar render + file export (2026-09-09, Fable seam + `:ext-calendar` render + review · Opus the host · Sonnet adb walk)

**Outcome.** **Seam (API 9, no floor moved):** `ICalendar` grew two appended methods after `end()` —
`render(store, targets[], widthPx, heightPx, flags, destination fd)` (bind-per-call, the store lent
for the call; one `PageBundle` v1, one page per target in order; `out` is an AIDL keyword, hence
`destination`) and `outgoingTarget()` (the parked whole-page send's or export request's page; null
after a selection send). `ExtensionContract`: `EXTRA_CALENDAR_EXPORT_ENABLED` (the Intent's fourth
boolean), `RESULT_CALENDAR_EXPORT = 3`, `MIN_API_VERSION_FOR_CALENDAR_RENDER = 9` (a method floor —
`MIN_API_VERSIONS` untouched, the contract test pins that a 7 still binds), `RENDER_GRID/INK/RING/
MARKS` + `RENDER_ALL`, `RENDER_MAX_TARGETS = 8`, `CALENDAR_RENDER_TIMEOUT_MS`. **`:ext-calendar`
(manifest 7 → 9):** pure `RenderRequest` (the refusals + `pageSize`: stored else the host's, a stored
size over `MAX_DIMENSION_PX` refused), `CalendarRender` (per target: `CalendarStore.open()` on the
lent binder → `readPage`/`readHeader` → white RGB_565 → `CalendarTemplate` by flag with `today`
made **nullable** (null rings nothing — HV5's paper) and `EventStore.marksFor(GridMarks.rangeOf)` by
flag → g-paper's public `StrokeRasterizer.draw` for the ink (the same door `ExportRender.bakeEndnote`
uses — no stroke painter of our own) → WEBP q100 → `PageBundle.Writer`; every non-argument failure
→ `IllegalStateException("render failed")`, the store → `"store unavailable"`); `CalendarService.
render` / `outgoingTarget`; `CalendarSession.outboundTarget` + `parkTarget` (cleared by `end`;
`InkTransferSession.clear` made `open`); `InkScreenActivity.parkOutgoing` grew a `wholePage`
overload the calendar overrides (the pad keeps its three-argument call); the **out-door** button
(`CalendarToolbar` picks the face: Send · Export `ic_download` · the `ActionSheetDialog` Send page /
Export… — `exportPage()` parks `document.target` and `exit(RESULT_CALENDAR_EXPORT)`); three strings.
**Deviation from D4's "insets 0":** the first walk showed the ink one bar-height low against a
full-page grid — the ink was written against the grid the *screen* drew, under the top bar — so the
render uses **the screen's bar insets** (`CalendarBars`: `toolbar_bar_thickness` + the new
`calendar_bar_rule` dimen the layout's two hairlines now reference); the exported page carries blank
bands where the bars were. **Host (Opus):** `HeldInkPoint.outgoingTarget` / `HeldInkClient.
outgoingTarget()`; `ExtensionScreenEntry` `resultExport` + `onExport` (the target read on the held
bind before `finish()`, the drain's shape; nothing back → "Export didn't start"), `decorateIntent`
handed the `ProviderRef`; `CalendarEntry` sets the extra when the calendar declares ≥ 9 **and any
exporter is installed** (the arc-30 door's rule — the screen handles "nothing takes pages");
`CalendarClient.render` bind-per-call with **`ExtensionStores.lease`** (new — replaced three of the
four `openStore` copies: `TagClient`, `CloudClient`, `CloudConnectClient`; `HeldInkClient.open`'s
inlined copy left, its log wording differs); host `export/CalendarRender` (the fourth producer:
`calendar.pages` in the export cache dir, re-read whole through `PageBundle.Reader` — page count,
every page, no links — before a byte is trusted); pure `CalendarRenderPlan` (Day → AM then PM,
flags, stems, label) + `ExportNaming.calendarStem` (`Calendar - September 2026` · `Calendar - Week
of 2026-09-06` · `Calendar - 2026-09-08`, ` AM`/` PM` per page under per-page delivery);
`ExportScope.Calendar(target)` (`lists` = `SOURCE_PAGES` only; `ExportDelivery.perPage` widened to a
Calendar scope with more than one page — a Day as PNG is a folder, a Month as PNG one file);
`ExportActivity` calendar mode (`EXTRA_CALENDAR_TARGET` "kind/date/half", host-internal; no notebook,
**no `.soil` opened**, no Scope/Source rows, header "Calendar · September 2026", `renderedCalendarPages`
as `runExport`'s first branch, `stemFor`, "The calendar was exported."; the calendar gone under it
→ a dialog and close); both doors (`LibraryActivity`, `NotebookActivity`) `onExport` → Export +
`reopenCalendarAfterExport` consumed in `onResume` (process death loses it — the calendar simply
stays closed); seven strings. **Measured on the Nomad:** Month with 46 strokes + ring + marks
**1012 ms** (client 1065 ms, 41 KB bundle); a Day pair with no ink **1600 ms** (1724 ms, 60 KB) —
`CALENDAR_RENDER_TIMEOUT_MS` set to **30 s** (8 targets < 10 s, ×3 for a cold store), down from the
export timeout's 120 s. `docs/extensions.md`'s `API_VERSION` row: the calendar declares 9. Tests:
`:extension-api` +1 (231), `:ext-calendar` +4 (295: `RenderRequestTest`, `CalendarSessionTargetTest`),
`:app` +22 (**1560**: `CalendarRenderPlanTest` 13, naming +4, scope +4, delivery +3) → **2941**.
Version stays `0.1.0-ratta`. **Nomad walk (Sonnet over adb, 2026-09-09) PASSED 9/9:** library door →
"Export page" download button, no separate Send · calendar closes, Export in calendar mode (PNG + PDF
only, no Scope/Source, template toggle) · Month PDF via SAF (129 556 B) → "Exported / The calendar
was exported." → the calendar reopens at its bookmark · the PDF's page: grid, ring on the 9th, glyphs
on 1/3/8/9, the ink · Day → PNG → the folder pick → "Exporting image 1 of 2…" → "2 images were
exported" → `Calendar - 2026-09-09 AM.png` / ` PM.png`, the AM page with "Dentist" at 11:00 · template
off → one PDF, white ground (no ink on that day) · Back without exporting → reopen · notebook door →
"Send or export page" → the two-row sheet → Export… → Back → the calendar over the notebook · crash
log empty. **Second pass after the inset fix (Sonnet, 2026-09-09) PASSED:** the Month PDF's page opens with a 133 px white band, the Sun–Sat header at row 133, and "Cherie's Dad" inside the 13th's cell; render 972 ms; crash log empty. Not walked: the
cloud leg for a calendar (the destination row is unchanged and HV1 walked the N-file upload).

### HV2 — Save as template (2026-09-08, Fable brief + review · Opus the flow · Sonnet adb walk)

**Outcome.** The page sheet's **eighth row, last, `ic_photo_plus`** (already in `:sn-screen` — no new
icon), absent when the page row has no usable size. `notebook/SaveAsTemplateFlow` (out of
`NotebookActivity`, which grew one field and one `addAction`): inside `runPageOp`, `store.drain()`
→ **`notebook/PageRaster`** — `ExportRender`'s private `bakePage` / `decodeTemplate` /
`templatePaint` moved out verbatim and the bake repointed, so a template made from a page is the
picture the page exports as — → `TemplateImport.overCap` (the import's TooBig dialog, before any
question) → `NameDialog` seeded by **`TemplateSeedName.of(title, n)`** (pure: the topmost heading
reduced to `NameRules.CHARSET`, spaces collapsed, capped at `MAX_TITLE_CHARS`, else `page N`; never a
seed the dialog would refuse — Fable's review fix over the brief) with confirm "Next" →
`FolderPickerActivity.pickIntent` grown with `browseFolderType` / `rootLabel` / **`PickVerb`**
(`IMPORT` default keeps the import door untouched; `SAVE_TEMPLATE` = "Save to…" / "Save here", root
"Templates") → reserved-name and `nameTaken` checks, each **re-asking the name in the same folder**
(the refusal dialog sits on the re-opened name dialog, the import's look) → `createTemplate(KIND_IMAGE,
TemplateFit.FIT, bytes)` → toast `template_saved`. Bytes and name are flow fields, never instance
state; a rebuilt screen gets "Saving was interrupted". No `.soil` write, no undo, no recents, no
cover. Strings: ten (`save_as_template_action` … `template_save_interrupted_body`). Tests: `:app`
+11 (`TemplateSeedNameTest`) → **1512**, **2888** total; `PageRaster` is Android-bound (no JVM test,
said in its KDoc). Version stays `0.1.0-ratta`. **Nomad walk (Sonnet over adb, 2026-09-08) PASSED:**
row last after Export page · seed `page 5` on a heading-less last page · picker chrome "Save to…" /
"Templates" / "Save here" · saved (12 018 B, logcat) · Templates screen shows the card with the page's
ink as its thumbnail · duplicate refused ("already exists here") with the name dialog underneath and
the folder kept · cancel at the name and cancel at the picker write nothing · no crash. The
reserved-name refusal was not walked (needs typing; the check is the import's line, shared). Second
pass: a real Heading object on the Objects notebook's page 3 seeded `Heading` (a bold Markdown *text*
object on the Sample notebook seeded `page 1` — texts are not headings, as designed) · saved (34 264 B)
· the new-notebook screen's inline template picker offered the card → the new notebook's first page
wore the saved page (heading, link, stars, line, scribble, sticky icon) as its paper and the library
cover matched. Not walked: the reserved-name refusal and the > 6 MiB TooBig dialog (both are the
import's own lines, shared verbatim).

