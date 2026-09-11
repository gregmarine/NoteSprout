"""OG page content → SN rows. One walker for every OG content table (a `.soil`'s `notebook`, the
index's `calendar` and `scratchpad`), format-agnostic on the OG side.

Modes (what the SN parent may hold):
  PAGE   — stroke, heading, text, shape, link, sticky_note
  LINK   — stroke, heading, text, shape, sticky_note (SN reads all five under a link)
  STICKY — stroke only, LOCAL coordinates
  INK    — stroke only (calendar / scratch pad store pages)
Anything a mode cannot hold is degraded: a shape or line becomes its outline as strokes, a
fallback heading/text becomes its ink, recognized text is dropped with a warning.
"""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

from . import shapes, strokes
from .ogread import Row, Tree, box

PAGE, LINK, STICKY, INK = "page", "link", "sticky", "ink"
ALLOWED = {
    PAGE: {"stroke", "heading", "text", "shape", "link", "sticky_note"},
    LINK: {"stroke", "heading", "text", "shape", "sticky_note"},
    STICKY: {"stroke"},
    INK: {"stroke"},
}

DEFAULT_COLOR = "#000000"
DEFAULT_WIDTH = 3.0
PEN = "PEN"


@dataclass
class SnRow:
    id: str
    parentId: str
    type: str
    order: int = 0
    createdAt: int = 0
    updatedAt: int = 0
    deletedAt: int | None = None
    text: str | None = None
    refId: str | None = None
    x: float | None = None
    y: float | None = None
    width: float | None = None
    height: float | None = None
    color: str | None = None
    strokeWidth: float | None = None
    style: str | None = None
    flags: int | None = None
    blob: bytes | None = None

    def as_tuple(self) -> tuple:
        return (
            self.id, self.parentId, self.type, self.order, self.createdAt, self.updatedAt, self.deletedAt,
            self.text, self.refId, self.x, self.y, self.width, self.height, self.color, self.strokeWidth,
            self.style, self.flags, self.blob,
        )


INSERT_SOIL = (
    'INSERT INTO notebook (id, parentId, type, "order", createdAt, updatedAt, deletedAt, text, refId, x, y, '
    "width, height, color, strokeWidth, style, flags, blob) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
)


@dataclass
class Ctx:
    density: float
    warn: Callable[[str], None]
    counts: dict[str, int] = field(default_factory=dict)

    def bump(self, key: str, n: int = 1) -> None:
        self.counts[key] = self.counts.get(key, 0) + n


def new_id() -> str:
    return str(uuid.uuid4())


def norm_color(c: str | None) -> str:
    if not c or not isinstance(c, str) or not c.startswith("#") or len(c) not in (7, 9):
        return DEFAULT_COLOR
    try:
        int(c[1:], 16)
    except ValueError:
        return DEFAULT_COLOR
    return c.upper()


_PREFIX = re.compile(r"^#{1,6} ")


def heading_prefix(level: int) -> str:
    return "#" * max(1, min(6, int(level or 1))) + " "


def heading_text(text: str, level: int) -> str:
    """SN `HeadingPrefix.applyLevel`: any existing `#{1,6} ` prefix is replaced, never stacked —
    OG already stores its recognized headings hash-prefixed."""
    return heading_prefix(level) + _PREFIX.sub("", str(text).strip(), count=1).strip()


# Wrapped text is drawn at its stored box width with SN's own font, which runs a little wider
# than OG's; a box that was exactly tight in OG wraps its last character onto a line the link
# composite never shows. Widen the box (and the link around it) so nothing wraps.
TEXT_IN_LINK_WIDEN = 1.25
TEXT_IN_LINK_PAD = 16.0
LINK_GROW_PAD = 4.0


# ── link payload ───────────────────────────────────────────────────────────

def link_payload(target: Any, chrome: str | None) -> str | None:
    """OG `LinkTarget` JSON (FQCN-discriminated sealed class) → SN `L1|chrome|kind|nb|page`."""
    if isinstance(target, str):
        try:
            target = json.loads(target)
        except ValueError:
            return None
    if not isinstance(target, dict):
        return None
    t = str(target.get("type") or "")
    ch = 0 if (chrome or "NONE") == "NONE" else 1
    if t.endswith("CurrentNotebookPage"):
        pid = target.get("pageId")
        return f"L1|{ch}|0||{pid}" if pid else None
    if t.endswith("OtherNotebookPage"):
        nb, pid = target.get("notebookId"), target.get("pageId")
        return f"L1|{ch}|2|{nb}|{pid}" if nb and pid else None
    if t.endswith("OtherNotebook"):
        nb = target.get("notebookId")
        return f"L1|{ch}|1|{nb}|" if nb else None
    return None


