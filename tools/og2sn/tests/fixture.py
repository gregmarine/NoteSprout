"""A synthetic OG library (plaintext) exercising every row shape the converter reads: v4+ columnar
rows, legacy JSON rows, legacy zlib(JSON) composites, layers, deleted rows, keyed calendar pages,
scratch pages, events, tasks, the user dictionary and the library templates."""

from __future__ import annotations

import json
import sqlite3
import zlib
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from og2sn import strokes  # noqa: E402

OG_ROW_COLS = """
    id TEXT NOT NULL PRIMARY KEY, parentId TEXT NOT NULL, boundingBox TEXT NOT NULL, "order" INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, type TEXT NOT NULL, data TEXT NOT NULL,
    x REAL, y REAL, width REAL, height REAL, "text" TEXT, color TEXT, strokeWidth REAL, refId TEXT, level INTEGER,
    lineStyle TEXT, orientation TEXT, dotSpacing REAL, shapeType TEXT, centerX REAL, centerY REAL, rotationDeg REAL,
    pointCount INTEGER, contentW REAL, contentH REAL, linkTarget TEXT, chrome TEXT, flags INTEGER, blob BLOB
"""
SOIL_DDL = [
    f"CREATE TABLE notebook ({OG_ROW_COLS}, srcUpdatedAt INTEGER)",
    "CREATE TABLE notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
    "CREATE TABLE undo_redo_state (id INTEGER PRIMARY KEY, payload TEXT)",
    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
]
INDEX_DDL = [
    "CREATE TABLE objects (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, parentId TEXT, "
    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, data TEXT NOT NULL DEFAULT '{}', "
    "pageCount INTEGER, flags INTEGER, keyScope TEXT, lastBackedUpLocal INTEGER, lastBackedUpDrive INTEGER, "
    "width INTEGER, height INTEGER, blob BLOB, refId TEXT, sortOrder INTEGER)",
    f"CREATE TABLE scratchpad ({OG_ROW_COLS})",
    f"CREATE TABLE calendar ({OG_ROW_COLS})",
    "CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, startEpochDay INTEGER NOT NULL, "
    "endEpochDay INTEGER NOT NULL, allDay INTEGER NOT NULL, startMinute INTEGER, endMinute INTEGER, recurring INTEGER NOT NULL, "
    "data TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)",
    "CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, parentId TEXT, type TEXT NOT NULL, title TEXT NOT NULL, state TEXT NOT NULL, "
    "dueEpochDay INTEGER, \"order\" INTEGER NOT NULL DEFAULT 0, seriesId TEXT, seriesIndex INTEGER, seriesAnchorDay INTEGER, "
    "recurFreq TEXT, recurInterval INTEGER, recurWeekdays INTEGER, recurMonthlyMode TEXT, recurEndMode TEXT, recurEndEpochDay INTEGER, "
    "recurEndCount INTEGER, remindAmount INTEGER, remindUnit TEXT, resolvedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)",
    "CREATE TABLE user_dictionary (word TEXT NOT NULL PRIMARY KEY, addedAt INTEGER NOT NULL)",
    "CREATE TABLE notebook_activity (id INTEGER PRIMARY KEY, notebookId TEXT, verb TEXT, at INTEGER)",
    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
]

T0 = 1_700_000_000_000
NB1 = "11111111-1111-4111-8111-111111111111"
NB2 = "22222222-2222-4222-8222-222222222222"
FOLDER = "33333333-3333-4333-8333-333333333333"
SUBFOLDER = "34343434-3434-4343-8343-343434343434"
PAGE1, PAGE2, PAGE3 = "aaaa0001-0000-4000-8000-000000000001", "aaaa0002-0000-4000-8000-000000000002", "aaaa0003-0000-4000-8000-000000000003"
LAYER1, LAYER2, LAYER3, LAYER3B = "bbbb0001-0000-4000-8000-000000000001", "bbbb0002-0000-4000-8000-000000000002", "bbbb0003-0000-4000-8000-000000000003", "bbbb0004-0000-4000-8000-000000000004"
TEMPLATE1 = "cccc0001-0000-4000-8000-000000000001"
LINK1, LINK2, STICKY1, HEAD1, HEAD2, TEXT1, LINE1, SHAPE1, SHAPE2, DOC1, DOCNB = (
    "dddd0001-0000-4000-8000-000000000001", "dddd0002-0000-4000-8000-000000000002", "eeee0001-0000-4000-8000-000000000001",
    "ffff0001-0000-4000-8000-000000000001", "ffff0002-0000-4000-8000-000000000002", "99990001-0000-4000-8000-000000000001",
    "88880001-0000-4000-8000-000000000001", "77770001-0000-4000-8000-000000000001", "77770002-0000-4000-8000-000000000002",
    "66660001-0000-4000-8000-000000000001", "66660002-0000-4000-8000-000000000002",
)
PINNED = "00000000-0000-0000-0000-70696e6e6564"
CAL_ROOT = "00000000-0000-0000-0000-63616c6e6472"
SCRATCH_ROOT = "00000000-0000-0000-0000-736372746368"
LIBTPL = "55550001-0000-4000-8000-000000000001"

