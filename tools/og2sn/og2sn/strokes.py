"""Stroke geometry: format B (float32 + zlib) — byte-identical on both sides — and OG's legacy JSON."""

from __future__ import annotations

import json
import struct
import zlib
from dataclasses import dataclass, field
from typing import Iterable

FLAG_PRESSURE = 0x01
FLAG_TILT = 0x02


@dataclass
class Points:
    x: list[float] = field(default_factory=list)
    y: list[float] = field(default_factory=list)
    pressure: list[float] | None = None
    tilt: list[float] | None = None

    def __len__(self) -> int:
        return len(self.x)


def decode(blob: bytes) -> Points:
    if not blob or blob[0] != 1:
        raise ValueError("not a format-B stroke blob")
    payload = zlib.decompress(blob[1:])
    if not payload:
        return Points()
    flags = payload[0]
    has_p = bool(flags & FLAG_PRESSURE)
    has_t = bool(flags & FLAG_TILT)
    stride = 8 + (4 if has_p else 0) + (4 if has_t else 0)
    body = payload[1:]
    n = len(body) // stride
    pts = Points(pressure=[] if has_p else None, tilt=[] if has_t else None)
    off = 0
    for _ in range(n):
        vals = struct.unpack_from("<" + "f" * (stride // 4), body, off)
        off += stride
        pts.x.append(vals[0])
        pts.y.append(vals[1])
        i = 2
        if has_p:
            pts.pressure.append(vals[i]); i += 1
        if has_t:
            pts.tilt.append(vals[i]); i += 1
    return pts


def encode(pts: Points) -> bytes:
    has_p = pts.pressure is not None and len(pts.pressure) == len(pts.x)
    has_t = pts.tilt is not None and len(pts.tilt) == len(pts.x)
    flags = (FLAG_PRESSURE if has_p else 0) | (FLAG_TILT if has_t else 0)
    out = bytearray([flags])
    for i in range(len(pts.x)):
        out += struct.pack("<ff", float(pts.x[i]), float(pts.y[i]))
        if has_p:
            out += struct.pack("<f", float(pts.pressure[i]))
        if has_t:
            out += struct.pack("<f", float(pts.tilt[i]))
    return bytes([1]) + zlib.compress(bytes(out), 9)


def points_from_json_list(items: Iterable[dict]) -> Points:
    """OG `StrokePoint` / `PointF` JSON: `{x, y[, pressure][, tilt][, ts]}`."""
    pts = Points()
    pres: list[float] = []
    tilt: list[float] = []
    any_p = any_t = False
    for it in items:
        pts.x.append(float(it.get("x", 0.0)))
        pts.y.append(float(it.get("y", 0.0)))
        p = it.get("pressure")
        t = it.get("tilt")
        if p is not None:
            any_p = True
        if t is not None:
            any_t = True
        pres.append(1.0 if p is None else float(p))
        tilt.append(0.0 if t is None else float(t))
    if any_p:
        pts.pressure = pres
    if any_t:
        pts.tilt = tilt
    return pts


def points_from_xy(xy: Iterable[tuple[float, float]]) -> Points:
    pts = Points()
    for x, y in xy:
        pts.x.append(float(x)); pts.y.append(float(y))
    return pts


def translated(pts: Points, dx: float, dy: float) -> Points:
    return Points(
        x=[v + dx for v in pts.x], y=[v + dy for v in pts.y],
        pressure=list(pts.pressure) if pts.pressure is not None else None,
        tilt=list(pts.tilt) if pts.tilt is not None else None,
    )


def bounds(pts: Points) -> tuple[float, float, float, float] | None:
    if not pts.x:
        return None
    return min(pts.x), min(pts.y), max(pts.x), max(pts.y)


# ── OG legacy JSON shapes ──────────────────────────────────────────────────


def live_stroke_points(ls: dict) -> Points:
    """OG `LiveStroke` JSON: `points:[{x,y}]` plus optional `srcPoints:[{x,y,pressure,tilt}]`
    (index-aligned when present)."""
    pts = points_from_json_list(ls.get("points") or [])
    src = ls.get("srcPoints")
    if src and len(src) == len(pts):
        sp = points_from_json_list(src)
        pts.pressure, pts.tilt = sp.pressure, sp.tilt
    return pts


def stroke_data_points(sd: dict) -> Points:
    """OG `StrokeData` JSON: `{color, strokeWidth, points:[{x,y,pressure,tilt}]}`."""
    return points_from_json_list(sd.get("points") or [])


def inflate_json(blob: bytes):
    return json.loads(zlib.decompress(blob).decode("utf-8"))
