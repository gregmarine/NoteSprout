# PARITY_BACKLOG.md — the og-parity work still wanted in Notesprout SN (branch `ratta`)

**What this file is.** Arcs 1–27 are complete and frozen (arc 26 closed item 1 below, arc 27 item 2). On 2026-09-05 the user asked for a gap
review of `apps/notesprout_android` (og Notesprout) against `apps/notesprout_ratta` (Notesprout SN)
before declaring the ratta effort a success. That review found more gaps than the user wants to
close; **this file holds the seven he chose**, in the order he named them.

**This file is a backlog, not a plan.** Each effort below gets its own plan document and its own
wizard when it is picked up — the same way arc 25 got the standalone `DRIVE_PLAN.md`. What is
written here is *what* and *why*, plus the user's own directives and the traps already known. The
*how* is deliberately absent.

**Read before starting any of them:** the root `CLAUDE.md`, `apps/notesprout_ratta/CLAUDE.md`, and
`RATTA_PLAN.md` (working protocol, model recipe, standing traps). The standing rule that
**SN has EIGHT extension points and no NINTH may be added without an explicit user decision**
binds every item here.

**Status:** item 1 **DONE** (arc 26 "Keys", U1–U7 landed 2026-09-05, complete + frozen; the
reference is `docs/encryption.md`, the plan and ledger `ENCRYPTION_PLAN.md`). Item 2 **DONE** (arc 27
"Restore", L1–L6 landed 2026-09-05/06, complete + frozen; the reference is `docs/restore.md`, the plan
and ledger `RESTORE_PLAN.md`). Item 3 **DONE** (arc 28 "Objects", H1–H7 landed 2026-09-06, complete + frozen; the reference is
`docs/objects.md`, the plan and ledger `OBJECTS_PLAN.md`). Item 4 **DONE** (arc 29 "Loop", LE1–LE4
landed 2026-09-06/07, complete + frozen; the reference is `docs/notebook.md` § Toolbar + § Undo, the
plan and ledger `LOOP_PLAN.md`). Item 5 **DONE** (arc 30 "Page", PE1–PE3 landed 2026-09-08, complete +
frozen; the reference is `docs/notebook.md` § Erase page / § Export page + `docs/export.md` § Scope, the
plan and ledger `PAGE_PLAN.md`). Item 6 **IN PROGRESS** (arc 31 "Harvest", wizard locked 2026-09-08; the
plan and ledger is the standalone `HARVEST_PLAN.md` — read it, not `RATTA_PLAN.md`). Item 7 not started.

---

## 1. Full encryption implementation, matching og — ✅ DONE (arc 26 "Keys", 2026-09-05)

**Closed 2026-09-05.** Everything below landed as arc 26 "Keys" (phases U1–U7, standalone
`ENCRYPTION_PLAN.md` with the per-phase ledger); **`docs/encryption.md` is the reference**. Built: the
Encryption screen behind the library's lock button (Reveal / Change passphrase / Forget), `SoilRekey`
+ its interrupted-commit recovery, the journaled `GlobalRotation` with three resume paths and
quarantine, per-notebook scope (`KeyScope` / `KeyResolver` / `NotebookPassphrasePrompt` / lock card /
the four doors / the import chooser), `NotebookRecovery`, and the raw-path audit (`peekVerified`) that
stands in for og's `SelfHealingKeyFactory`. The text that follows is the gap review as it stood before
the arc, kept for the record.

**User's call:** "Let's do a full encryption implementation that closely matches og."

**Where SN is today.** Encrypt-by-default under one global key, auto-minted at first launch as the
user's recovery key (`crypto/GlobalKey`), cached in the Keystore (`crypto/PassphraseStore`),
`crypto/AttemptLimiter` on import unlock, and export/import keying (`crypto/ExportKeying` /
`ImportKeying`). `bootstrap/RecoveryKeyActivity` shows the key **once**, on first launch;
`bootstrap/UnlockActivity` takes it back after a reinstall or restore.

