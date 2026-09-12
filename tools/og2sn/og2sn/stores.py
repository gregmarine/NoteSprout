"""The three SN extension stores the converter fills: calendar (ink + events), scratch pad,
document (the user dictionary)."""

from __future__ import annotations

import datetime as dt
import json
import re
import sqlite3
import uuid

from . import snschema, strokes
from .content import INK, Ctx, SnRow, Walker, page_layers
from .ogread import Row, Tree, load_table, page_size, table_exists

CALENDAR_ROOT_ID = "00000000-0000-0000-0000-63616c6e6472"
SCRATCHPAD_ROOT_ID = "00000000-0000-0000-0000-736372746368"

KIND_MONTH, KIND_WEEK, KIND_DAY = 0, 1, 2
KEY_RE = re.compile(r"^cal-(month|week|day|daynote)-(\d{4})-(\d{2})(?:-(\d{2}))?(?:-(AM|PM))?$")

INSERT_STROKE = 'INSERT INTO stroke (id, pageId, "order", color, width, style, blob) VALUES (?,?,?,?,?,?,?)'


def argb_int(color: str | None) -> int:
    """`#RRGGBB` / `#AARRGGBB` → the signed 32-bit ARGB int SN's ink stores keep."""
    c = (color or "#000000").lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    try:
        v = int(c, 16) & 0xFFFFFFFF
    except ValueError:
        v = 0xFF000000
    return v - (1 << 32) if v >= (1 << 31) else v


def _ink_rows(walker: Walker, tree: Tree, page: Row, sn_page_id: str, ctx: Ctx) -> list[tuple]:
    rows = walker.convert_children(page_layers(tree, page["id"]), sn_page_id, INK)
    out = []
    order = 0
    for r in rows:
        if r.type != "stroke" or r.deletedAt is not None:
            continue
        out.append((r.id, sn_page_id, order, argb_int(r.color), float(r.strokeWidth or 3.0), "PEN", r.blob))
        order += 1
    return out


# ── calendar ───────────────────────────────────────────────────────────────

def parse_calendar_key(key: str) -> tuple[str, str, int] | None:
    """`cal-month-YYYY-MM` → ('month', 'YYYY-MM-01', 0); week → Sunday date; day → half 0/1;
    daynote → ('daynote', date, 0). None for anything else."""
    m = KEY_RE.match(key or "")
    if not m:
        return None
    kind, y, mo, d, half = m.groups()
    try:
        if kind == "month":
            return "month", f"{int(y):04d}-{int(mo):02d}-01", 0
        date = dt.date(int(y), int(mo), int(d or 1))
    except ValueError:
        return None
    if kind == "week":
        # OG keys weeks by their Sunday already; normalise in case.
        sunday = date - dt.timedelta(days=(date.weekday() + 1) % 7)
        return "week", sunday.isoformat(), 0
    if kind == "day":
        return "day", date.isoformat(), (1 if half == "PM" else 0)
    return "daynote", date.isoformat(), 0


def convert_calendar_ink(og_index: sqlite3.Connection, con: sqlite3.Connection, ctx: Ctx) -> list[Row]:
    """Writes period/page/stroke rows into the (plaintext) calendar store; returns the day-note pages
    for the Day notes notebook."""
    if not table_exists(og_index, "calendar"):
        return []
    rows = load_table(og_index, "calendar")
    tree = Tree.build(rows)
    walker = Walker(tree, ctx)
    daynotes: list[Row] = []
    periods: dict[tuple[int, str], str] = {}
    kinds = {"month": KIND_MONTH, "week": KIND_WEEK, "day": KIND_DAY}
    for page in tree.children(CALENDAR_ROOT_ID):
        if page.get("type") != "page" or page.get("deletedAt") is not None:
            continue
        parsed = parse_calendar_key(page["id"])
        if not parsed:
            ctx.warn(f"calendar page {page['id']!r}: unknown key, skipped")
            continue
        kind, date, half = parsed
        if kind == "daynote":
            daynotes.append(page)
            continue
        w, h = page_size(page)
        if w <= 0 or h <= 0:
            ctx.warn(f"calendar page {page['id']}: no size, skipped")
            continue
        sn_page = str(uuid.uuid4())
        ink = _ink_rows(walker, tree, page, sn_page, ctx)
        if not ink:
            continue  # SN mints rows on the first stroke; an empty page has no row
        pk = (kinds[kind], date)
        if pk not in periods:
            periods[pk] = str(uuid.uuid4())
            con.execute("INSERT INTO period (id, kind, date) VALUES (?,?,?)", (periods[pk], pk[0], pk[1]))
        con.execute(
            "INSERT INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?)",
            (sn_page, periods[pk], half, w, h, int(page.get("createdAt") or 0), int(page.get("updatedAt") or 0)),
        )
        con.executemany(INSERT_STROKE, ink)
        ctx.bump("calendar.pages")
        ctx.bump("calendar.strokes", len(ink))
    return daynotes


def _iso(epoch_day) -> str | None:
    if epoch_day is None:
        return None
    try:
        return (dt.date(1970, 1, 1) + dt.timedelta(days=int(epoch_day))).isoformat()
    except (OverflowError, ValueError):
        return None


