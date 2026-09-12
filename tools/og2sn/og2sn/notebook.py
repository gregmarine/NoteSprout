"""One OG `.soil` (plaintext) → one SN `.soil` (plaintext). Layers collapse into the page;
templates, documents and the meta row cross over; page_text and undo state stay behind."""

from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass

from . import snschema
from .content import PAGE, Ctx, INSERT_SOIL, SnRow, Walker, heading_prefix, page_layers
from .ogread import Row, Tree, load_table, notebook_title_and_last_page, page_size, page_template_id, table_exists, template_image

NIL_UUID = "00000000-0000-0000-0000-000000000000"


def image_token(image: bytes, fit: int = 0) -> str:
    """SN `TemplateToken.ofImage`: `IMG#` + first 8 hex of SHA-256(fit byte ‖ bytes)."""
    h = hashlib.sha256()
    h.update(bytes([fit & 0xFF]))
    h.update(image)
    return "IMG#" + h.hexdigest()[:8]


@dataclass
class NotebookResult:
    notebook_id: str
    page_count: int
    has_template: bool
    text_document: bool
    rows: int


def find_root(tree: Tree) -> Row | None:
    roots = [r for r in tree.children("") if r.get("type") == "notebook"]
    if roots:
        return roots[0]
    # Legacy files without a root row parent their pages to the NIL uuid.
    return None


def convert_soil(
    og_con: sqlite3.Connection,
    sn_path,
    notebook_id: str,
    name: str,
    folder_path: list[dict],
    text_document: bool,
    ctx: Ctx,
    app_version_code: int = 1,
) -> NotebookResult:
    rows = load_table(og_con, "notebook")
    tree = Tree.build(rows)
    root = find_root(tree)
    root_id = root["id"] if root else NIL_UUID
    title, last_page = notebook_title_and_last_page(root) if root else (name, None)
    walker = Walker(tree, ctx)

    out: list[SnRow] = []
    now_c = int(root.get("createdAt") or 0) if root else 0
    now_u = int(root.get("updatedAt") or now_c) if root else 0

    # Templates (per-notebook rows under the root)
    template_ids: set[str] = set()
    for t in tree.children(root_id):
        if t.get("type") != "template":
            continue
        img, tname, w, h = template_image(t)
        if not img:
            ctx.warn(f"template {t['id']} has no image; pages using it fall back to blank")
            continue
        template_ids.add(t["id"])
        out.append(SnRow(
            id=t["id"], parentId=notebook_id, type="template", order=int(t.get("order") or 0),
            createdAt=int(t.get("createdAt") or now_c), updatedAt=int(t.get("updatedAt") or now_u),
            deletedAt=t.get("deletedAt"), text=image_token(img, 0), width=float(w), height=float(h), blob=img,
        ))
        ctx.bump("templates")

    # Pages
    pages = [p for p in tree.children(root_id) if p.get("type") == "page"]
    alive_pages = 0
    page_ids: list[str] = []
    for order, p in enumerate(pages):
        w, h = page_size(p)
        if w <= 0 or h <= 0:
            ctx.warn(f"page {p['id']} has no size; skipped")
            continue
        tid = page_template_id(p)
        if tid and tid not in template_ids:
            tid = ""
        page_ids.append(p["id"])
        if p.get("deletedAt") is None:
            alive_pages += 1
        out.append(SnRow(
            id=p["id"], parentId=notebook_id, type="page", order=order,
            createdAt=int(p.get("createdAt") or now_c), updatedAt=int(p.get("updatedAt") or now_u),
            deletedAt=p.get("deletedAt"), refId=tid, width=w, height=h,
        ))
        ctx.bump("pages")
        # Content: every layer, in order, flattened onto the page.
        out.extend(walker.convert_children(page_layers(tree, p["id"]), p["id"], PAGE))
        # The page document
        for d in tree.children(p["id"]):
            if d.get("type") == "document" and d.get("text") and str(d["text"]).strip():
                out.append(_document_row(d, p["id"]))
                ctx.bump("documents")

    # The notebook document (parented to the root row, or the NIL uuid on a legacy file)
    for d in tree.children(root_id):
        if d.get("type") == "document" and d.get("text") and str(d["text"]).strip():
            out.append(_document_row(d, notebook_id))
            ctx.bump("documents")

    if last_page not in page_ids:
        last_page = page_ids[0] if page_ids else None
    out.insert(0, SnRow(
        id=notebook_id, parentId="", type="notebook", order=0, createdAt=now_c, updatedAt=now_u,
        text=name or title, refId=last_page,
    ))

    # Write
    con = snschema.create_plain(sn_path, snschema.SOIL_DDL, snschema.SOIL_USER_VERSION)
    snschema.stamp_room(con, snschema.SOIL_IDENTITY_HASH)
    con.executemany(INSERT_SOIL, [r.as_tuple() for r in out])
    meta = {
        "formatVersion": 1, "notebookId": notebook_id, "name": name or title,
        "createdAt": now_c, "updatedAt": now_u, "encrypted": True, "keyScope": "GLOBAL",
        "folderPath": folder_path, "appVersionCode": app_version_code, "textDocument": bool(text_document),
    }
    con.execute("INSERT INTO notebook_meta (id, json) VALUES (0, ?)", (json.dumps(meta, separators=(",", ":")),))
    con.commit()
    con.close()
    return NotebookResult(notebook_id, alive_pages, bool(template_ids), text_document, len(out))


