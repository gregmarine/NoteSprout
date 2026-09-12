"""Reading OG rows format-agnostically: the v4+ typed columns when present, the legacy JSON
`boundingBox` / `data` otherwise. Works for a `.soil`'s `notebook` table and for the index's
`scratchpad` / `calendar` tables (same row shape)."""

from __future__ import annotations

import base64
import json
import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from typing import Any


def _json(s: str | None) -> Any:
    if not s:
        return None
    try:
        return json.loads(s)
    except (ValueError, TypeError):
        return None


class Row(dict):
    """A row as a dict of column → value; missing columns read as None."""

    def __getattr__(self, k: str) -> Any:
        return self.get(k)

    @property
    def data_json(self) -> Any:
        d = self.get("data")
        return _json(d) if d else None


def load_table(con: sqlite3.Connection, table: str) -> list[Row]:
    con.row_factory = sqlite3.Row
    cur = con.execute(f'SELECT * FROM "{table}"')
    return [Row(dict(r)) for r in cur.fetchall()]


def table_exists(con: sqlite3.Connection, table: str) -> bool:
    return con.execute("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()[0] > 0


@dataclass
class Tree:
    by_id: dict[str, Row]
    by_parent: dict[str, list[Row]]

    @classmethod
    def build(cls, rows: list[Row]) -> "Tree":
        by_id = {r["id"]: r for r in rows}
        by_parent: dict[str, list[Row]] = defaultdict(list)
        for r in rows:
            by_parent[r.get("parentId") or ""].append(r)
        for lst in by_parent.values():
            lst.sort(key=lambda r: ((r.get("order") or 0), r.get("createdAt") or 0))
        return cls(by_id, by_parent)

    def children(self, parent_id: str, alive_only: bool = False) -> list[Row]:
        rows = self.by_parent.get(parent_id, [])
        return [r for r in rows if r.get("deletedAt") is None] if alive_only else list(rows)


def box(row: Row) -> tuple[float, float, float, float] | None:
    """(x, y, w, h) from the typed columns, else the legacy `boundingBox` JSON `{x,y,width,height}`."""
    x, y, w, h = row.get("x"), row.get("y"), row.get("width"), row.get("height")
    if x is not None and y is not None and w is not None and h is not None:
        return float(x), float(y), float(w), float(h)
    bb = _json(row.get("boundingBox"))
    if isinstance(bb, dict):
        return float(bb.get("x", 0)), float(bb.get("y", 0)), float(bb.get("width", 0)), float(bb.get("height", 0))
    return None


def page_size(row: Row) -> tuple[float, float]:
    """A page keeps its size in `boundingBox` `{0,0,w,h}` even when columnar; legacy = PageData JSON."""
    d = row.data_json
    if isinstance(d, dict) and ("width" in d or "height" in d):
        return float(d.get("width", 0)), float(d.get("height", 0))
    bb = _json(row.get("boundingBox"))
    if isinstance(bb, dict):
        return float(bb.get("width", 0)), float(bb.get("height", 0))
    if row.get("width") is not None and row.get("height") is not None:
        return float(row["width"]), float(row["height"])
    return 0.0, 0.0


def page_template_id(row: Row) -> str:
    d = row.data_json
    if isinstance(d, dict) and "template" in d:
        return str(d.get("template") or "")
    return str(row.get("refId") or "")


def template_image(row: Row) -> tuple[bytes | None, str, int, int]:
    """(image bytes, name, width, height) for an OG template row (blob, or legacy base64 in JSON)."""
    d = row.data_json
    if isinstance(d, dict) and d.get("image"):
        try:
            img = base64.b64decode(d["image"])
        except (ValueError, TypeError):
            img = None
        return img, str(d.get("name") or ""), int(d.get("width") or 0), int(d.get("height") or 0)
    bb = box(row)
    w = int(bb[2]) if bb else int(row.get("width") or 0)
    h = int(bb[3]) if bb else int(row.get("height") or 0)
    return row.get("blob"), str(row.get("text") or ""), w, h


def notebook_title_and_last_page(row: Row) -> tuple[str, str | None]:
    d = row.data_json
    if isinstance(d, dict):
        return str(d.get("title") or ""), d.get("last_opened_page")
    return str(row.get("text") or ""), row.get("refId")


def index_cover(row: Row) -> bytes | None:
    if row.get("blob"):
        return row["blob"]
    d = row.data_json
    if isinstance(d, dict) and d.get("snapshot"):
        try:
            return base64.b64decode(d["snapshot"])
        except (ValueError, TypeError):
            return None
    return None


def index_notebook_payload(row: Row) -> dict:
    """OG notebook index fields, columns first, legacy JSON fallback."""
    d = row.data_json if isinstance(row.data_json, dict) else {}
    flags = row.get("flags")
    if flags is None:
        flags = (1 if d.get("encrypted") else 0) | (2 if d.get("excludeFromBackup") else 0)
    return {
        "pageCount": row.get("pageCount") if row.get("pageCount") is not None else d.get("pageCount"),
        "flags": int(flags or 0),
        "keyScope": row.get("keyScope") or d.get("keyScope"),
    }


def legacy_list_members(row: Row) -> list[str]:
    d = row.data_json
    if isinstance(d, dict):
        for k in ("notebookIds", "templateIds"):
            v = d.get(k)
            if isinstance(v, list):
                return [str(x) for x in v]
    return []
