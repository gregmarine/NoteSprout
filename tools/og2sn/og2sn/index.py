"""OG `notesprout.db` `objects` → SN `notesprout.db` `objects`."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from . import snschema
from .ogread import Row, index_cover, index_notebook_payload, legacy_list_members, load_table

PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"  # same sentinel in both apps
FLAG_ENCRYPTED, FLAG_EXCLUDE_BACKUP, FLAG_TEXT_DOCUMENT = 1, 2, 4

INSERT_OBJ = (
    "INSERT INTO objects (id, type, name, parentId, createdAt, updatedAt, deletedAt, pageCount, flags, keyScope, "
    "templateKind, blob, refId, sortOrder) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
)


@dataclass
class IndexNotebook:
    id: str
    name: str
    parent_id: str | None
    flags: int
    key_scope: str | None
    deleted_at: int | None
    created_at: int
    updated_at: int
    page_count: int | None
    cover: bytes | None
    text_document: bool


@dataclass
class IndexPlan:
    notebooks: list[IndexNotebook] = field(default_factory=list)
    folders: list[Row] = field(default_factory=list)
    lists: list[tuple[Row, list[str]]] = field(default_factory=list)
    template_folders: list[Row] = field(default_factory=list)
    templates: list[Row] = field(default_factory=list)
    by_id: dict[str, Row] = field(default_factory=dict)

    def ancestry(self, parent_id: str | None) -> list[dict]:
        """SN `folderPath`: root → immediate parent, as `{id, name, parentId}`."""
        out: list[dict] = []
        seen: set[str] = set()
        pid = parent_id
        while pid and pid not in seen and pid in self.by_id and self.by_id[pid].get("type") == "folder":
            seen.add(pid)
            f = self.by_id[pid]
            out.append({"id": f["id"], "name": f["name"], "parentId": f.get("parentId")})
            pid = f.get("parentId")
        out.reverse()
        return out

    def name_taken(self, name: str, parent_id: str | None) -> bool:
        for n in self.notebooks:
            if n.parent_id == parent_id and n.deleted_at is None and n.name == name:
                return True
        for f in self.folders:
            if f.get("parentId") == parent_id and f.get("deletedAt") is None and f["name"] == name:
                return True
        return False

    def free_name(self, name: str, parent_id: str | None = None) -> str:
        if not self.name_taken(name, parent_id):
            return name
        i = 2
        while self.name_taken(f"{name} {i}", parent_id):
            i += 1
        return f"{name} {i}"


def read_plan(og_con: sqlite3.Connection) -> IndexPlan:
    rows = load_table(og_con, "objects")
    plan = IndexPlan(by_id={r["id"]: r for r in rows})
    items_by_list: dict[str, list[Row]] = {}
    for r in rows:
        if r.get("type") == "list_item" and r.get("parentId"):
            items_by_list.setdefault(r["parentId"], []).append(r)
    for r in rows:
        t = r.get("type")
        if t == "notebook":
            p = index_notebook_payload(r)
            plan.notebooks.append(IndexNotebook(
                id=r["id"], name=r["name"], parent_id=r.get("parentId"), flags=p["flags"], key_scope=p["keyScope"],
                deleted_at=r.get("deletedAt"), created_at=int(r.get("createdAt") or 0), updated_at=int(r.get("updatedAt") or 0),
                page_count=p["pageCount"], cover=index_cover(r), text_document=bool(p["flags"] & FLAG_TEXT_DOCUMENT),
            ))
        elif t == "folder":
            plan.folders.append(r)
        elif t == "list":
            if r["id"] != PINNED_LIST_ID:
                continue  # the pinned-templates list has a different sentinel and different members in SN
            members = legacy_list_members(r) or [
                str(i["refId"]) for i in sorted(items_by_list.get(r["id"], []), key=lambda i: (i.get("sortOrder") or 0))
                if i.get("refId")
            ]
            plan.lists.append((r, members))
        elif t == "template_folder":
            plan.template_folders.append(r)
        elif t == "template":
            plan.templates.append(r)
    return plan


def write_index(
    sn_path, plan: IndexPlan, converted: dict[str, tuple[int, bool]], extra_notebooks: list[IndexNotebook], ctx,
) -> None:
    """`converted` = notebook id → (alive page count, has a template row) for every notebook that
    produced an SN file; notebooks not in it are left out of the index (their file is not in the
    set, and a row naming a missing file is an orphan the restore would refuse)."""
    con = snschema.create_plain(sn_path, snschema.INDEX_DDL, snschema.INDEX_USER_VERSION)
    snschema.stamp_room(con, snschema.INDEX_IDENTITY_HASH)
    rows: list[tuple] = []
    import uuid

    for f in plan.folders:
        rows.append((f["id"], "folder", f["name"], f.get("parentId"), int(f.get("createdAt") or 0), int(f.get("updatedAt") or 0), f.get("deletedAt"), None, None, None, None, None, None, None))
        ctx.bump("index.folders")
    alive_notebook_ids: set[str] = set()
    for n in plan.notebooks + extra_notebooks:
        if n.id not in converted:
            continue
        pages, has_template = converted[n.id]
        flags = FLAG_ENCRYPTED | (n.flags & (FLAG_EXCLUDE_BACKUP | FLAG_TEXT_DOCUMENT))
        kind = "IMAGE" if has_template else "BLANK"
        rows.append((n.id, "notebook", n.name, n.parent_id, n.created_at, n.updated_at, n.deleted_at, pages, flags, "GLOBAL", kind, n.cover, None, None))
        if n.deleted_at is None:
            alive_notebook_ids.add(n.id)
        ctx.bump("index.notebooks")
    for lst, members in plan.lists:
        rows.append((lst["id"], "list", lst["name"] or "pinned", None, int(lst.get("createdAt") or 0), int(lst.get("updatedAt") or 0), None, None, None, None, None, None, None, None))
        pos = 0
        for m in members:
            if m not in alive_notebook_ids:
                continue
            rows.append((str(uuid.uuid4()), "list_item", "", lst["id"], int(lst.get("updatedAt") or 0), int(lst.get("updatedAt") or 0), None, None, None, None, None, None, m, pos))
            pos += 1
        ctx.bump("index.pinned", pos)
    for tf in plan.template_folders:
        rows.append((tf["id"], "template_folder", tf["name"], tf.get("parentId"), int(tf.get("createdAt") or 0), int(tf.get("updatedAt") or 0), tf.get("deletedAt"), None, None, None, None, None, None, None))
    for t in plan.templates:
        img = t.get("blob")
        if not img:
            d = t.data_json
            if isinstance(d, dict) and d.get("image"):
                import base64
                try:
                    img = base64.b64decode(d["image"])
                except (ValueError, TypeError):
                    img = None
        if not img:
            ctx.warn(f"library template {t.get('name')!r} has no image; skipped")
            continue
        rows.append((t["id"], "template", t["name"], t.get("parentId"), int(t.get("createdAt") or 0), int(t.get("updatedAt") or 0), t.get("deletedAt"), None, 0, None, "IMAGE", img, None, None))
        ctx.bump("index.templates")
    con.executemany(INSERT_OBJ, rows)
    con.commit()
    con.close()