**What is missing.** SN has no encryption UI at all beyond those two bootstrap screens.

- **No way to see the recovery key after first launch.** "Show recovery key" exists only in the
  debug menu (`app/src/debug/.../library/DebugMenu.kt`). In a release build the one secret that
  opens the library is displayed once and never again. og's `EncryptionSettingsActivity` is the
  reference.
- **No key rotation / change passphrase.** og has `crypto/GlobalRotation`; SN's
  `crypto/KeyMaterial` carries a comment reading "future rotation" and nothing else. A recovery key
  that leaks cannot be replaced today.
- **No encryption settings screen**, and no door to one — og's library overflow carries
  Encryption + HWR; SN's bottom bar carries Backup / Import / Templates only.
- **No per-notebook encryption scope.** og has `crypto/KeyScope`, `KeyResolver` and
  `NotebookRecovery`: a notebook may carry its own passphrase, with lock badges in the library. SN
  is global-key-only.
- **No self-healing stale raw key** (og `crypto/SelfHealingKeyFactory`) — a data-loss defense og
  added deliberately (`docs/encryption.md` § data-loss defense).

**Why it is first-tier.** Together with effort 2, this is the only pair in this backlog whose
absence can *lose data permanently*: lose the device and the recovery key and every backup,
local and cloud, is unopenable, with no in-app path to have written the key down in the first place.

**Known scope questions for the plan.** Whether per-notebook scope comes with it or is split off;
what rotation means for the extension stores (`Garden/<pkg>.db`) and for backups already written
under the old key; whether og's `SoilMigrator` has any SN equivalent (SN was born encrypted, so
probably not). Reference: og `docs/encryption.md`, `crypto/*`, `EncryptionSettingsActivity`.

---

## 2. Restore — ✅ DONE (arc 27 "Restore", L1–L6 landed 2026-09-05/06)

**The reference is `docs/restore.md`; the plan and ledger `RESTORE_PLAN.md`** — read those, not this
section. What landed: both legs (local SAF **and** cloud), replace-all with a rename-only aside-swap
and the installed index as the commit marker, no undo, the staged index proved openable before any
commit (cached global silently, then a prompt under `AttemptLimiter("RESTORE")`), orphans in the
folder skipped and named, and **the backup destination treated as device-local state that a restore
never rewrites** — the user's directive below, made whole as decision 3 and walked on both legs. No
code review in the arc; L5 was a failure-injection pass (nine faults, every one walked on the
Nomad). The text that follows is the gap review as it stood before the wizard, kept for the record.

**User's call:** "Need restore for sure."

**Where SN is today.** SN backs up — local SAF and, since arc 25 / V4, a cloud leg — and has **no
restore path of any kind**. A single notebook comes back through arc 16's Import, because every
backup file is a self-describing `.soil`. Nothing else does: the index, and every extension store
(tags, calendar, scratch pad, document prefs), come back only by a hand copy of
`Garden/<pkg>.db` over adb with the app closed, which `docs/backup.md` § Extension stores
documents. og has `data/backup/RestoreEngine` — staging-first, aside-swap, replace-all, restart
into unlock.

**The user's specific directive — the backup-folder trap.**

> In og, the restore sets the [backup] folder based on what folder was being restored. I think if
> the folder was already setup in the backup settings, a restore shouldn't override that setting.
> We can allow restore from any backup folder while maintaining that setting.

**The incident this comes from:** restoring a BOOX backup onto a Supernote silently flipped the
Supernote's configured backup folder to the BOOX's, and it went unnoticed for several backup runs
— so the original BOOX backup was overwritten by the Supernote's. Undesired, and a real data loss.

So: **restoring from a folder must never rewrite the configured backup destination.** Reading a
backup and writing one are two different questions and the answer to the first must not silently
answer the second. Restore-from-anywhere stays allowed.

