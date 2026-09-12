"""The SN side's exact DDL, copied from real files the SN app wrote (Nomad dev library,
2026-09-10), plus the Room identity hashes those files carry. Room compares the hash on open;
a matching hash means no schema validation ever runs, so this must be byte-exact."""

from __future__ import annotations

import sqlite3
from pathlib import Path

INDEX_USER_VERSION = 1
SOIL_USER_VERSION = 1
STORE_USER_VERSION = 2  # StoreFormat.VERSION — the table-store file format

INDEX_IDENTITY_HASH = "cd6b27016dcd7da0993ac85347813855"
SOIL_IDENTITY_HASH = "7c05940f6179724b53fb0e038e229953"

ROOM_MASTER = "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"

INDEX_DDL = [
    "CREATE TABLE `objects` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, "
    "`parentId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, "
    "`pageCount` INTEGER, `flags` INTEGER, `keyScope` TEXT, `templateKind` TEXT, `blob` BLOB, "
    "`refId` TEXT, `sortOrder` INTEGER, PRIMARY KEY(`id`))",
    ROOM_MASTER,
    "CREATE INDEX `index_objects_parentId_type_deletedAt` ON `objects` (`parentId`, `type`, `deletedAt`)",
]

SOIL_DDL = [
    "CREATE TABLE `notebook` (`id` TEXT NOT NULL, `parentId` TEXT NOT NULL, `type` TEXT NOT NULL, "
    "`order` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, "
    "`deletedAt` INTEGER, `text` TEXT, `refId` TEXT, `x` REAL, `y` REAL, `width` REAL, `height` REAL, "
    "`color` TEXT, `strokeWidth` REAL, `style` TEXT, `flags` INTEGER, `blob` BLOB, PRIMARY KEY(`id`))",
    ROOM_MASTER,
    "CREATE TABLE notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
    "CREATE INDEX `idx_notebook_parent_order` ON `notebook` (`parentId`, `order`, `deletedAt`)",
]

HOST_SCHEMA_DDL = "CREATE TABLE host_schema (id INTEGER PRIMARY KEY CHECK (id = 0), version INTEGER NOT NULL)"

INK_STROKE_DDL = (
    "CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE, "
    '"order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)'
)
INK_STROKE_INDEX = 'CREATE INDEX stroke_page_order ON stroke(pageId, "order")'

SCRATCH_SCHEMA_VERSION = 1
SCRATCH_DDL = [
    HOST_SCHEMA_DDL,
    "CREATE TABLE page (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, "
    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    INK_STROKE_DDL,
    INK_STROKE_INDEX,
    "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    "CREATE INDEX page_position ON page(position)",
]

CALENDAR_SCHEMA_VERSION = 2
CALENDAR_DDL = [
    HOST_SCHEMA_DDL,
    "CREATE TABLE period (id TEXT PRIMARY KEY, kind INTEGER NOT NULL, date TEXT NOT NULL, UNIQUE(kind, date))",
    "CREATE TABLE page (id TEXT PRIMARY KEY, periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE, "
    "half INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, createdAt INTEGER NOT NULL, "
    "updatedAt INTEGER NOT NULL, UNIQUE(periodId, half))",
    INK_STROKE_DDL,
    INK_STROKE_INDEX,
    "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    "CREATE TABLE event (id TEXT PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, startDate TEXT NOT NULL, "
    "endDate TEXT NOT NULL, allDay INTEGER NOT NULL, startMinute INTEGER, endMinute INTEGER, recurring INTEGER NOT NULL, "
    "freq TEXT, interval INTEGER NOT NULL, monthlyMode TEXT NOT NULL, endMode TEXT NOT NULL, untilDate TEXT, "
    "endCount INTEGER, noteText TEXT NOT NULL, noteWidth REAL NOT NULL, noteHeight REAL NOT NULL, "
    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE event_weekday (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, weekday INTEGER NOT NULL, "
    "PRIMARY KEY(eventId, weekday))",
    "CREATE TABLE event_exception (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, date TEXT NOT NULL, "
    "PRIMARY KEY(eventId, date))",
    "CREATE TABLE event_reminder (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, amount INTEGER NOT NULL, "
    "unit TEXT NOT NULL, PRIMARY KEY(eventId, amount, unit))",
    "CREATE TABLE note_stroke (id TEXT PRIMARY KEY, eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, "
    '"order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)',
    "CREATE INDEX event_span ON event(startDate, endDate)",
    "CREATE INDEX event_recurring ON event(recurring)",
    'CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order")',
]

DOCUMENT_SCHEMA_VERSION = 1
DOCUMENT_DDL = [
    HOST_SCHEMA_DDL,
    "CREATE TABLE prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    "CREATE TABLE word (word TEXT PRIMARY KEY, addedAt INTEGER NOT NULL)",
    "CREATE TABLE caret (pageKey TEXT PRIMARY KEY, offset INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
]

# Extension package stems (the store file is `<pkg>.db` in the backup folder).
PKG_HOST = "com.symmetricalpalmtree.notesproutsn"
EXT_CALENDAR = PKG_HOST + ".ext.calendar"
EXT_SCRATCHPAD = PKG_HOST + ".ext.scratchpad"
EXT_DOCUMENT = PKG_HOST + ".ext.document"


def store_file_name(ext_pkg: str, variant: str) -> str:
    suffix = ".dev" if variant == "dev" else ""
    return f"{ext_pkg}{suffix}.db"


def create_plain(path: Path, ddl: list[str], user_version: int) -> sqlite3.Connection:
    if path.exists():
        path.unlink()
    con = sqlite3.connect(path)
    for stmt in ddl:
        con.execute(stmt)
    con.execute(f"PRAGMA user_version = {int(user_version)}")
    return con


def stamp_room(con: sqlite3.Connection, identity_hash: str) -> None:
    con.execute("DELETE FROM room_master_table")
    con.execute("INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)", (identity_hash,))


def stamp_host_schema(con: sqlite3.Connection, version: int) -> None:
    con.execute("INSERT OR REPLACE INTO host_schema (id, version) VALUES (0, ?)", (int(version),))
