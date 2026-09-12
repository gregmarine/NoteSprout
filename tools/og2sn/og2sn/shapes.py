"""Shape outlines. SN keeps six shape kinds; the other five OG kinds become closed polyline strokes
along OG's own outline (rotation about the centre, as `ShapeGeometry.pathFor` does)."""

from __future__ import annotations

import math

SN_SHAPES = {"RECTANGLE", "ELLIPSE", "TRIANGLE", "ARROW", "LINE", "STAR"}
OG_SHAPES = SN_SHAPES | {"DIAMOND", "TRAPEZOID", "PENTAGON", "HEXAGON", "ARCH"}

MIN_POINTS, MAX_POINTS = 5, 12


def pack_shape_flags(aspect_locked: bool, point_count: int, rotation_deg: float) -> int:
    """SN `ShapeFlags.pack`: bit 0 aspect · bits 8–15 point count · bits 16–31 rotation tenths."""
    pts = max(MIN_POINTS, min(MAX_POINTS, int(point_count or MIN_POINTS)))
    deg = float(rotation_deg or 0.0) % 360.0
    tenths = int(round(deg * 10.0)) % 3600
    return (1 if aspect_locked else 0) | (pts << 8) | (tenths << 16)


def pack_sticky_flags(content_w: float, content_h: float) -> int:
    """SN `StickyFlags.pack`: bits 0–19 content width · bits 20–39 content height (px, ints)."""
    m = 0xFFFFF
    w = max(0, min(m, int(round(content_w or 0))))
    h = max(0, min(m, int(round(content_h or 0))))
    return w | (h << 20)


def _rotate(points: list[tuple[float, float]], cx: float, cy: float, deg: float) -> list[tuple[float, float]]:
    if not deg:
        return points
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    out = []
    for x, y in points:
        dx, dy = x - cx, y - cy
        out.append((cx + dx * c - dy * s, cy + dx * s + dy * c))
    return out


def outline_polylines(
    kind: str, cx: float, cy: float, w: float, h: float, rotation_deg: float, point_count: int = 5,
) -> list[list[tuple[float, float]]]:
    """OG `ShapeGeometry.pathFor` as sampled polylines, page-absolute, closed shapes repeating the
    first point at the end. Ellipses and arches are sampled at 64 / 32 segments."""
    hw, hh = w / 2.0, h / 2.0
    L, T, R, B = cx - hw, cy - hh, cx + hw, cy + hh
    polys: list[list[tuple[float, float]]] = []

    def closed(pts: list[tuple[float, float]]) -> list[tuple[float, float]]:
        return pts + [pts[0]]

    if kind == "RECTANGLE":
        polys.append(closed([(L, T), (R, T), (R, B), (L, B)]))
    elif kind == "ELLIPSE":
        n = 64
        polys.append(closed([(cx + hw * math.cos(2 * math.pi * i / n), cy + hh * math.sin(2 * math.pi * i / n)) for i in range(n)]))
    elif kind == "TRIANGLE":
        polys.append(closed([(cx, T), (R, B), (L, B)]))
    elif kind == "DIAMOND":
        polys.append(closed([(cx, T), (R, cy), (cx, B), (L, cy)]))
    elif kind == "TRAPEZOID":
        inset = 0.2 * w
        polys.append(closed([(L + inset, T), (R - inset, T), (R, B), (L, B)]))
    elif kind in ("PENTAGON", "HEXAGON"):
        n = 5 if kind == "PENTAGON" else 6
        start = -math.pi / 2 + (math.pi / 6 if kind == "HEXAGON" else 0.0)
        polys.append(closed([(cx + hw * math.cos(start + i * 2 * math.pi / n), cy + hh * math.sin(start + i * 2 * math.pi / n)) for i in range(n)]))
    elif kind == "STAR":
        n = max(MIN_POINTS, min(MAX_POINTS, int(point_count or 5)))
        start = -math.pi / 2
        tips = [(cx + hw * math.cos(start + i * 2 * math.pi / n), cy + hh * math.sin(start + i * 2 * math.pi / n)) for i in range(n)]
        polys.append(closed([tips[(i * 2) % n] for i in range(n)]))
    elif kind == "ARCH":
        pts: list[tuple[float, float]] = []
        if h >= hw:
            pts.append((L, B))
            pts.append((L, cy))
            # dome over rect (L, T, R, T + h) from 180° to 360°
            rx, ry = hw, h / 2.0
            ox, oy = cx, T + ry
            for i in range(33):
                ang = math.pi + math.pi * i / 32
                pts.append((ox + rx * math.cos(ang), oy + ry * math.sin(ang)))
            pts.append((R, B))
            polys.append(closed(pts))
        else:
            for i in range(33):
                ang = math.pi + math.pi * i / 32
                pts.append((cx + hw * math.cos(ang), cy + hh * math.sin(ang)))
            pts.append((L, cy))
            polys.append(closed(pts))
    elif kind == "LINE":
        polys.append([(L, cy), (R, cy)])
    elif kind == "ARROW":
        polys.append([(L, cy), (R, cy)])
        head = min(hw * 0.5, 72.0)
        for ang in (math.pi * 5 / 6, -math.pi * 5 / 6):
            polys.append([(R, cy), (R + head * math.cos(ang), cy + head * math.sin(ang))])
    else:
        polys.append(closed([(L, T), (R, T), (R, B), (L, B)]))
    return [_rotate(p, cx, cy, rotation_deg) for p in polys]