def _document_row(d: Row, parent: str) -> SnRow:
    src = d.get("srcUpdatedAt")
    return SnRow(
        id=d["id"], parentId=parent, type="document", order=0,
        createdAt=int(d.get("createdAt") or 0), updatedAt=int(d.get("updatedAt") or 0), deletedAt=d.get("deletedAt"),
        text=str(d["text"]), flags=(int(src) if src is not None else None),
    )


def write_fresh_notebook(
    sn_path, notebook_id: str, name: str, folder_path: list[dict], pages: list[dict], text_document: bool,
    document_text: str | None, now: int, app_version_code: int = 1,
) -> NotebookResult:
    """A notebook the converter authors itself (Day notes, Tasks). `pages` = [{id, width, height,
    heading: str|None, rows: [SnRow]}] — an empty list makes one blank page."""
    import uuid

    out: list[SnRow] = []
    if not pages:
        pages = [{"id": str(uuid.uuid4()), "width": 1920.0, "height": 2560.0, "heading": None, "rows": []}]
    for order, p in enumerate(pages):
        out.append(SnRow(id=p["id"], parentId=notebook_id, type="page", order=order, createdAt=now, updatedAt=now, refId="", width=float(p["width"]), height=float(p["height"])))
        if p.get("heading"):
            out.append(SnRow(
                id=str(uuid.uuid4()), parentId=p["id"], type="heading", order=0, createdAt=now, updatedAt=now,
                text=heading_prefix(2) + p["heading"], flags=2, x=48.0, y=40.0, width=float(p["width"]) - 96.0, height=96.0,
            ))
        out.extend(p.get("rows") or [])
    if document_text and document_text.strip():
        out.append(SnRow(id=str(uuid.uuid4()), parentId=notebook_id, type="document", order=0, createdAt=now, updatedAt=now, text=document_text))
    out.insert(0, SnRow(id=notebook_id, parentId="", type="notebook", createdAt=now, updatedAt=now, text=name, refId=pages[0]["id"]))
    con = snschema.create_plain(sn_path, snschema.SOIL_DDL, snschema.SOIL_USER_VERSION)
    snschema.stamp_room(con, snschema.SOIL_IDENTITY_HASH)
    con.executemany(INSERT_SOIL, [r.as_tuple() for r in out])
    meta = {
        "formatVersion": 1, "notebookId": notebook_id, "name": name, "createdAt": now, "updatedAt": now,
        "encrypted": True, "keyScope": "GLOBAL", "folderPath": folder_path, "appVersionCode": app_version_code,
        "textDocument": bool(text_document),
    }
    con.execute("INSERT INTO notebook_meta (id, json) VALUES (0, ?)", (json.dumps(meta, separators=(",", ":")),))
    con.commit()
    con.close()
    return NotebookResult(notebook_id, len(pages), False, text_document, len(out))
