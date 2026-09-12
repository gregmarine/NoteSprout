"""og2sn — command line.

  python3 og2sn.py --in <OG library folder> --out <SN backup folder> [--passphrase-file F]
                   [--out-passphrase-file F] [--variant release|dev] [--density 1.875] [--keep-work]
                   [--plain]

The input folder holds `notesprout.db` and `<uuid>.soil` files (an OG backup folder, or the
app's `files/` + `files/Garden/` pulled off the device — `-wal` sidecars next to a file are
honoured). The output folder is an SN backup set: `notesprout.db`, one `.soil` per notebook and
the three extension stores, all encrypted under the output passphrase, plus `report.txt`.

`--plain` reads plaintext inputs and writes plaintext outputs (tests and inspection only).
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import shutil
import sqlite3
import sys
import uuid
from pathlib import Path

from . import cipher, snschema
from .content import Ctx
from .extras import daynote_pages, tasks_markdown
from .index import IndexNotebook, read_plan, write_index
from .notebook import convert_soil, write_fresh_notebook
from .ogread import table_exists
from .stores import build_calendar_store, build_document_store, build_scratch_store

MANTA = (1920.0, 2560.0, 300 / 160.0)


def _read_secret(path: str | None, prompt: str) -> str:
    if path:
        return Path(path).read_text(encoding="utf-8").rstrip("\r\n")
    if not sys.stdin.isatty():
        raise SystemExit(f"{prompt}: no TTY — pass --passphrase-file")
    return getpass.getpass(prompt + ": ")


class Report:
    def __init__(self) -> None:
        self.lines: list[str] = []
        self.warnings: list[str] = []

    def log(self, s: str) -> None:
        self.lines.append(s)
        print(s)

    def warn(self, s: str) -> None:
        self.warnings.append(s)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="og2sn", description="Convert an OG Notesprout library into a Notesprout SN backup set.")
    ap.add_argument("--in", dest="src", required=True, help="OG library folder (notesprout.db + *.soil, optionally under Garden/)")
    ap.add_argument("--out", dest="dst", required=True, help="output folder (created; must be empty)")
    ap.add_argument("--passphrase-file", help="file holding the OG global passphrase (else prompted)")
    ap.add_argument("--out-passphrase-file", help="file holding the passphrase for the SN set (default: same as input)")
    ap.add_argument("--variant", choices=("release", "dev"), default="release", help="SN build the set is for (store file names)")
    ap.add_argument("--density", type=float, default=MANTA[2], help="dp→px factor of the source device (Manta 1.875)")
    ap.add_argument("--page-size", default=f"{int(MANTA[0])}x{int(MANTA[1])}", help="fallback page size WxH px")
    ap.add_argument("--keep-work", action="store_true", help="keep the plaintext work folder (inspection only)")
    ap.add_argument("--plain", action="store_true", help="plaintext in, plaintext out (tests)")
    ap.add_argument("--app-version-code", type=int, default=1)
    args = ap.parse_args(argv)

    src = Path(args.src).expanduser().resolve()
    dst = Path(args.dst).expanduser().resolve()
    if not src.is_dir():
        raise SystemExit(f"--in: not a folder: {src}")
    if dst.exists() and any(dst.iterdir()):
        raise SystemExit(f"--out: folder is not empty: {dst}")
    dst.mkdir(parents=True, exist_ok=True)
    work = dst / "_work"
    work.mkdir()
    pw, ph = (float(v) for v in args.page_size.lower().split("x"))

    rep = Report()
    ctx = Ctx(density=args.density, warn=rep.warn)
    in_pass = out_pass = ""
    if not args.plain:
        in_pass = _read_secret(args.passphrase_file, "OG global passphrase")
        out_pass = _read_secret(args.out_passphrase_file, "SN passphrase") if args.out_passphrase_file else in_pass
        if not in_pass:
            raise SystemExit("empty passphrase")

    def plain_of(path: Path) -> Path:
        """A plaintext copy of an OG file in the work folder (or the file itself under --plain)."""
        if args.plain:
            return path
        out = work / (path.name + ".plain")
        if not cipher.is_encrypted(path):
            # An unencrypted OG file (pre-encryption notebook): copy it with its WAL sidecar so
            # sqlite applies any pending frames on open.
            shutil.copyfile(path, out)
            wal = Path(str(path) + "-wal")
            if wal.exists() and wal.stat().st_size > 0:
                shutil.copyfile(wal, Path(str(out) + "-wal"))
            rep.warn(f"{path.name}: was not encrypted in OG; encrypted under the SN passphrase now")
            return out
        cipher.decrypt_to_plain(path, in_pass, out)
        return out

    def emit(plain: Path, name: str, user_version: int) -> None:
        if args.plain:
            shutil.move(str(plain), str(dst / name))
            return
        cipher.encrypt_from_plain(plain, out_pass, dst / name, user_version)
        cipher.verify(dst / name, out_pass, user_version)

    # ── the OG index ──
    og_index_file = src / "notesprout.db"
    if not og_index_file.exists():
        raise SystemExit(f"no notesprout.db in {src}")
    rep.log(f"Reading OG index {og_index_file}")
    og_index = sqlite3.connect(plain_of(og_index_file))
    if not table_exists(og_index, "objects"):
        raise SystemExit("notesprout.db has no objects table — wrong passphrase or not an OG index")
    plan = read_plan(og_index)
    rep.log(f"  {len(plan.notebooks)} notebooks, {len(plan.folders)} folders, {len(plan.templates)} library templates")

    # ── notebooks ──
    garden = src / "Garden" if (src / "Garden").is_dir() else src
    converted: dict[str, tuple[int, bool]] = {}
    for n in plan.notebooks:
        f = garden / f"{n.id}.soil"
        if n.deleted_at is not None:
            rep.log(f"  · {n.name!r}: in OG's trash, left out")
            continue
        if not f.exists():
            rep.warn(f"notebook {n.name!r} ({n.id}): file missing, left out")
            continue
        if (n.key_scope or "GLOBAL") != "GLOBAL":
            rep.warn(f"notebook {n.name!r} ({n.id}): NOTEBOOK-scope passphrase — refused, left out")
            continue
        try:
            og_nb = sqlite3.connect(plain_of(f))
        except cipher.CipherError as e:
            rep.warn(f"notebook {n.name!r} ({n.id}): could not open ({e}); left out")
            continue
        if not table_exists(og_nb, "notebook"):
            rep.warn(f"notebook {n.name!r} ({n.id}): not a notebook file; left out")
            og_nb.close()
            continue
        plain_out = work / f"{n.id}.soil.out"
        before = dict(ctx.counts)
        res = convert_soil(og_nb, plain_out, n.id, n.name, plan.ancestry(n.parent_id), n.text_document, ctx, args.app_version_code)
        og_nb.close()
        emit(plain_out, f"{n.id}.soil", snschema.SOIL_USER_VERSION)
        converted[n.id] = (res.page_count, res.has_template)
        delta = {k: v - before.get(k, 0) for k, v in ctx.counts.items() if v - before.get(k, 0)}
        rep.log(f"  ✓ {n.name!r}: {res.page_count} pages, {delta.get('strokes', 0)} strokes" + (" (text document)" if n.text_document else ""))

    # ── stores ──
    now = int(dt.datetime.now().timestamp() * 1000)
    cal_plain = work / "calendar.out"
    daynotes = build_calendar_store(og_index, cal_plain, ctx)
    emit(cal_plain, snschema.store_file_name(snschema.EXT_CALENDAR, args.variant), snschema.STORE_USER_VERSION)
    rep.log(f"  calendar: {ctx.counts.get('calendar.pages', 0)} ink pages, {ctx.counts.get('events', 0)} events")

    scratch_plain = work / "scratch.out"
    build_scratch_store(og_index, scratch_plain, ctx, (pw, ph))
    emit(scratch_plain, snschema.store_file_name(snschema.EXT_SCRATCHPAD, args.variant), snschema.STORE_USER_VERSION)
    rep.log(f"  scratch pad: {ctx.counts.get('scratch.pages', 0)} pages")

    doc_plain = work / "document.out"
    build_document_store(og_index, doc_plain, ctx)
    emit(doc_plain, snschema.store_file_name(snschema.EXT_DOCUMENT, args.variant), snschema.STORE_USER_VERSION)
    rep.log(f"  user dictionary: {ctx.counts.get('words', 0)} words")

    # ── authored notebooks ──
    extra: list[IndexNotebook] = []
    pages = daynote_pages(og_index, daynotes, ctx)
    if pages:
        nid = str(uuid.uuid4())
        name = plan.free_name("Day notes")
        out = work / f"{nid}.soil.out"
        res = write_fresh_notebook(out, nid, name, [], pages, False, None, now, args.app_version_code)
        emit(out, f"{nid}.soil", snschema.SOIL_USER_VERSION)
        converted[nid] = (res.page_count, False)
        extra.append(IndexNotebook(nid, name, None, 1, "GLOBAL", None, now, now, res.page_count, None, False))
        rep.log(f"  ✓ {name!r}: {res.page_count} day pages")
    md = tasks_markdown(og_index, ctx)
    if md:
        nid = str(uuid.uuid4())
        name = plan.free_name("Tasks")
        out = work / f"{nid}.soil.out"
        res = write_fresh_notebook(out, nid, name, [], [], True, md, now, args.app_version_code)
        emit(out, f"{nid}.soil", snschema.SOIL_USER_VERSION)
        converted[nid] = (1, False)
        extra.append(IndexNotebook(nid, name, None, 1 | 4, "GLOBAL", None, now, now, 1, None, True))
        rep.log(f"  ✓ {name!r}: text document ({ctx.counts.get('tasks', 0)} task rows)")

    # ── the SN index ──
    idx_plain = work / "index.out"
    write_index(idx_plain, plan, converted, extra, ctx)
    emit(idx_plain, "notesprout.db", snschema.INDEX_USER_VERSION)
    og_index.close()

    # ── report + hygiene ──
    rep.log("")
    rep.log("Counts:")
    for k in sorted(ctx.counts):
        rep.log(f"  {k}: {ctx.counts[k]}")
    if rep.warnings:
        rep.log("")
        rep.log(f"Warnings ({len(rep.warnings)}):")
        for w in rep.warnings:
            rep.log(f"  - {w}")
    (dst / "report.txt").write_text("\n".join(rep.lines) + "\n", encoding="utf-8")
    if not args.keep_work:
        for p in work.iterdir():
            cipher.scrub(p)
        shutil.rmtree(work, ignore_errors=True)
    rep.log("")
    rep.log(f"Done → {dst}")
    return 0