**Known scope questions for the plan.** Everything arc 17 and arc 21 / W5 named and left (both are
already ledgered in the monorepo `BACKLOG.md`): the aside-swap ordering; what "replace all" means
against a library that has moved on since the backup; and the cross-device case — a store's
ciphertext is keyed to the device that wrote it, so a restore across devices needs the source
device's recovery key, exactly as an encrypted import does. That last one ties this effort to
effort 1. Also open: whether the cloud leg gets a restore too, or only local. Reference: og
`docs/backup.md`, `data/backup/RestoreEngine.kt`, `RestoreSource.kt`, `SafBackupReader.kt`.

**All of the above are answered in `RESTORE_PLAN.md` § Decisions** (2026-09-05): the cloud leg **does**
get a restore (`ICloudStorage` already has `list` + `download`, so no contract change); "replace all"
means the whole library, index and extension stores included, with the aside-swap and the installed
index as the commit marker; and the cross-device key case is met **before** the commit rather than
after it — the staged index must open under a key the user can supply, or nothing live is touched.

---

## 3. Content objects — sticky notes, text, some shapes — ✅ DONE (arc 28 "Objects", H1–H7 landed 2026-09-06)

**The reference is `docs/objects.md`; the plan and ledger `OBJECTS_PLAN.md`** — read those, not this
section. What landed, all **core** on the arc-3 heading pattern: three additive row types on the
universal table (`text` / `shape` / `sticky_note`, no `SOIL_VERSION` bump, no new column), the
Insert bar (Sticky · Text · Rectangle · Ellipse · Triangle · Line · Arrow · Star), text objects
created by lasso-bar recognition **or** Insert-and-type and edited in a plain Markdown box, six
hand-placed shapes with a g-paper **transform mode** (handles + rotate knob + aspect lock, g-paper
0.1.27), sticky notes with a core `StickyEditorActivity` on its own paper surface writing through the
notebook's `SoilWriter` (no ninth point — the seam question below was answered *in-process*), full
in-notebook parity (lasso / move / erase / undo / clipboard / page copy / link-wrap) with **no
extension transfers** (Pad / Calendar hide), and PDF endnotes over a backward-readable `PageBundle`
v2 (no `API_VERSION` bump). Line objects and the dwell shape recognizer stayed out, as decided. The
arc-range code review was waived by the user at H6. The text that follows is the gap review as it
stood before the wizard, kept for the record.

**User's call:** "We will implement sticky notes, text, and perhaps some of the shapes. **We will
not implement the smart shape feature** — that never worked well."

**Where SN is today.** An SN page carries **strokes, headings and links**. That is the whole object
catalog. Headings are core (not an extension) by the arc-3 decision, and that is the shape any new
content object should follow.

Three sub-efforts, each likely its own phase or its own arc:

- **Sticky notes.** og's model is two coordinate spaces — an on-page icon plus a separate editor
  canvas — with tap-to-open, lasso/undo parity, scratch-pad parity, and a PDF footnote/endnote
  export treatment. SN has no editor-in-another-process precedent for a *paper* surface other than
  the scratch pad and the calendar, so "whose seam does the editor live behind" is a real question
  for the plan, and one that must not reach for a ninth extension point without a decision.
  Reference: og `docs/sticky-notes.md`.
- **Text objects.** og's on-page text object renders Markdown in place and is edited through
  `TextEditDialog`. SN already has the `:markdown` module (parser, renderer, formatter, reflow) —
  the engine exists; the page-level object does not. Reference: og `docs/content-objects.md`.
- **Shapes — hand-placed only.** og has a `ShapeObject` with an oriented box, transform mode, an
  aspect / circle-oval toggle, and lasso / clipboard / erase / export parity. **The dwell-triggered
  shape *recognizer* is explicitly out of scope** (it is already disabled in og, and the user's
  judgment is that it never worked well). "Perhaps some of the shapes" — which ones is a wizard
  question, not a decision made here. Reference: og `docs/shape-objects.md`.

