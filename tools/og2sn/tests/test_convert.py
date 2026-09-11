from __future__ import annotations

import json
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import fixture  # noqa: E402
from og2sn import cipher, snschema, strokes  # noqa: E402
from og2sn.cli import main  # noqa: E402
from og2sn.shapes import pack_shape_flags, pack_sticky_flags  # noqa: E402


def q(db: Path, sql: str, *args):
    con = sqlite3.connect(db)
    try:
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


class PlainConversion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="og2sn-"))
        cls.src = fixture.build_library(cls.tmp / "og")
        cls.dst = cls.tmp / "sn"
        rc = main(["--in", str(cls.src), "--out", str(cls.dst), "--plain"])
        assert rc == 0
        cls.report = (cls.dst / "report.txt").read_text()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ── files + schema ──

    def test_output_set(self):
        names = sorted(p.name for p in self.dst.iterdir())
        self.assertIn("notesprout.db", names)
        self.assertIn(f"{fixture.NB1}.soil", names)
        self.assertIn(f"{fixture.NB2}.soil", names)
        self.assertIn("com.symmetricalpalmtree.notesproutsn.ext.calendar.db", names)
        self.assertIn("com.symmetricalpalmtree.notesproutsn.ext.scratchpad.db", names)
        self.assertIn("com.symmetricalpalmtree.notesproutsn.ext.document.db", names)
        self.assertNotIn("_work", names)
        self.assertEqual(len([n for n in names if n.endswith(".soil")]), 4)  # + Day notes + Tasks

    def test_room_identity(self):
        self.assertEqual(q(self.dst / "notesprout.db", "SELECT id, identity_hash FROM room_master_table"), [(42, snschema.INDEX_IDENTITY_HASH)])
        self.assertEqual(q(self.dst / "notesprout.db", "PRAGMA user_version"), [(1,)])
        soil = self.dst / f"{fixture.NB1}.soil"
        self.assertEqual(q(soil, "SELECT id, identity_hash FROM room_master_table"), [(42, snschema.SOIL_IDENTITY_HASH)])
        self.assertEqual(q(soil, "PRAGMA user_version"), [(1,)])
        cols = [r[1] for r in q(soil, "PRAGMA table_info(notebook)")]
        self.assertEqual(cols, ["id", "parentId", "type", "order", "createdAt", "updatedAt", "deletedAt", "text", "refId", "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob"])
        self.assertEqual([r[0] for r in q(soil, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")], ["notebook", "notebook_meta", "room_master_table"])

    # ── notebook 1 ──

    def soil1(self, sql, *a):
        return q(self.dst / f"{fixture.NB1}.soil", sql, *a)

    def test_root_pages_templates(self):
        self.assertEqual(self.soil1("SELECT text, refId FROM notebook WHERE type='notebook'"), [("First", fixture.PAGE2)])
        pages = self.soil1('SELECT id, "order", width, height, refId, deletedAt FROM notebook WHERE type=\'page\' ORDER BY "order"')
        self.assertEqual(pages[0], (fixture.PAGE1, 0, 1920.0, 2560.0, fixture.TEMPLATE1, None))
        self.assertEqual(pages[1], (fixture.PAGE2, 1, 1920.0, 2560.0, "", None))
        self.assertEqual(pages[2][0], "pdel")
        self.assertIsNotNone(pages[2][5])
        tpl = self.soil1("SELECT text, width, height, length(blob) FROM notebook WHERE type='template'")
        self.assertEqual(len(tpl), 1)
        self.assertTrue(tpl[0][0].startswith("IMG#") and len(tpl[0][0]) == 12)
        self.assertEqual(tpl[0][1:], (1920.0, 2560.0, len(fixture.PNG_1x1)))
        self.assertEqual(self.soil1("SELECT count(*) FROM notebook WHERE type IN ('layer','page_text')"), [(0,)])
        meta = json.loads(self.soil1("SELECT json FROM notebook_meta")[0][0])
        self.assertEqual(meta["folderPath"], [{"id": fixture.FOLDER, "name": "Work", "parentId": None}, {"id": fixture.SUBFOLDER, "name": "Inner", "parentId": fixture.FOLDER}])
        self.assertEqual((meta["encrypted"], meta["keyScope"], meta["textDocument"], meta["name"]), (True, "GLOBAL", False, "First"))

    def test_strokes_flatten_and_pass_through(self):
        rows = self.soil1('SELECT id, parentId, color, strokeWidth, style, blob, deletedAt FROM notebook WHERE type=\'stroke\' AND parentId=? ORDER BY "order"', fixture.PAGE1)
        by_id = {r[0]: r for r in rows}
        # columnar blob passes through byte-identical; pressure survives
        self.assertEqual(by_id["s1"][5], fixture.fmtb([(10, 10), (20, 20), (30, 25)], [0.5, 0.6, 0.7]))
        self.assertEqual(by_id["s1"][2:5], ("#000000", 3.0, "PEN"))
        # legacy JSON stroke re-encoded, pressure channel present, colour normalised
        pts = strokes.decode(by_id["s2"][5])
        self.assertEqual((pts.x, pts.y), ([1.0, 3.0], [2.0, 4.0]))
        for got, want in zip(pts.pressure, [0.3, 0.4]):
            self.assertAlmostEqual(got, want, places=5)
        self.assertEqual(by_id["s2"][2], "#FF0000")
        # second layer's stroke lands on the same page
        self.assertIn("s3", by_id)
        # deleted stroke carried with its tombstone
        self.assertIsNotNone(by_id["s4"][6])
        # fallback heading became its ink on the page
        self.assertIn("s5", by_id)
        self.assertEqual(self.soil1("SELECT count(*) FROM notebook WHERE id=?", fixture.HEAD2), [(0,)])

    def test_heading_text_shape_line(self):
        self.assertEqual(self.soil1("SELECT text, flags, x, y, width, height, parentId FROM notebook WHERE id=?", fixture.HEAD1), [("## Meeting notes", 2, 50.0, 40.0, 800.0, 90.0, fixture.PAGE1)])
        self.assertEqual(self.soil1("SELECT text, x, y, width, height FROM notebook WHERE id=?", fixture.TEXT1), [("Some **bold** text", 50.0, 400.0, 600.0, 200.0)])
        # line → 2-point stroke, dp→px width at density 1.875
        line = self.soil1("SELECT type, strokeWidth, blob FROM notebook WHERE id=?", fixture.LINE1)[0]
        self.assertEqual(line[0], "stroke")
        self.assertAlmostEqual(line[1], 2.0 * 1.875)
        pts = strokes.decode(line[2])
        self.assertEqual((pts.x, pts.y), ([100.0, 900.0], [702.0, 702.0]))
        # supported shape keeps its params
        sh = self.soil1("SELECT style, x, y, width, height, strokeWidth, flags FROM notebook WHERE id=?", fixture.SHAPE1)[0]
        self.assertEqual(sh[:5], ("RECTANGLE", 500.0, 900.0, 200.0, 100.0))
        self.assertAlmostEqual(sh[5], 3.75)
        self.assertEqual(sh[6], pack_shape_flags(True, 5, 15.0))
        self.assertEqual(sh[6] & 1, 1)
        self.assertEqual((sh[6] >> 8) & 0xFF, 5)
        self.assertEqual(sh[6] >> 16, 150)
        # unsupported shape became a closed polyline stroke
        hexa = self.soil1("SELECT type, blob FROM notebook WHERE id=?", fixture.SHAPE2)[0]
        self.assertEqual(hexa[0], "stroke")
        pts = strokes.decode(hexa[1])
        self.assertEqual(len(pts), 7)
        self.assertAlmostEqual(pts.x[0], pts.x[-1]); self.assertAlmostEqual(pts.y[0], pts.y[-1])

    def test_links(self):
        l1 = self.soil1("SELECT text, x, y, width, height, parentId FROM notebook WHERE id=?", fixture.LINK1)[0]
        self.assertEqual(l1[0], f"L1|1|2|{fixture.NB2}|{fixture.PAGE3}")
        self.assertEqual(l1[5], fixture.PAGE1)
        kids = self.soil1("SELECT id, type, text, width FROM notebook WHERE parentId=? ORDER BY \"order\"", fixture.LINK1)
        self.assertEqual([k[:3] for k in kids], [("s6", "stroke", None), ("h3", "heading", "### Linked"), ("t2", "text", "Tight text")])
        # wrapped text widened, and the link grew to cover it (was 40,1000 500x120)
        self.assertAlmostEqual(kids[2][3], 480 * 1.25 + 16)
        self.assertLess(l1[1], 40.0 + 1e-6); self.assertLessEqual(l1[2], 1000.0)
        self.assertGreaterEqual(l1[1] + l1[3], 50 + 480 * 1.25 + 16)
        self.assertGreaterEqual(l1[2] + l1[4], 1118)
        l2 = self.soil1("SELECT text FROM notebook WHERE id=?", fixture.LINK2)[0][0]
        self.assertEqual(l2, f"L1|1|0||{fixture.PAGE2}")
        kids2 = self.soil1("SELECT type, text, strokeWidth FROM notebook WHERE parentId=? ORDER BY \"order\"", fixture.LINK2)
        types = [k[0] for k in kids2]
        self.assertEqual(types, ["stroke", "heading", "stroke", "shape"])
        self.assertEqual(kids2[1][1], "# Legacy head")
        self.assertEqual(kids2[3][0], "shape")
        # legacy stroke carried its srcPoints pressure/tilt
        blob = self.soil1("SELECT blob FROM notebook WHERE parentId=? AND type='stroke' ORDER BY \"order\" LIMIT 1", fixture.LINK2)[0][0]
        pts = strokes.decode(blob)
        for got, want in zip(pts.pressure, [0.9, 0.8]):
            self.assertAlmostEqual(got, want, places=5)
        self.assertAlmostEqual(pts.tilt[1], 0.2, places=5)

    def test_sticky(self):
        st = self.soil1("SELECT x, y, width, height, flags FROM notebook WHERE id=?", fixture.STICKY1)[0]
        self.assertEqual(st, (1500.0, 100.0, 120.0, 120.0, pack_sticky_flags(1920, 1200)))
        self.assertEqual(st[4] & 0xFFFFF, 1920)
        self.assertEqual(st[4] >> 20, 1200)
        kids = self.soil1("SELECT id, type, color FROM notebook WHERE parentId=?", fixture.STICKY1)
        self.assertEqual(kids, [("s7", "stroke", "#0000FF")])
        self.assertIn("has no home in sticky", self.report)

    def test_documents(self):
        docs = self.soil1("SELECT parentId, text, flags FROM notebook WHERE type='document' ORDER BY parentId")
        self.assertEqual(docs, [(fixture.NB1, "# Whole notebook\n\nMerged.", None), (fixture.PAGE1, "# Page one\n\nDraft.", fixture.T0 + 100)])

    def test_notebook2_legacy_root(self):
        soil = self.dst / f"{fixture.NB2}.soil"
        self.assertEqual(q(soil, "SELECT text, refId FROM notebook WHERE type='notebook'"), [("Second", fixture.PAGE3)])
        self.assertEqual(q(soil, "SELECT width, height, refId FROM notebook WHERE type='page'"), [(1404.0, 1872.0, "")])
        self.assertEqual(json.loads(q(soil, "SELECT json FROM notebook_meta")[0][0])["textDocument"], True)

    # ── index ──

    def test_index(self):
        idx = self.dst / "notesprout.db"
        nbs = {r[0]: r for r in q(idx, "SELECT id, name, parentId, pageCount, flags, keyScope, templateKind, length(blob) FROM objects WHERE type='notebook'")}
        self.assertEqual(nbs[fixture.NB1][1:], ("First", fixture.SUBFOLDER, 2, 1, "GLOBAL", "IMAGE", len(fixture.PNG_1x1)))
        self.assertEqual(nbs[fixture.NB2][1:], ("Second", None, 1, 5, "GLOBAL", "BLANK", None))
        self.assertNotIn("missing-nb", nbs)  # no file → no row
        self.assertNotIn(fixture.NB2 + "-trash", nbs)  # trashed in OG → not shipped
        self.assertFalse((self.dst / f"{fixture.NB2}-trash.soil").exists())
        names = sorted(r[1] for r in nbs.values())
        self.assertEqual(names, ["Day notes", "First", "Second", "Tasks"])
        self.assertEqual(q(idx, "SELECT name, parentId FROM objects WHERE type='folder' ORDER BY name"), [("Inner", fixture.FOLDER), ("Work", None)])
        self.assertEqual(q(idx, "SELECT refId, sortOrder FROM objects WHERE type='list_item'"), [(fixture.NB1, 0)])  # ghost member dropped
        self.assertEqual(q(idx, "SELECT name, parentId, templateKind, flags FROM objects WHERE type='template'"), [("Cornell", "tf1", "IMAGE", 0)])
        self.assertEqual(q(idx, "SELECT count(*) FROM objects WHERE type IN ('clipboard','backup','naming')"), [(0,)])
        tasks_row = [r for r in nbs.values() if r[1] == "Tasks"][0]
        self.assertEqual((tasks_row[4], tasks_row[3]), (5, 1))

    # ── stores ──

    def test_calendar_store(self):
        cal = self.dst / "com.symmetricalpalmtree.notesproutsn.ext.calendar.db"
        self.assertEqual(q(cal, "PRAGMA user_version"), [(2,)])
        self.assertEqual(q(cal, "SELECT version FROM host_schema"), [(2,)])
        periods = sorted(q(cal, "SELECT kind, date FROM period"))
        self.assertEqual(periods, [(0, "2026-09-01"), (1, "2026-08-30"), (2, "2026-09-02")])
        pages = q(cal, "SELECT p.half, pr.kind, p.width FROM page p JOIN period pr ON pr.id = p.periodId ORDER BY pr.kind, p.half")
        self.assertEqual(pages, [(0, 0, 1920.0), (0, 1, 1920.0), (0, 2, 1920.0), (1, 2, 1920.0)])
        # month page: its stroke + the ellipse as a stroke; the heading was dropped with a warning
        month_page = q(cal, "SELECT p.id FROM page p JOIN period pr ON pr.id=p.periodId WHERE pr.kind=0")[0][0]
        st = q(cal, 'SELECT color, width, style FROM stroke WHERE pageId=? ORDER BY "order"', month_page)
        self.assertEqual(len(st), 2)
        self.assertEqual(st[0], (-16777216, 3.0, "PEN"))
        self.assertIn("Lost on ink", self.report)
        # events
        ev = {r[0]: r for r in q(cal, "SELECT id, type, startDate, endDate, allDay, startMinute, recurring, freq, interval, monthlyMode, endMode, endCount, noteText FROM event")}
        self.assertEqual(ev["ev1"][1:], ("BIRTHDAY", "2024-10-04", "2024-10-04", 1, None, 1, "YEARLY", 1, "DAY_OF_MONTH", "NEVER", None, "cake"))
        self.assertEqual(ev["ev2"][7:12], ("WEEKLY", 1, "DAY_OF_MONTH", "COUNT", 10))
        self.assertNotIn("ev3", ev)
        self.assertEqual(sorted(q(cal, "SELECT weekday FROM event_weekday WHERE eventId='ev2'")), [(1,), (3,), (5,)])
        self.assertEqual(q(cal, "SELECT date FROM event_exception WHERE eventId='ev1'"), [("2025-10-04",)])
        self.assertEqual(q(cal, "SELECT amount, unit FROM event_reminder WHERE eventId='ev1'"), [(1, "WEEKS")])

    def test_scratch_store(self):
        sp = self.dst / "com.symmetricalpalmtree.notesproutsn.ext.scratchpad.db"
        self.assertEqual(q(sp, "SELECT version FROM host_schema"), [(1,)])
        self.assertEqual(q(sp, "SELECT position, width FROM page ORDER BY position"), [(0, 1920.0), (1, 1920.0)])
        self.assertEqual(q(sp, "SELECT count(*) FROM stroke"), [(2,)])
        cur = q(sp, "SELECT value FROM state WHERE key='current'")[0][0]
        self.assertEqual(q(sp, "SELECT position FROM page WHERE id=?", cur), [(0,)])

    def test_document_store(self):
        doc = self.dst / "com.symmetricalpalmtree.notesproutsn.ext.document.db"
        self.assertEqual(sorted(q(doc, "SELECT word FROM word")), [("notesprout",), ("supernote",)])

    # ── authored notebooks ──

    def test_day_notes_and_tasks(self):
        idx = self.dst / "notesprout.db"
        dn = q(idx, "SELECT id FROM objects WHERE type='notebook' AND name='Day notes'")[0][0]
        soil = self.dst / f"{dn}.soil"
        pages = q(soil, 'SELECT id FROM notebook WHERE type=\'page\' ORDER BY "order"')
        self.assertEqual(len(pages), 2)
        heads = q(soil, 'SELECT text FROM notebook WHERE type=\'heading\' ORDER BY parentId, "order"')
        self.assertIn(("## 2026-09-01",), heads)
        self.assertIn(("## 2026-09-02",), heads)
        self.assertIn(("# Kept",), heads)
        self.assertEqual(q(soil, "SELECT count(*) FROM notebook WHERE type='stroke'"), [(2,)])
        tk = q(idx, "SELECT id FROM objects WHERE type='notebook' AND name='Tasks'")[0][0]
        md = q(self.dst / f"{tk}.soil", "SELECT text FROM notebook WHERE type='document'")[0][0]
        self.assertIn("- [ ] Buy milk (due 2026-09-04)", md)
        self.assertIn("### Weekly review", md)
        self.assertIn("- [x] Clear inbox", md)
        self.assertIn("## Done", md)
        self.assertIn("- [x] Old thing", md)
        self.assertEqual(json.loads(q(self.dst / f"{tk}.soil", "SELECT json FROM notebook_meta")[0][0])["textDocument"], True)


@unittest.skipUnless(cipher.shutil.which("sqlcipher"), "sqlcipher CLI not installed")
class EncryptedRoundTrip(unittest.TestCase):
    def test_encrypted_in_and_out(self):
        tmp = Path(tempfile.mkdtemp(prefix="og2sn-enc-"))
        try:
            plain = fixture.build_library(tmp / "plain")
            enc = tmp / "og"
            (enc / "Garden").mkdir(parents=True)
            cipher.encrypt_from_plain(plain / "notesprout.db", "og pass'word", enc / "notesprout.db", 11)
            for f in (plain / "Garden").glob("*.soil"):
                cipher.encrypt_from_plain(f, "og pass'word", enc / "Garden" / f.name, 5)
            self.assertTrue(cipher.is_encrypted(enc / "notesprout.db"))
            pf = tmp / "pass.txt"
            pf.write_text("og pass'word\n")
            of = tmp / "outpass.txt"
            of.write_text("sn-pass")
            dst = tmp / "sn"
            rc = main(["--in", str(enc), "--out", str(dst), "--passphrase-file", str(pf), "--out-passphrase-file", str(of), "--variant", "dev"])
            self.assertEqual(rc, 0)
            self.assertTrue(cipher.is_encrypted(dst / "notesprout.db"))
            self.assertTrue((dst / "com.symmetricalpalmtree.notesproutsn.ext.calendar.dev.db").exists())
            cipher.verify(dst / "notesprout.db", "sn-pass", 1)
            cipher.verify(dst / f"{fixture.NB1}.soil", "sn-pass", 1)
            cipher.verify(dst / "com.symmetricalpalmtree.notesproutsn.ext.calendar.dev.db", "sn-pass", 2)
            # readable again under the new key, with the Room hash intact
            out = tmp / "check.db"
            cipher.decrypt_to_plain(dst / f"{fixture.NB1}.soil", "sn-pass", out)
            self.assertEqual(q(out, "SELECT identity_hash FROM room_master_table"), [(snschema.SOIL_IDENTITY_HASH,)])
            self.assertFalse((dst / "_work").exists())
            with self.assertRaises(cipher.CipherError):
                cipher.decrypt_to_plain(dst / "notesprout.db", "wrong", tmp / "x.db")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
