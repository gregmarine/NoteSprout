# og2sn — OG Notesprout → Notesprout SN library converter (plan + ledger)

**Status: WORKING — the real library migrated 2026-09-10 (98 notebooks, 235k strokes, 159 calendar pages, 58 events, 16 pad pages, 91 day notes, 16 tasks) and walked by the user on the Manta.** One-user desktop tool, never app code, never an extension.
Input: an OG library (index + `.soil`s, from a backup folder or pulled off the device). Output: an
**SN backup set** that SN's arc-27 Restore installs whole (index + `.soil`s + extension stores),
flat in one folder, encrypted under one passphrase the Restore screen's key prompt proves.

## Decisions (user, 2026-09-10)

| # | Decision |
|---|---|
| 1 | Desktop converter, Python 3 stdlib + the Homebrew `sqlcipher` CLI. Lives in `tools/og2sn/`, committed once it works. |
| 2 | Day notes (`cal-daynote-YYYY-MM-DD`) → one paper notebook **"Day notes"**, one page per day, a level-2 heading with the date. |
| 3 | Tasks + routines → one **text document** notebook **"Tasks"** (Markdown checklist: open, routines with steps, done). |
| 4 | Every OG notebook is GLOBAL-scope. A NOTEBOOK-scope file is refused by name; no per-file prompt. |
| 5 | Delivery = SN Restore (replace-all) from a local folder on the Manta; the target is the **release** SN package (`com.symmetricalpalmtree.notesproutsn`, installed beside OG on the Manta). `--variant dev` renames the stores for the Dev build. |

## Format facts the code stands on (verified against real files 2026-09-10)

- SN index `notesprout.db`: Room, `user_version` 1, `objects` (14 cols) + `index_objects_parentId_type_deletedAt`,
  `room_master_table(42, 'cd6b27016dcd7da0993ac85347813855')`. Pulled from the Nomad dev library.
- SN `.soil`: Room, `user_version` 1, `notebook` (18 cols) + `idx_notebook_parent_order` + `notebook_meta`,
  `room_master_table(42, '7c05940f6179724b53fb0e038e229953')`. No layers: content rows parent to the page.
- SN extension stores: plain SQLite (not Room), `user_version` 2, `host_schema(0, <ext schema version>)`:
  calendar v2 (`period`/`page`/`stroke`/`state` + `event`/`event_weekday`/`event_exception`/`event_reminder`/`note_stroke`),
  scratchpad v1 (`page`/`stroke`/`state`), document v1 (`prefs`/`word`/`caret`). Stroke `color` is a signed ARGB int, `style` `PEN`.
- Stroke blob codec (format B) is byte-identical on both sides — columnar OG stroke blobs pass through untouched.
- Both apps use stock SQLCipher 4 defaults, so the CLI opens either with the passphrase text.
- Manta: 1920×2560 @ 300 dpi → density 1.875 (OG stores shape/line widths in dp; SN wants px).

## Type map

| OG | SN |
|---|---|
| layer | dropped; children re-parented to the page, z-order kept |
| stroke | stroke (colour text, width px, style PEN, blob as-is; legacy JSON re-encoded) |
| heading (recognized) | heading (`text` = `#`×level + ` ` + text, `flags` = level) |
| heading / text (unrecognized fallback) | its strokes, on the page |
| text | text |
| line | 2-point stroke (dp→px width; dash/dot style lost) |
| shape RECTANGLE/ELLIPSE/TRIANGLE/ARROW/LINE/STAR | shape (`style`, centre in x/y, packed flags) |
| shape DIAMOND/TRAPEZOID/PENTAGON/HEXAGON/ARCH | closed polyline stroke(s) along OG's outline |
| link | link (`L1|chrome|kind|nb|page`; DOTTED_CHEVRON→UNDERLINE) + children re-parented (legacy blob materialised with fresh ids) |
| sticky_note | sticky_note (`flags` = packed contentW/H) + stroke children in LOCAL coords; recognized text inside a sticky is dropped with a warning |
| document | document (`flags` = srcUpdatedAt) |
| page_text, undo_redo_state | dropped |
| index folder/notebook/list/list_item/template/template_folder | same; notebook flags bit0 forced on, bits 1–2 carried, `keyScope` GLOBAL |
| calendar month/week/day pages | calendar store `period(kind 0/1/2, date)` + `page(half)` + strokes (non-ink content → strokes or warning) |
| events | calendar store `event` + child tables (epoch-day → ISO text) |
| scratchpad pages | scratchpad store pages + strokes |
| user_dictionary | document store `word` |
| tasks | "Tasks" text document |
| day notes | "Day notes" notebook |
| notebook_activity, recents, clipboard, backup config, toolbar | dropped |

## Ledger

- 2026-09-10 — plan written; format survey done against real SN files from the Nomad and the OG spec + source.
- 2026-09-10 — built, 15 tests; synthetic proof set restored on the Manta first (Room accepted every
  file). Real run found three things the spec did not say: (1) three OG notebooks were **plaintext**
  (pre-encryption, index flag 0) — the converter now copies an unencrypted input and encrypts it under
  the SN passphrase; (2) OG stores recognized headings **already hash-prefixed** — the converter
  stacked a second prefix; now `heading_text` strips before applying the level; (3) text objects
  wrapped in links lost their last character (SN's font runs a little wider than OG's box) — text in
  links is widened ×1.25 + 16 px and the link grows around its children. Also: OG's trashed notebooks
  are left out (a shipped file with no alive row is an orphan the restore reports). Restore lands the
  set under the same passphrase; the proof and real sets restored replace-all on the Manta.