PNG_1x1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360f8cfc00000030101"
    "00c9fe92ef0000000049454e44ae426082"
)


def bb(x, y, w, h) -> str:
    return json.dumps({"x": x, "y": y, "width": w, "height": h})


def fmtb(xy, pressure=None):
    pts = strokes.points_from_xy(xy)
    if pressure:
        pts.pressure = list(pressure)
    return strokes.encode(pts)


def _insert(con, table, **cols):
    keys = list(cols)
    q = ", ".join(f'"{k}"' for k in keys)
    con.execute(f'INSERT INTO "{table}" ({q}) VALUES ({",".join("?" for _ in keys)})', [cols[k] for k in keys])


def row(con, table, id, parentId, type, order=0, boundingBox="", data="", created=T0, updated=None, deleted=None, **cols):
    _insert(con, table, id=id, parentId=parentId, boundingBox=boundingBox, order=order, createdAt=created,
            updatedAt=updated or created, deletedAt=deleted, type=type, data=data, **cols)


def build_soil_1(path: Path) -> None:
    """Notebook 1: two pages, two layers on page 1, every content type, a template, documents."""
    con = sqlite3.connect(path)
    for s in SOIL_DDL:
        con.execute(s)
    con.execute("PRAGMA user_version = 5")
    con.execute("INSERT INTO room_master_table VALUES (42, 'ogoldhash')")
    # root (columnar meta: text=title, refId=last page)
    row(con, "notebook", NB1, "", "notebook", text="First", refId=PAGE2)
    row(con, "notebook", TEMPLATE1, NB1, "template", boundingBox=bb(0, 0, 1920, 2560), text="Lined", blob=PNG_1x1)
    # page 1 (columnar: size in boundingBox, template in refId), two layers
    row(con, "notebook", PAGE1, NB1, "page", order=0, boundingBox=bb(0, 0, 1920, 2560), refId=TEMPLATE1)
    row(con, "notebook", LAYER1, PAGE1, "layer", order=0, text="Content", flags=2)
    row(con, "notebook", LAYER2, PAGE1, "layer", order=1, text="Second", flags=2)
    # strokes: columnar format B (with pressure) + legacy JSON
    row(con, "notebook", "s1", LAYER1, "stroke", order=0, color="#000000", strokeWidth=3.0, blob=fmtb([(10, 10), (20, 20), (30, 25)], [0.5, 0.6, 0.7]))
    row(con, "notebook", "s2", LAYER1, "stroke", order=1, data=json.dumps({"color": "#ff0000", "strokeWidth": 2.5, "points": [{"x": 1, "y": 2, "pressure": 0.3, "ts": 5}, {"x": 3, "y": 4, "pressure": 0.4}]}))
    row(con, "notebook", "s3", LAYER2, "stroke", order=0, color="#00FF00", strokeWidth=4.0, blob=fmtb([(100, 100), (110, 120)]))
    row(con, "notebook", "s4", LAYER1, "stroke", order=2, deleted=T0 + 5, color="#000000", strokeWidth=3.0, blob=fmtb([(0, 0), (1, 1)]))
    # recognized heading + fallback heading with child strokes
    row(con, "notebook", HEAD1, LAYER1, "heading", order=3, x=50, y=40, width=800, height=90, text="# Meeting notes", level=2)
    row(con, "notebook", HEAD2, LAYER1, "heading", order=4, x=50, y=200, width=800, height=90, level=1)
    row(con, "notebook", "s5", HEAD2, "stroke", order=0, color="#000000", strokeWidth=3.0, blob=fmtb([(60, 210), (70, 220)]))
    # text object, line, shapes
    row(con, "notebook", TEXT1, LAYER1, "text", order=5, x=50, y=400, width=600, height=200, text="Some **bold** text")
    row(con, "notebook", LINE1, LAYER1, "line", order=6, x=100, y=700, width=800, height=4, lineStyle="DASHED", orientation="HORIZONTAL", strokeWidth=2.0, dotSpacing=0)
    row(con, "notebook", SHAPE1, LAYER1, "shape", order=7, width=200, height=100, strokeWidth=2.0, shapeType="RECTANGLE", centerX=500, centerY=900, rotationDeg=15, pointCount=5, flags=1)
    row(con, "notebook", SHAPE2, LAYER1, "shape", order=8, width=200, height=200, strokeWidth=1.0, shapeType="HEXAGON", centerX=900, centerY=900, rotationDeg=0, pointCount=5, flags=0)
    # link with columnar child rows (a stroke + a heading)
    row(con, "notebook", LINK1, LAYER1, "link", order=9, x=40, y=1000, width=500, height=120,
        linkTarget=json.dumps({"type": "com.notesprout.android.data.LinkTarget.OtherNotebookPage", "notebookId": NB2, "pageId": PAGE3}), chrome="DOTTED_CHEVRON")
    row(con, "notebook", "s6", LINK1, "stroke", order=0, color="#000000", strokeWidth=3.0, blob=fmtb([(50, 1010), (60, 1020)]))
    row(con, "notebook", "h3", LINK1, "heading", order=1, x=50, y=1030, width=400, height=80, text="### Linked", level=3)
    row(con, "notebook", "t2", LINK1, "text", order=2, x=50, y=1100, width=480, height=18, text="Tight text")
    # legacy link: JSON content zlib'd in blob, target/chrome in columns
    legacy_link = {
        "target": {"type": "com.notesprout.android.data.LinkTarget.CurrentNotebookPage", "pageId": PAGE2}, "chrome": "UNDERLINE",
        "strokes": [{"id": "x", "points": [{"x": 1200, "y": 1200}, {"x": 1210, "y": 1210}], "color": "#000000", "strokeWidth": 3.0,
                     "srcPoints": [{"x": 1200, "y": 1200, "pressure": 0.9, "tilt": 0.1}, {"x": 1210, "y": 1210, "pressure": 0.8, "tilt": 0.2}]}],
        "headings": [{"id": "y", "boundingBox": {"left": 1200, "top": 1250, "right": 1500, "bottom": 1300}, "strokes": [], "recognizedText": "Legacy head", "level": 1}],
        "textObjects": [], "lines": [{"id": "z", "boundingBox": {"left": 1200, "top": 1320, "right": 1500, "bottom": 1324}, "style": "SOLID", "orientation": "HORIZONTAL", "strokeWidthDp": 1.0, "dotSpacingDp": 0}],
        "shapes": [{"id": "w", "boundingBox": {"left": 0, "top": 0, "right": 0, "bottom": 0}, "type": "STAR", "centerX": 1400, "centerY": 1400, "width": 100, "height": 100, "rotationDeg": 0, "strokeWidthDp": 2.0, "aspectLocked": True, "pointCount": 6}],
    }
    row(con, "notebook", LINK2, LAYER1, "link", order=10, x=1190, y=1190, width=400, height=300,
        linkTarget=json.dumps(legacy_link["target"]), chrome="UNDERLINE", blob=zlib.compress(json.dumps(legacy_link).encode()))
    # sticky with child rows in LOCAL coords (a stroke + a recognized heading that must be dropped with a warning)
    row(con, "notebook", STICKY1, LAYER1, "sticky_note", order=11, x=1500, y=100, width=120, height=120, contentW=1920, contentH=1200)
    row(con, "notebook", "s7", STICKY1, "stroke", order=0, color="#0000FF", strokeWidth=3.0, blob=fmtb([(5, 5), (15, 15)]))
    row(con, "notebook", "h4", STICKY1, "heading", order=1, x=5, y=50, width=300, height=60, text="In sticky", level=1)
    # documents + page_text
    row(con, "notebook", DOC1, PAGE1, "document", text="# Page one\n\nDraft.", srcUpdatedAt=T0 + 100)
    row(con, "notebook", "pt1", PAGE1, "page_text", data=json.dumps({"lines": ["x"]}))
    row(con, "notebook", DOCNB, NB1, "document", text="# Whole notebook\n\nMerged.")
    # page 2: legacy PageData JSON, one layer with legacy JSON stroke; a deleted page 3
    row(con, "notebook", PAGE2, NB1, "page", order=1, data=json.dumps({"width": 1920, "height": 2560, "template": ""}))
    row(con, "notebook", LAYER3, PAGE2, "layer", order=0, data=json.dumps({"label": "Content", "isLocked": False, "isVisible": True}))
    row(con, "notebook", "s8", LAYER3, "stroke", order=0, boundingBox=bb(0, 0, 10, 10), data=json.dumps({"color": "#000000", "strokeWidth": 3.0, "points": [{"x": 0, "y": 0}, {"x": 10, "y": 10}]}))
    row(con, "notebook", "pdel", NB1, "page", order=2, boundingBox=bb(0, 0, 1920, 2560), refId="", deleted=T0 + 9)
    con.execute("INSERT INTO notebook_meta VALUES (0, ?)", (json.dumps({"formatVersion": 1, "notebookId": NB1, "name": "First", "createdAt": T0, "updatedAt": T0, "encrypted": True, "keyScope": "GLOBAL", "folderPath": []}),))
    con.commit()
    con.close()