def convert_events(og_index: sqlite3.Connection, con: sqlite3.Connection, ctx: Ctx) -> None:
    if not table_exists(og_index, "events"):
        return
    for e in load_table(og_index, "events"):
        if e.get("deletedAt") is not None:
            continue
        try:
            payload = json.loads(e.get("data") or "{}")
        except ValueError:
            payload = {}
        rule = payload.get("recurrence") if isinstance(payload, dict) else None
        rule = rule if isinstance(rule, dict) else None
        start, end = _iso(e.get("startEpochDay")), _iso(e.get("endEpochDay"))
        if not start:
            ctx.warn(f"event {e.get('title')!r}: no start date, skipped")
            continue
        end = end or start
        if end < start:
            end = start
        freq = (rule or {}).get("freq")
        con.execute(
            "INSERT INTO event (id, type, title, startDate, endDate, allDay, startMinute, endMinute, recurring, freq, interval, "
            "monthlyMode, endMode, untilDate, endCount, noteText, noteWidth, noteHeight, createdAt, updatedAt) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                e["id"], e.get("type") or "OTHER", e.get("title") or "", start, end, 1 if e.get("allDay") else 0,
                e.get("startMinute"), e.get("endMinute"), 1 if rule and freq else 0, freq if rule else None,
                int((rule or {}).get("interval") or 1), (rule or {}).get("monthlyMode") or "DAY_OF_MONTH",
                (rule or {}).get("endMode") or "NEVER", _iso((rule or {}).get("endEpochDay")) if rule else None,
                (rule or {}).get("endCount") if rule else None, str((payload or {}).get("notes") or ""), 0.0, 0.0,
                int(e.get("createdAt") or 0), int(e.get("updatedAt") or 0),
            ),
        )
        if rule:
            for wd in rule.get("weekdays") or []:
                con.execute("INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?,?)", (e["id"], int(wd)))
            for ex in rule.get("exceptionDates") or []:
                d = _iso(ex)
                if d:
                    con.execute("INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?,?)", (e["id"], d))
        for rem in (payload or {}).get("reminders") or []:
            if isinstance(rem, dict) and rem.get("amount") is not None:
                con.execute("INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?,?,?)", (e["id"], int(rem["amount"]), rem.get("unit") or "DAYS"))
        ctx.bump("events")


def build_calendar_store(og_index: sqlite3.Connection, path, ctx: Ctx) -> list[Row]:
    con = snschema.create_plain(path, snschema.CALENDAR_DDL, snschema.STORE_USER_VERSION)
    snschema.stamp_host_schema(con, snschema.CALENDAR_SCHEMA_VERSION)
    daynotes = convert_calendar_ink(og_index, con, ctx)
    convert_events(og_index, con, ctx)
    con.commit()
    con.close()
    return daynotes


# ── scratch pad ────────────────────────────────────────────────────────────

def build_scratch_store(og_index: sqlite3.Connection, path, ctx: Ctx, default_size: tuple[float, float]) -> None:
    con = snschema.create_plain(path, snschema.SCRATCH_DDL, snschema.STORE_USER_VERSION)
    snschema.stamp_host_schema(con, snschema.SCRATCH_SCHEMA_VERSION)
    first_page: str | None = None
    position = 0
    if table_exists(og_index, "scratchpad"):
        rows = load_table(og_index, "scratchpad")
        tree = Tree.build(rows)
        walker = Walker(tree, ctx)
        for page in tree.children(SCRATCHPAD_ROOT_ID):
            if page.get("type") != "page" or page.get("deletedAt") is not None:
                continue
            w, h = page_size(page)
            if w <= 0 or h <= 0:
                w, h = default_size
            sn_page = str(uuid.uuid4())
            con.execute(
                "INSERT INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?,?,?,?,?,?)",
                (sn_page, position, w, h, int(page.get("createdAt") or 0), int(page.get("updatedAt") or 0)),
            )
            ink = _ink_rows(walker, tree, page, sn_page, ctx)
            con.executemany(INSERT_STROKE, ink)
            first_page = first_page or sn_page
            position += 1
            ctx.bump("scratch.pages")
            ctx.bump("scratch.strokes", len(ink))
    if first_page is None:
        first_page = str(uuid.uuid4())
        now = int(dt.datetime.now().timestamp() * 1000)
        con.execute("INSERT INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?,?,?,?,?,?)", (first_page, 0, default_size[0], default_size[1], now, now))
    con.execute("INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)", (first_page,))
    con.commit()
    con.close()


# ── document store (user dictionary) ───────────────────────────────────────

def build_document_store(og_index: sqlite3.Connection, path, ctx: Ctx) -> None:
    con = snschema.create_plain(path, snschema.DOCUMENT_DDL, snschema.STORE_USER_VERSION)
    snschema.stamp_host_schema(con, snschema.DOCUMENT_SCHEMA_VERSION)
    if table_exists(og_index, "user_dictionary"):
        for r in load_table(og_index, "user_dictionary"):
            if r.get("word"):
                con.execute("INSERT OR REPLACE INTO word (word, addedAt) VALUES (?,?)", (r["word"], int(r.get("addedAt") or 0)))
                ctx.bump("words")
    con.commit()
    con.close()
