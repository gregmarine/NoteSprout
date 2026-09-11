# og2sn — OG Notesprout → Notesprout SN

Converts a whole OG Notesprout library (the original Android app) into a **Notesprout SN backup
set** that SN's Restore installs in one go: the index, every notebook, the calendar (ink + events),
the scratch pad, the user dictionary, plus two authored notebooks — **Day notes** (paper, one page
per OG day note) and **Tasks** (a text document with a Markdown checklist).

One-user desktop tool. Python 3.11+ stdlib and the Homebrew `sqlcipher` CLI; no pip packages.
`PLAN.md` holds the decisions, the type map and the format facts it stands on.

```
brew install sqlcipher
cd tools/og2sn
python3 -m unittest tests.test_convert        # 15 tests, incl. an encrypted round trip
```

## Run

1. **Get the OG library onto the Mac.** Either an OG backup folder (local SAF backup: `notesprout.db`
   + `<uuid>.soil`), or the live files pulled off the device with OG **closed**:

   ```
   mkdir -p ~/og-library/Garden
   adb -s SN100C10023972 pull /sdcard/Android/data/com.notesprout.android/files/notesprout.db ~/og-library/
   adb -s SN100C10023972 pull /sdcard/Android/data/com.notesprout.android/files/notesprout.db-wal ~/og-library/
   adb -s SN100C10023972 pull /sdcard/Android/data/com.notesprout.android/files/Garden ~/og-library/
   ```
   (`-wal` sidecars next to a file are applied when it is opened; pull them too.)

2. **Convert.** The OG global passphrase is prompted (or read from a `chmod 600` file); it is never
   put on a command line or in a log. The SN set is encrypted under the same passphrase unless
   `--out-passphrase-file` says otherwise.

   ```
   python3 og2sn.py --in ~/og-library --out ~/sn-migration
   python3 og2sn.py --in ~/og-library --out ~/sn-migration --passphrase-file ~/.og-pass
   ```
   Options: `--variant dev` (store file names for the SN Dev build), `--density` (dp→px of the
   source device; Manta 1.875), `--keep-work` (keep the plaintext work folder for inspection —
   otherwise it is scrubbed).

   The output folder holds `notesprout.db`, one `<uuid>.soil` per notebook, the three
   `com.symmetricalpalmtree.notesproutsn.ext.*.db` stores and `report.txt` (counts + every warning).

3. **Restore in SN.** Push the folder to the device and pick it from SN's Backup → Restore:

   ```
   adb -s SN100C10023972 push ~/sn-migration /sdcard/Download/sn-migration
   ```
   Restore is replace-all: it swaps SN's whole library for the set. When the set's passphrase is not
   the one the SN install already holds, the key prompt asks for it; type the passphrase the set was
   encrypted under.

## Notes from the real run (2026-09-10)

Migrated a 99-notebook OG library end to end; see `PLAN.md`'s ledger. Unencrypted OG notebooks are
accepted and encrypted on the way out; notebooks in OG's trash are left out; headings keep exactly
one `#` prefix; text inside links is widened so SN's font does not wrap its last character.

## What does not carry over

Tasks/routines survive only as text; day notes as ordinary pages; day history, recents, toolbar
customisation, clipboard, backup config, undo state and the recognized-text cache stay behind.
Lines and the five shape kinds SN lacks become ink; recognized text inside a sticky note is dropped
(warned). Multiple content layers on a page collapse into one. Every warning is in `report.txt`.
