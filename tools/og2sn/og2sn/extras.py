"""The two notebooks the converter authors: Day notes (paper) and Tasks (text document)."""

from __future__ import annotations

import datetime as dt
import sqlite3
import uuid

from .content import PAGE, Ctx, Walker, page_layers
from .ogread import Row, Tree, load_table, page_size, table_exists
from .stores import parse_calendar_key


def daynote_pages(og_index: sqlite3.Connection, daynotes: list[Row], ctx: Ctx) -> list[dict]:
    """Pages for `write_fresh_notebook`, one per day-note page, oldest first."""
    if not daynotes:
        return []
    rows = load_table(og_index, "calendar")
    tree = Tree.build(rows)
    walker = Walker(tree, ctx)
    pages = []
    for page in sorted(daynotes, key=lambda p: p["id"]):
        parsed = parse_calendar_key(page["id"])
        if not parsed:
            continue
        _, date, _ = parsed
        w, h = page_size(page)
        if w <= 0 or h <= 0:
            continue
        pid = str(uuid.uuid4())
        content = walker.convert_children(page_layers(tree, page["id"]), pid, PAGE)
        for r in content:
            if r.parentId == pid:
                r.order += 1  # the date heading takes order 0
        pages.append({"id": pid, "width": w, "height": h, "heading": date, "rows": content})
        ctx.bump("daynotes.pages")
    return pages


def _iso(epoch_day) -> str | None:
    if epoch_day is None:
        return None
    try:
        return (dt.date(1970, 1, 1) + dt.timedelta(days=int(epoch_day))).isoformat()
    except (OverflowError, ValueError):
        return None


def _recur_text(r: Row) -> str:
    f = r.get("recurFreq")
    if not f:
        return ""
    n = int(r.get("recurInterval") or 1)
    unit = {"DAILY": "day", "WEEKLY": "week", "MONTHLY": "month", "YEARLY": "year"}.get(f, f.lower())
    s = f"every {n} {unit}s" if n > 1 else f"every {unit}"
    if r.get("recurEndMode") == "UNTIL" and _iso(r.get("recurEndEpochDay")):
        s += f" until {_iso(r.get('recurEndEpochDay'))}"
    elif r.get("recurEndMode") == "COUNT" and r.get("recurEndCount"):
        s += f", {int(r['recurEndCount'])} times"
    return s


def _task_line(r: Row, done_mark: bool = False) -> str:
    box = "[x]" if (done_mark or r.get("state") == "DONE") else ("[-]" if r.get("state") == "SKIPPED" else "[ ]")
    bits = []
    due = _iso(r.get("dueEpochDay"))
    if due:
        bits.append(f"due {due}")
    rec = _recur_text(r)
    if rec:
        bits.append(rec)
    if r.get("remindAmount"):
        unit = "week" if (r.get("remindUnit") or "DAYS") == "WEEKS" else "day"
        n = int(r["remindAmount"])
        bits.append(f"remind {n} {unit}{'s' if n != 1 else ''} before")
    if r.get("resolvedAt") and r.get("state") in ("DONE", "SKIPPED"):
        when = dt.datetime.fromtimestamp(int(r["resolvedAt"]) / 1000).date().isoformat()
        bits.append(f"{'done' if r.get('state') == 'DONE' else 'skipped'} {when}")
    tail = f" ({'; '.join(bits)})" if bits else ""
    return f"- {box} {r.get('title') or ''}{tail}"


def tasks_markdown(og_index: sqlite3.Connection, ctx: Ctx) -> str | None:
    if not table_exists(og_index, "tasks"):
        return None
    rows = [r for r in load_table(og_index, "tasks") if r.get("deletedAt") is None]
    if not rows:
        return None
    by_parent: dict[str, list[Row]] = {}
    for r in rows:
        if r.get("parentId"):
            by_parent.setdefault(r["parentId"], []).append(r)
    standalone = [r for r in rows if r.get("type") == "TASK" and not r.get("parentId")]
    routines = [r for r in rows if r.get("type") == "ROUTINE"]
    key = lambda r: (r.get("dueEpochDay") if r.get("dueEpochDay") is not None else 10**9, r.get("order") or 0, r.get("createdAt") or 0)
    open_tasks = sorted([r for r in standalone if r.get("state") == "NOT_DONE"], key=key)
    done_tasks = sorted([r for r in standalone if r.get("state") != "NOT_DONE"], key=lambda r: -(r.get("resolvedAt") or 0))
    out = ["# Tasks", "", f"Migrated from Notesprout on {dt.date.today().isoformat()}.", ""]
    out += ["## Open", ""] + ([_task_line(r) for r in open_tasks] or ["(none)"]) + [""]
    if routines:
        out += ["## Routines", ""]
        for rt in sorted(routines, key=key):
            head = rt.get("title") or "Routine"
            meta = "; ".join(b for b in (_recur_text(rt), f"due {_iso(rt.get('dueEpochDay'))}" if _iso(rt.get("dueEpochDay")) else "", f"state {rt.get('state', '').lower()}") if b)
            out.append(f"### {head}" + (f" ({meta})" if meta else ""))
            out.append("")
            steps = sorted(by_parent.get(rt["id"], []), key=lambda r: (r.get("order") or 0))
            out += [_task_line(s) for s in steps] or ["(no steps)"]
            out.append("")
    if done_tasks:
        out += ["## Done", ""] + [_task_line(r, done_mark=(r.get("state") == "DONE")) for r in done_tasks] + [""]
    ctx.bump("tasks", len(rows))
    return "\n".join(out).rstrip() + "\n"