def build_soil_2(path: Path) -> None:
    """Notebook 2: a text document with one blank page and a notebook document; legacy root JSON."""
    con = sqlite3.connect(path)
    for s in SOIL_DDL:
        con.execute(s)
    con.execute("PRAGMA user_version = 5")
    row(con, "notebook", NB2, "", "notebook", data=json.dumps({"title": "Second", "last_opened_page": PAGE3}))
    row(con, "notebook", PAGE3, NB2, "page", order=0, boundingBox=bb(0, 0, 1404, 1872), refId="")
    row(con, "notebook", LAYER3B, PAGE3, "layer", order=0, text="Content", flags=2)
    row(con, "notebook", "doc2", NB2, "document", text="Hello text document")
    con.execute("INSERT INTO notebook_meta VALUES (0, ?)", (json.dumps({"formatVersion": 1, "notebookId": NB2, "name": "Second", "createdAt": T0, "updatedAt": T0, "encrypted": True, "keyScope": "GLOBAL", "folderPath": [], "textDocument": True}),))
    con.commit()
    con.close()


def build_index(path: Path) -> None:
    con = sqlite3.connect(path)
    for s in INDEX_DDL:
        con.execute(s)
    con.execute("PRAGMA user_version = 11")
    o = lambda **c: _insert(con, "objects", **c)
    o(id=FOLDER, type="folder", name="Work", parentId=None, createdAt=T0, updatedAt=T0, data="")
    o(id=SUBFOLDER, type="folder", name="Inner", parentId=FOLDER, createdAt=T0, updatedAt=T0, data="")
    o(id=NB1, type="notebook", name="First", parentId=SUBFOLDER, createdAt=T0, updatedAt=T0 + 1, data="", pageCount=2, flags=1, keyScope="GLOBAL", blob=PNG_1x1)
    o(id=NB2, type="notebook", name="Second", parentId=None, createdAt=T0, updatedAt=T0, data="", pageCount=1, flags=1 | 4, keyScope="GLOBAL")
    o(id=NB2 + "-trash", type="notebook", name="Trashed", parentId=None, createdAt=T0, updatedAt=T0, deletedAt=T0 + 1, data="", pageCount=1, flags=1, keyScope="GLOBAL")
    o(id="missing-nb", type="notebook", name="Ghost", parentId=None, createdAt=T0, updatedAt=T0, data="", pageCount=1, flags=1, keyScope="GLOBAL")
    o(id=PINNED, type="list", name="Pinned", parentId=None, createdAt=T0, updatedAt=T0, data="")
    o(id="li1", type="list_item", name="", parentId=PINNED, createdAt=T0, updatedAt=T0, data="", refId=NB1, sortOrder=0)
    o(id="li2", type="list_item", name="", parentId=PINNED, createdAt=T0, updatedAt=T0, data="", refId="missing-nb", sortOrder=1)
    o(id="tf1", type="template_folder", name="Mine", parentId=None, createdAt=T0, updatedAt=T0, data="")
    o(id=LIBTPL, type="template", name="Cornell", parentId="tf1", createdAt=T0, updatedAt=T0, data="", width=1920, height=2560, blob=PNG_1x1)
    o(id="clip", type="clipboard", name="", parentId=None, createdAt=T0, updatedAt=T0, data='{"items":[]}')
    # calendar: month, week, day AM/PM, a day note — each with a layer and a stroke; one shape on the month page
    row(con, "calendar", CAL_ROOT, "", "calendar_root")
    for key in ("cal-month-2026-09", "cal-week-2026-08-30", "cal-day-2026-09-02-AM", "cal-day-2026-09-02-PM", "cal-daynote-2026-09-02", "cal-daynote-2026-09-01"):
        row(con, "calendar", key, CAL_ROOT, "page", boundingBox=bb(0, 0, 1920, 2560), refId="")
        row(con, "calendar", key + "/L", key, "layer", text="Content", flags=2)
        row(con, "calendar", key + "/s", key + "/L", "stroke", order=0, color="#000000", strokeWidth=3.0, blob=fmtb([(1, 1), (2, 2)]))
    row(con, "calendar", "cal-month-2026-09/shape", "cal-month-2026-09/L", "shape", order=1, width=50, height=50, strokeWidth=1.0, shapeType="ELLIPSE", centerX=300, centerY=300, rotationDeg=0, pointCount=5, flags=0)
    row(con, "calendar", "cal-month-2026-09/head", "cal-month-2026-09/L", "heading", order=2, x=0, y=0, width=100, height=20, text="Lost on ink", level=1)
    row(con, "calendar", "cal-daynote-2026-09-02/head", "cal-daynote-2026-09-02/L", "heading", order=1, x=0, y=300, width=400, height=80, text="Kept", level=1)
    # scratch pad: two pages
    row(con, "scratchpad", SCRATCH_ROOT, "", "scratchpad_root")
    for i, pid in enumerate(("sp1", "sp2")):
        row(con, "scratchpad", pid, SCRATCH_ROOT, "page", order=i, boundingBox=bb(0, 0, 1920, 2560))
        row(con, "scratchpad", pid + "/L", pid, "layer", text="Content", flags=2)
        row(con, "scratchpad", pid + "/s", pid + "/L", "stroke", order=0, color="#000000", strokeWidth=3.0, blob=fmtb([(3, 3), (4, 4)]))
    # events
    ev = lambda **c: _insert(con, "events", **c)
    ev(id="ev1", type="BIRTHDAY", title="Mom", startEpochDay=20000, endEpochDay=20000, allDay=1, startMinute=None, endMinute=None, recurring=1,
       data=json.dumps({"recurrence": {"freq": "YEARLY", "interval": 1, "weekdays": [], "monthlyMode": "DAY_OF_MONTH", "endMode": "NEVER", "exceptionDates": [20365]}, "notes": "cake", "reminders": [{"amount": 1, "unit": "WEEKS"}]}),
       createdAt=T0, updatedAt=T0)
    ev(id="ev2", type="MEETING", title="Standup", startEpochDay=20600, endEpochDay=20600, allDay=0, startMinute=540, endMinute=570, recurring=1,
       data=json.dumps({"recurrence": {"freq": "WEEKLY", "interval": 1, "weekdays": [1, 3, 5], "monthlyMode": "DAY_OF_MONTH", "endMode": "COUNT", "endCount": 10}}), createdAt=T0, updatedAt=T0)
    ev(id="ev3", type="OTHER", title="Deleted", startEpochDay=20600, endEpochDay=20601, allDay=1, recurring=0, data="{}", createdAt=T0, updatedAt=T0, deletedAt=T0)
    # tasks
    tk = lambda **c: _insert(con, "tasks", **c)
    tk(id="t1", type="TASK", title="Buy milk", state="NOT_DONE", dueEpochDay=20700, createdAt=T0, updatedAt=T0)
    tk(id="t2", type="TASK", title="Old thing", state="DONE", dueEpochDay=20600, resolvedAt=T0, createdAt=T0, updatedAt=T0)
    tk(id="r1", type="ROUTINE", title="Weekly review", state="NOT_DONE", dueEpochDay=20705, recurFreq="WEEKLY", recurInterval=1, createdAt=T0, updatedAt=T0)
    tk(id="r1s1", parentId="r1", type="TASK", title="Clear inbox", state="DONE", createdAt=T0, updatedAt=T0)
    tk(id="r1s2", parentId="r1", type="TASK", title="Plan week", state="NOT_DONE", createdAt=T0, updatedAt=T0)
    con.execute("INSERT INTO user_dictionary VALUES ('notesprout', ?)", (T0,))
    con.execute("INSERT INTO user_dictionary VALUES ('supernote', ?)", (T0,))
    con.commit()
    con.close()


def build_library(root: Path) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    build_index(root / "notesprout.db")
    garden = root / "Garden"
    garden.mkdir(exist_ok=True)
    build_soil_1(garden / f"{NB1}.soil")
    build_soil_2(garden / f"{NB2}.soil")
    build_soil_2(garden / f"{NB2}-trash.soil")
    return root