# ── the walker ─────────────────────────────────────────────────────────────

class Walker:
    def __init__(self, tree: Tree, ctx: Ctx):
        self.tree = tree
        self.ctx = ctx

    # -- entry points -------------------------------------------------------

    def convert_children(self, og_parent_ids: list[str], sn_parent: str, mode: str, start_order: int = 0) -> list[SnRow]:
        """All content rows under the given OG parents (layers of a page, or a composite), in
        z-order across parents, re-parented to `sn_parent`."""
        out: list[SnRow] = []
        order = start_order
        for pid in og_parent_ids:
            for row in self.tree.children(pid):
                t = row.get("type")
                if t in ("page_text", "layer", "document", "page", "template", "notebook"):
                    continue
                rows = self.convert_row(row, sn_parent, mode, order)
                order += len([r for r in rows if r.parentId == sn_parent])
                out.extend(rows)
        return out

    def convert_row(self, row: Row, sn_parent: str, mode: str, order: int) -> list[SnRow]:
        t = row.get("type")
        allowed = ALLOWED[mode]
        ts = self._ts(row)
        if t == "stroke":
            return self._stroke_row(row, sn_parent, order, ts)
        if t == "heading":
            return self._heading(row, sn_parent, mode, order, ts, allowed)
        if t == "text":
            return self._text(row, sn_parent, mode, order, ts, allowed)
        if t == "line":
            return self._line(row, sn_parent, order, ts)
        if t == "shape":
            return self._shape(row, sn_parent, order, ts, allowed)
        if t == "link":
            return self._link(row, sn_parent, mode, order, ts, allowed)
        if t == "sticky_note":
            return self._sticky(row, sn_parent, mode, order, ts, allowed)
        self.ctx.warn(f"unknown row type {t!r} ({row.get('id')}) dropped")
        self.ctx.bump("dropped.unknown")
        return []

    # -- helpers ------------------------------------------------------------

    @staticmethod
    def _ts(row: Row) -> tuple[int, int, int | None]:
        c = int(row.get("createdAt") or 0)
        u = int(row.get("updatedAt") or c)
        d = row.get("deletedAt")
        return c, u, (int(d) if d is not None else None)

    def _mk_stroke(self, sn_parent: str, order: int, pts: strokes.Points, color: str, width: float,
                   ts: tuple[int, int, int | None], sid: str | None = None, blob: bytes | None = None) -> SnRow | None:
        if blob is None:
            if len(pts) == 0:
                return None
            blob = strokes.encode(pts)
        self.ctx.bump("strokes")
        return SnRow(
            id=sid or new_id(), parentId=sn_parent, type="stroke", order=order,
            createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
            color=norm_color(color), strokeWidth=float(width if width and width > 0 else DEFAULT_WIDTH),
            style=PEN, blob=blob,
        )

    def _stroke_row(self, row: Row, sn_parent: str, order: int, ts) -> list[SnRow]:
        blob = row.get("blob")
        if blob and len(blob) > 0 and blob[0] == 1 and not row.get("data"):
            # Columnar format B — verify it decodes, then pass the bytes through untouched.
            try:
                pts = strokes.decode(blob)
            except Exception:
                self.ctx.warn(f"stroke {row['id']}: undecodable blob dropped")
                self.ctx.bump("dropped.stroke")
                return []
            if len(pts) == 0:
                return []
            r = self._mk_stroke(sn_parent, order, pts, row.get("color"), row.get("strokeWidth") or DEFAULT_WIDTH, ts, row["id"], blob)
            return [r] if r else []
        d = row.data_json
        if isinstance(d, dict):
            pts = strokes.stroke_data_points(d)
            r = self._mk_stroke(sn_parent, order, pts, d.get("color") or row.get("color"), d.get("strokeWidth") or row.get("strokeWidth") or DEFAULT_WIDTH, ts, row["id"])
            return [r] if r else []
        self.ctx.warn(f"stroke {row['id']}: no geometry, dropped")
        self.ctx.bump("dropped.stroke")
        return []

    def _packed_live_strokes(self, blob: bytes | None) -> list[dict]:
        if not blob:
            return []
        try:
            v = strokes.inflate_json(blob)
            return v if isinstance(v, list) else []
        except Exception:
            return []

    def _live_strokes_to_rows(self, items: list[dict], sn_parent: str, order: int, ts, dx: float = 0.0, dy: float = 0.0) -> list[SnRow]:
        out = []
        for ls in items:
            if not isinstance(ls, dict):
                continue
            pts = strokes.live_stroke_points(ls)
            if dx or dy:
                pts = strokes.translated(pts, dx, dy)
            r = self._mk_stroke(sn_parent, order + len(out), pts, ls.get("color"), ls.get("strokeWidth") or DEFAULT_WIDTH, ts)
            if r:
                out.append(r)
        return out

    def _fallback_ink(self, row: Row, legacy_strokes: list[dict] | None, sn_parent: str, order: int, ts) -> list[SnRow]:
        """A heading/text with no recognized text: its ink from child rows, a packed blob, or JSON."""
        out: list[SnRow] = []
        for child in self.tree.children(row["id"]):
            if child.get("type") == "stroke":
                out.extend(self._stroke_row(child, sn_parent, order + len(out), self._ts(child)))
        if not out:
            out.extend(self._live_strokes_to_rows(self._packed_live_strokes(row.get("blob")), sn_parent, order, ts))
        if not out and legacy_strokes:
            out.extend(self._live_strokes_to_rows(legacy_strokes, sn_parent, order, ts))
        return out

    # -- heading / text -----------------------------------------------------

    def _heading(self, row: Row, sn_parent: str, mode: str, order: int, ts, allowed: set[str]) -> list[SnRow]:
        d = row.data_json if isinstance(row.data_json, dict) else None
        text = row.get("text") if row.get("data") in (None, "") else (d or {}).get("recognizedText")
        level = int((row.get("level") if row.get("data") in (None, "") else (d or {}).get("level")) or 1)
        b = box(row)
        if text and str(text).strip():
            if "heading" in allowed and b:
                self.ctx.bump("headings")
                return [SnRow(
                    id=row["id"], parentId=sn_parent, type="heading", order=order,
                    createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                    text=heading_text(text, level), flags=level,
                    x=b[0], y=b[1], width=b[2], height=b[3],
                )]
            self.ctx.warn(f"heading {row['id']} ({str(text)[:30]!r}) has no home in {mode}; dropped")
            self.ctx.bump("dropped.heading_text")
            return []
        rows = self._fallback_ink(row, (d or {}).get("strokes") if d else None, sn_parent, order, ts)
        self.ctx.bump("headings.fallback_ink", 1 if rows else 0)
        return rows

    def _text(self, row: Row, sn_parent: str, mode: str, order: int, ts, allowed: set[str]) -> list[SnRow]:
        d = row.data_json if isinstance(row.data_json, dict) else None
        text = row.get("text") if row.get("data") in (None, "") else (d or {}).get("text")
        b = box(row)
        if text and str(text).strip():
            if "text" in allowed and b:
                self.ctx.bump("texts")
                return [SnRow(
                    id=row["id"], parentId=sn_parent, type="text", order=order,
                    createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                    text=str(text), x=b[0], y=b[1], width=b[2], height=b[3],
                )]
            self.ctx.warn(f"text {row['id']} ({str(text)[:30]!r}) has no home in {mode}; dropped")
            self.ctx.bump("dropped.text")
            return []
        return self._fallback_ink(row, (d or {}).get("strokes") if d else None, sn_parent, order, ts)

    # -- line / shape -------------------------------------------------------

    def _line(self, row: Row, sn_parent: str, order: int, ts) -> list[SnRow]:
        b = box(row)
        if not b:
            return []
        d = row.data_json if isinstance(row.data_json, dict) else {}
        orient = row.get("orientation") or d.get("orientation") or "HORIZONTAL"
        sw_dp = row.get("strokeWidth") if row.get("lineStyle") else d.get("strokeWidthDp", 1.0)
        x, y, w, h = b
        if orient == "VERTICAL":
            pts = strokes.points_from_xy([(x + w / 2, y), (x + w / 2, y + h)])
        else:
            pts = strokes.points_from_xy([(x, y + h / 2), (x + w, y + h / 2)])
        self.ctx.bump("lines_as_strokes")
        r = self._mk_stroke(sn_parent, order, pts, DEFAULT_COLOR, float(sw_dp or 1.0) * self.ctx.density, ts, row["id"])
        return [r] if r else []

    def _shape_params(self, row: Row) -> dict | None:
        if row.get("shapeType"):
            return {
                "type": row["shapeType"], "centerX": float(row.get("centerX") or 0), "centerY": float(row.get("centerY") or 0),
                "width": float(row.get("width") or 0), "height": float(row.get("height") or 0),
                "rotationDeg": float(row.get("rotationDeg") or 0), "strokeWidthDp": float(row.get("strokeWidth") or 1.0),
                "aspectLocked": bool((row.get("flags") or 0) & 1), "pointCount": int(row.get("pointCount") or 5),
            }
        d = row.data_json
        if isinstance(d, dict) and d.get("type"):
            return {
                "type": d["type"], "centerX": float(d.get("centerX") or 0), "centerY": float(d.get("centerY") or 0),
                "width": float(d.get("width") or 0), "height": float(d.get("height") or 0),
                "rotationDeg": float(d.get("rotationDeg") or 0), "strokeWidthDp": float(d.get("strokeWidthDp") or 1.0),
                "aspectLocked": bool(d.get("aspectLocked")), "pointCount": int(d.get("pointCount") or 5),
            }
        return None

    def _shape_from_params(self, p: dict, sn_parent: str, order: int, ts, allowed: set[str], sid: str | None) -> list[SnRow]:
        kind = p["type"]
        width_px = float(p["strokeWidthDp"]) * self.ctx.density
        if "shape" in allowed and kind in shapes.SN_SHAPES:
            self.ctx.bump("shapes")
            return [SnRow(
                id=sid or new_id(), parentId=sn_parent, type="shape", order=order,
                createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                style=kind, x=p["centerX"], y=p["centerY"], width=p["width"], height=p["height"],
                strokeWidth=width_px, flags=shapes.pack_shape_flags(p["aspectLocked"], p["pointCount"], p["rotationDeg"]),
            )]
        if kind not in shapes.OG_SHAPES:
            self.ctx.warn(f"shape {sid}: unknown type {kind!r} dropped")
            return []
        out = []
        for i, poly in enumerate(shapes.outline_polylines(kind, p["centerX"], p["centerY"], p["width"], p["height"], p["rotationDeg"], p["pointCount"])):
            r = self._mk_stroke(sn_parent, order + i, strokes.points_from_xy(poly), DEFAULT_COLOR, width_px, ts, sid if (i == 0 and sid) else None)
            if r:
                out.append(r)
        self.ctx.bump("shapes_as_strokes")
        return out

    def _shape(self, row: Row, sn_parent: str, order: int, ts, allowed: set[str]) -> list[SnRow]:
        p = self._shape_params(row)
        if not p:
            self.ctx.warn(f"shape {row['id']}: unreadable, dropped")
            return []
        return self._shape_from_params(p, sn_parent, order, ts, allowed, row["id"])

    # -- composites ---------------------------------------------------------

    def _legacy_composite(self, row: Row) -> dict | None:
        """A link/sticky still carrying its content inline: JSON in `data`, or zlib(JSON) in `blob`."""
        d = row.data_json
        if isinstance(d, dict):
            return d
        if row.get("blob"):
            try:
                v = strokes.inflate_json(row["blob"])
                return v if isinstance(v, dict) else None
            except Exception:
                return None
        return None

    def _legacy_children(self, obj: dict, sn_parent: str, mode: str, ts, allowed: set[str]) -> list[SnRow]:
        """Materialise a legacy composite's nested lists as SN child rows with fresh ids."""
        out: list[SnRow] = []

        def nxt() -> int:
            return len(out)

        out.extend(self._live_strokes_to_rows(obj.get("strokes") or [], sn_parent, nxt(), ts))
        for h in obj.get("headings") or []:
            if not isinstance(h, dict):
                continue
            bb = h.get("boundingBox") or {}
            text = h.get("recognizedText")
            if text and str(text).strip() and "heading" in allowed:
                self.ctx.bump("headings")
                out.append(SnRow(
                    id=new_id(), parentId=sn_parent, type="heading", order=nxt(), createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                    text=heading_text(text, int(h.get("level") or 1)), flags=int(h.get("level") or 1),
                    x=float(bb.get("left", 0)), y=float(bb.get("top", 0)),
                    width=float(bb.get("right", 0)) - float(bb.get("left", 0)), height=float(bb.get("bottom", 0)) - float(bb.get("top", 0)),
                ))
            elif text and str(text).strip():
                self.ctx.warn(f"nested heading ({str(text)[:30]!r}) has no home in {mode}; dropped")
                self.ctx.bump("dropped.heading_text")
            else:
                out.extend(self._live_strokes_to_rows(h.get("strokes") or [], sn_parent, nxt(), ts))
        for t in obj.get("textObjects") or []:
            if not isinstance(t, dict):
                continue
            bb = t.get("boundingBox") or {}
            text = t.get("text")
            if text and str(text).strip() and "text" in allowed:
                self.ctx.bump("texts")
                out.append(SnRow(
                    id=new_id(), parentId=sn_parent, type="text", order=nxt(), createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                    text=str(text), x=float(bb.get("left", 0)), y=float(bb.get("top", 0)),
                    width=float(bb.get("right", 0)) - float(bb.get("left", 0)), height=float(bb.get("bottom", 0)) - float(bb.get("top", 0)),
                ))
            elif text and str(text).strip():
                self.ctx.warn(f"nested text ({str(text)[:30]!r}) has no home in {mode}; dropped")
                self.ctx.bump("dropped.text")
            else:
                out.extend(self._live_strokes_to_rows(t.get("strokes") or [], sn_parent, nxt(), ts))
        for ln in obj.get("lines") or []:
            if not isinstance(ln, dict):
                continue
            bb = ln.get("boundingBox") or {}
            x, y = float(bb.get("left", 0)), float(bb.get("top", 0))
            w, h = float(bb.get("right", 0)) - x, float(bb.get("bottom", 0)) - y
            if (ln.get("orientation") or "HORIZONTAL") == "VERTICAL":
                pts = strokes.points_from_xy([(x + w / 2, y), (x + w / 2, y + h)])
            else:
                pts = strokes.points_from_xy([(x, y + h / 2), (x + w, y + h / 2)])
            r = self._mk_stroke(sn_parent, nxt(), pts, DEFAULT_COLOR, float(ln.get("strokeWidthDp") or 1.0) * self.ctx.density, ts)
            if r:
                out.append(r)
            self.ctx.bump("lines_as_strokes")
        for sh in obj.get("shapes") or []:
            if not isinstance(sh, dict) or not sh.get("type"):
                continue
            sw_dp = sh.get("strokeWidthDp")
            if sw_dp is None:
                sw_dp = float(sh.get("strokeWidthPx") or self.ctx.density) / self.ctx.density
            p = {
                "type": sh["type"], "centerX": float(sh.get("centerX") or 0), "centerY": float(sh.get("centerY") or 0),
                "width": float(sh.get("width") or 0), "height": float(sh.get("height") or 0),
                "rotationDeg": float(sh.get("rotationDeg") or 0), "strokeWidthDp": float(sw_dp),
                "aspectLocked": bool(sh.get("aspectLocked")), "pointCount": int(sh.get("pointCount") or 5),
            }
            out.extend(self._shape_from_params(p, sn_parent, nxt(), ts, allowed, None))
        return out

    def _link(self, row: Row, sn_parent: str, mode: str, order: int, ts, allowed: set[str]) -> list[SnRow]:
        b = box(row)
        legacy = self._legacy_composite(row)
        target = row.get("linkTarget") if row.get("linkTarget") else (legacy or {}).get("target")
        chrome = row.get("chrome") if row.get("chrome") else (legacy or {}).get("chrome")
        payload = link_payload(target, chrome) if target is not None else None
        if "link" in allowed and b and payload:
            link_id = row["id"]
            head = SnRow(
                id=link_id, parentId=sn_parent, type="link", order=order,
                createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                text=payload, x=b[0], y=b[1], width=b[2], height=b[3],
            )
            if legacy is not None:
                kids = self._legacy_children(legacy, link_id, LINK, ts, ALLOWED[LINK])
            else:
                kids = self.convert_children([link_id], link_id, LINK)
            self._fit_link(head, kids)
            self.ctx.bump("links")
            return [head] + kids
        # No home for a link here (or an unreadable target): keep its content in place.
        if payload is None:
            self.ctx.warn(f"link {row['id']}: target unreadable; content kept, link dropped")
            self.ctx.bump("dropped.link")
        if legacy is not None:
            return self._legacy_children(legacy, sn_parent, mode, ts, allowed)
        return self.convert_children([row["id"]], sn_parent, mode, order)

    @staticmethod
    def _fit_link(head: SnRow, kids: list[SnRow]) -> None:
        """Widen wrapped text boxes and grow the link's bounds to cover every direct child box."""
        l, t = head.x, head.y
        r, b = head.x + (head.width or 0), head.y + (head.height or 0)
        grew = False
        for k in kids:
            if k.parentId != head.id:
                continue
            if k.type == "text" and k.width:
                k.width = k.width * TEXT_IN_LINK_WIDEN + TEXT_IN_LINK_PAD
            if k.type in ("text", "heading", "sticky_note") and k.x is not None and k.width is not None:
                l2, t2, r2, b2 = k.x, k.y, k.x + k.width, k.y + (k.height or 0)
            elif k.type == "stroke" and k.blob:
                bb = strokes.bounds(strokes.decode(k.blob))
                if not bb:
                    continue
                pad = (k.strokeWidth or DEFAULT_WIDTH) / 2
                l2, t2, r2, b2 = bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad
            else:
                continue
            if l2 < l or t2 < t or r2 > r or b2 > b:
                grew = True
            l, t, r, b = min(l, l2), min(t, t2), max(r, r2), max(b, b2)
        if grew:
            head.x, head.y = l - LINK_GROW_PAD, t - LINK_GROW_PAD
            head.width, head.height = (r - l) + 2 * LINK_GROW_PAD, (b - t) + 2 * LINK_GROW_PAD

    def _sticky(self, row: Row, sn_parent: str, mode: str, order: int, ts, allowed: set[str]) -> list[SnRow]:
        b = box(row)
        legacy = self._legacy_composite(row)
        if legacy is not None:
            cw, chh = float(legacy.get("contentWidth") or 0), float(legacy.get("contentHeight") or 0)
        else:
            cw, chh = float(row.get("contentW") or 0), float(row.get("contentH") or 0)
        if "sticky_note" in allowed and b:
            sid = row["id"]
            head = SnRow(
                id=sid, parentId=sn_parent, type="sticky_note", order=order,
                createdAt=ts[0], updatedAt=ts[1], deletedAt=ts[2],
                x=b[0], y=b[1], width=b[2], height=b[3], flags=shapes.pack_sticky_flags(cw, chh),
            )
            if legacy is not None:
                kids = self._legacy_children(legacy, sid, STICKY, ts, ALLOWED[STICKY])
            else:
                kids = self.convert_children([sid], sid, STICKY)
            self.ctx.bump("stickies")
            return [head] + kids
        # A sticky cannot live here: its content is in LOCAL coordinates, so translate it to the
        # icon's top-left and drop it in as ink.
        self.ctx.warn(f"sticky {row['id']} has no home in {mode}; content unfolded at its icon")
        self.ctx.bump("stickies_unfolded")
        dx, dy = (b[0], b[1]) if b else (0.0, 0.0)
        kids = self._legacy_children(legacy, sn_parent, mode, ts, {"stroke"}) if legacy is not None \
            else self.convert_children([row["id"]], sn_parent, STICKY, order)
        for k in kids:
            if k.type == "stroke" and k.blob:
                pts = strokes.translated(strokes.decode(k.blob), dx, dy)
                k.blob = strokes.encode(pts)
        return kids


def page_layers(tree: Tree, page_id: str) -> list[str]:
    """The OG page's layers in order (every content row hangs off a layer). A page with content
    rows directly under it (defensive) is treated as its own layer."""
    layers = [r["id"] for r in tree.children(page_id) if r.get("type") == "layer"]
    direct = [r for r in tree.children(page_id) if r.get("type") not in ("layer", "page_text", "document")]
    return ([page_id] if direct else []) + layers