**What every one of them drags along** (the arc-3 heading precedent is the checklist): a `.soil`
row type and its pure mapper, a renderer in the committed layer, selection / lasso / move,
undo actions, clipboard capture and paste, cross-notebook page copy, erase, Contents where it
applies, and export parity in all three exporters.

Not included, per the user: og's line objects.

---

## 4. Lasso eraser — ✅ DONE (arc 29 "Loop", LE1–LE4 landed 2026-09-06/07)

**The reference is `docs/notebook.md` (§ Toolbar — fixed tools, § Undo / redo, § Frame-silence
rule) with pointers in `docs/scratchpad.md`, `docs/calendar.md`, `docs/objects.md` and
`docs/sn-screen.md`; the plan and ledger `LOOP_PLAN.md`** — read those, not this section. What
landed: the tool lives in the **engine** — `Tool.LASSO_ERASER` in g-paper **0.1.28** (SN re-pinned
from 0.1.27), the lasso's own outline capture completed as an erase on the lasso's own hit rule
(`LassoHitTest`, touch semantics — so select-then-Delete and lasso-erase always agree), no selection
box ever, the Supernote x-trail (`SupernoteInk.Pen.CROSS`) retracted by the proven trace ladder, one
`PaperListener.onLassoErased(strokeIds, contentIds)` with a forwarding default. It is armed on **all
four paper surfaces** (notebook, sticky editor, scratch pad, calendar) from **a second tap on the
armed eraser** — a Point · Lasso sub-bar (`EraserBar` in `:sn-screen`, `AnchoredBar` moved there
with it) — not a fourth bar button, because a twelfth 62 dp button does not fit the Nomad's 749 dp
with every extension installed. The notebook mirrors it as `NotebookUndo.Action.LassoErased`
(`ScribbleErased`'s shape, its own kind) through `EraseKind { ERASER, SCRIBBLE, LASSO }`; the sticky
editor records its point-eraser body; the pad and the calendar record `InkAction.Erased` with the
whole sub-bar lifecycle living once in `:ext-ink` `InkScreenActivity`. No point, no `API_VERSION`
bump, no new row, no code review (the user's call); version stays `0.1.0-ratta`; tests 1472 `:app` /
2832 across the modules. The text that follows is the gap review as it stood before the wizard, kept
for the record.

**User's call:** "We will want lasso eraser."

**Where SN is today.** Two erase paths: the point eraser (15 px, fixed) and scribble-erase, which
is hardwired on. og carries a third as its own armable tool — `lassoEraser` in
`notebook/ToolbarButtonRegistry` — which deletes everything inside a drawn loop.

Smallest effort on this list, and the one whose blast radius is best understood: it is the existing
lasso geometry pointed at the existing erase path, plus one toolbar button and one undo action
kind. The real questions are chrome — SN's notebook top bar is fixed and already carries nine
buttons — and whether it also lands in the scratch pad and the calendar, since **the pad's tools
are the notebook's, fixed**, and a pad that erased differently one tap from the notebook would read
as a bug (`apps/notesprout_ratta/CLAUDE.md`, `docs/scratchpad.md`). Shared ink helpers live in
`:ext-ink`, so a change to the ink feel is a change in three places by design.

---

## 5. Page erase and page export — ✅ DONE (arc 30 "Page", PE1–PE3 landed 2026-09-08; reference `docs/notebook.md` § Erase page / § Export page + `docs/export.md` § Scope; ledger `PAGE_PLAN.md`)

**As shipped:** two page-sheet rows. **Erase page** (between Page template and Delete) — confirm →
one soft-delete transaction over `liveDescendantIds` (every kind, the page's document row
included), page row / order / size / template kept, `Action.PageErased(pageId, ids)` replayed by
id, an empty page erases silently. **Export page** (last, only while an exporter is installed) —
close, export, reopen: the notebook closes as for a Recents switch, `ExportActivity` opens seeded to
that page with a host-owned Scope row (This page · Whole notebook — first row, above Format; GONE
from the library door), Soil hidden at page scope, `ExportScope`'s page-id filter ahead of every
render (`ExportSpec` and every exporter untouched), filename `<notebook> - <heading>.<ext>` by the
Contents rule else `<notebook> - page N.<ext>`, and `finish()` relaunches the notebook whatever the
outcome. Host-only, no point, no API bump, no new row, no code review. 1472 → 1487 `:app` / 2832 →
2847 tests. Item 6's page-scope half now rides this seam.

**User's call:** "Page erase and page export are needed."

**Where SN is today.** The notebook's one-finger long-press page sheet offers Copy / Cut / Paste /
Page template / Delete. og also offers **Erase Page** (clear the page's content, keep the page and
its paper) and a per-page **Export** — both moved into og's canvas long-press "Page" menu.

- **Erase page** is a content-only wipe: soft-delete every live object on the page, keep the page
  row, its `order`, its size and its template. One transaction, one undo action, and it must be
  type-agnostic the way `deleteCurrent()` already is, so it takes strokes, headings, links — and
  every object type effort 3 adds.
- **Page export** is the notebook's Export reachable at page scope. It overlaps effort 6's page
  scope and export presets; whether the two ship together is a plan question. SN's `ExportActivity`
  today is whole-notebook only and knows no page scope at all.

---

## 6. Export and import extras — 🔄 IN PROGRESS (arc 31 "Harvest", wizard locked 2026-09-08; plan and ledger `HARVEST_PLAN.md`)

**Locked shape (the eight wizard answers, all in `HARVEST_PLAN.md`):** `:ext-image` "NSE · Image Export" (PNG, one file per page into a folder at whole scope) · page-sheet row **Save as template** (name → folder picker, fit pinned) · presets as an additive `export_preset` index row behind a radio row · `ICalendar.render` (`API_VERSION` 8 → 9, not a ninth point) feeding the Export screen's calendar mode and a **new papered page** on a whole-page send. Six phases HV1–HV6, no code review. The text below is the gap review as it stood before the arc.

**User's call:** "Page-to-template, exporting of pages as images, export presets, and exporting of
calendar pages (both as normal export, and enhancing the existing cal-to-notebook feature to
include the calendar's template)."

**Where SN is today.** `ExportActivity` exports the **whole notebook**, in one of three shapes
(`.soil` via `NSE · Soil Export`, PDF via `NSE · PDF Export`, or the document's text/Markdown via
`NSE · Document`), to a local SAF destination or, since arc 25 / V3, to the cloud. There is no page
scope, no image format, and no preset beyond "the last exporter you used."

Four sub-efforts:

- **Page-to-template.** Turn a page into paper in the template library. SN's paper is identified by
  a **token**, not a kind (`docs/templates.md`), and an imported picture is already `IMG#<8 hex>`
  whose digest covers the fit mode — so a rendered page is close to an existing path, not a new
  one. og reaches this as an export *destination* (`ExportDestination.TEMPLATE`); SN's template
  library has its own SAF import, which may be the more honest door here. A plan question.
- **Pages as images.** og has `ExportFormat.PNG`, raster, honouring the page-template option. In
  SN this is a new exporter (or a new format on `:ext-pdf`'s render path — the host already renders
  a `SOURCE_PAGES` bundle and the extension assembles it, so most of the machinery exists). Whether
  a multi-page image export writes one file per page and how that reaches a single SAF destination
  is the interesting part.
- **Export presets.** og has `data/export/ExportPreset` + `ExportPresetsManager` — named, saved
  combinations of format and options. SN remembers only `exportPrefs.lastExporter`. Note og's own
  rule, worth keeping: page scope is **not** captured by a preset.
- **Calendar export**, in two halves:
  - **Normal export of calendar pages.** No exporter reads the calendar store today: every exporter
    that exists takes a *notebook* as its source, and the calendar's `period`/`page`/`stroke` rows
    live in `Garden/<pkg>.ext.calendar.db`, which no export path opens. The monorepo `BACKLOG.md`
    already poses the shape question and it stands: is this a new `sourceKind` on the existing
    exporter point, or a reason the exporter contract needs to know about a non-notebook source at
    all? Either way it must not become a ninth extension point by accident.
  - **Calendar → notebook carries the calendar's template.** The arc-23 transfer sends *ink* to a
    notebook page and nothing else — a copy through the held bind, no ids, coordinates 1:1. The
    user wants the calendar's own paper (the Month/Week/Day grid) to come with it, so the page it
    lands on looks like the calendar it came from. That means the transfer, or the host on the far
    side of it, has to name paper — which brushes the template token rules above and the "an
    extension writes nothing to disk itself, ever" rule. Worth planning next to page-to-template.

---

## 7. Launch restore — return to the last screen

**User's call:** "Launch restore should go back to whatever screen/view the user last had open."

**Where SN is today.** A cold launch reopens the **last notebook** and nothing else — one of the
three launch sites routed through `LibraryActivity.openNotebook` (`docs/library.md`). Everything
else opens at the library: the calendar (which keeps its own bookmark, but only once you get
there), the scratch pad, the document editor, the templates browser, the backup screen.

og restores the whole **surface stack** — `state/AppStateManager` + `state/SurfaceStack` — so a
cold launch reopens the entire chain of screens that was open, in order, and Back walks back out of
it exactly as it would have.

**What makes this awkward in SN and belongs in the plan.** Half of SN's surfaces are *other
processes* behind `startActivityForResult` with a `HostCallerCheck.enforceActivity` guard on the
far side — the scratch pad, the document editor, the tag manager, the calendar. Reopening one on a
cold launch is not "start an Activity"; it is re-establishing a host-launched showing from a
standing start, and each of those points has its own bind lifecycle. The stack also has to survive
a process kill and refuse to restore into anything that has since been deleted. `IndexGuard` and
`BootstrapActivity`'s ordering bound the whole thing.

---

## Deliberately not in this list

Named in the 2026-09-05 gap review, **and not chosen** — recorded so they are not re-raised as
oversights. Any of them needs a fresh user decision.

| Not chosen | Note |
|---|---|
| Tasks and routines | The user's standing call, restated at this review: intentional, not planned. |
| Today dashboard · the day window (Events/Note/Notebooks/History) · day notes · day history | Already ledgered in the monorepo `BACKLOG.md` as "later extensions, a fresh user decision each time." Each still fits no current extension point. |
| Ink colour, greyscale palette, pen widths, stroke styles | SN's ink is fixed black, 3 px pen / 15 px eraser, no panels — the P1 decision. The single most visible daily difference from og, and left standing. |
| Toolbar customization | og has a full customize layer + overflow manager; SN's bar is fixed. |
| Page Index screen (thumbnails, multi-page select, reorder, bulk copy/move) | SN has Contents (a heading outline) and page gestures. |
| Recognition depth: the `page_text` cache, the recognized-text viewer, RTR, the TrOCR personal engine, an HWR settings screen | SN recognizes for headings, tags and document seeding only. |
| Onboarding | og has `OnboardingActivity`; SN goes straight to the recovery-key screen. |
| Library bulk select (multi-notebook move/delete) | |
| Share destination for exports; manual Compact action | SN compacts at seal and backup time, which is arguably the better shape. |
| Line objects | Dropped from effort 3 by the user; sticky notes, text and some shapes only. |
| Smart shape recognition (dwell trigger) | Explicitly refused — "that never worked well." Disabled in og too. |

**Where SN is ahead of og**, for the record, so no future effort "restores parity" by removing
something: the extension architecture itself (eight points, thirteen modules, extension stores as
real SQLite tables), tags on notebooks and pages, fuzzy search over names **and** tags (og's search
is names-only and unranked by comparison), the full paper/template library with SAF import/export
and its Pinned/Recents/Search shelves, name schemes v2, and the per-page document plus the merged
notebook document.
