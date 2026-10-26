"""fleet_console tests (run: python3 -m unittest discover -s tools/fleet-console -v)."""
import base64
import hashlib
import http.client
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock
from urllib.parse import unquote

import fleet_console as fc

# fmt: off
KST, GENESIS, VIN, H = timezone(timedelta(hours=9)), "0" * 64, "KMHTEST000000001", 3600000
kst_ms = lambda y, mo, d, h, mi: int(datetime(y, mo, d, h, mi, tzinfo=KST).timestamp() * 1000)
T = kst_ms(2025, 3, 6, 9, 0)    # a Thursday, months away from 'now': month/year come from the newest trip, not the clock

def sha(prev, seq, epoch_ms, kind, trip_id, body):    # the wire contract's hash rule, written out independently
    return hashlib.sha256(("%s|%d|%d|%s|%s|%s" % (prev, seq, epoch_ms, kind, trip_id, body)).encode("utf-8")).hexdigest()

class Chain:
    def __init__(self, vin=VIN, plate="12가 3456"):
        self.car = {"vin": vin, "plate": plate, "model": "아이오닉 5", "entity": "가상 물류(주)"}
        self.prev, self.seq = GENESIS, 0

    def add(self, kind, trip_id, body, at=None):
        self.seq += 1
        at, text = at or T + 60000 * self.seq, json.dumps(body, ensure_ascii=False)
        h = sha(self.prev, self.seq, at, kind, trip_id, text)
        entry = {"seq": self.seq, "epochMs": at, "kind": kind, "tripId": trip_id, "body": text, "prevHash": self.prev, "hash": h}
        self.prev = h
        return {"type": "ledger_entry", "app": "autobrief", "appVersion": "2.0.0", "car": self.car, "entry": entry}

    def trip(self, n, start, so, eo, place="거래처"):
        return self.add("TRIP", "TRIP-%d" % n, {"id": "TRIP-%d" % n, "startEpochMs": start, "endEpochMs": start + 1800000,
                        "startOdometerKm": so, "endOdometerKm": eo, "vin": self.car["vin"], "source": "OBD",
                        "startPlace": "본사", "endPlace": place}, start)

    def classify(self, n, purpose, by="USER"):
        return self.add("CLASSIFY", "TRIP-%d" % n, {"id": "TRIP-%d" % n, "purpose": purpose, "userName": "홍길동",
                                                    "userDept": "영업팀", "memo": "", "by": by, "autoReason": None})

    def run(self, count):    # count back-to-back 5 km trips, seq 1..count
        return [self.trip(n, T + n * H, 1000.0 + n * 10, 1005.0 + n * 10) for n in range(1, count + 1)]

def archive_payload(name="운행기록부_2026-10-01_2026-10-31.xlsx", data=b"PK\x03\x04demo", car=None):
    return {"type": "logbook_archive", "car": car or Chain().car, "fileName": name, "periodStart": "2026-10-01",
            "periodEnd": "2026-10-31", "verificationCode": "ABCD-1234", "xlsxBase64": base64.b64encode(data).decode("ascii")}

class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fleet-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.store = fc.Store(self.tmp)

    def feed(self, *payloads):
        return [self.store.ingest(p) for p in payloads]

    def car(self, key=VIN):
        return {c["key"]: c for c in self.store.state()["cars"]}[key]

    def texts(self, key=VIN):
        return [a["text"] for a in self.car(key)["alerts"]]

class ChainTests(Base):
    def test_valid_chain_projection(self):
        ch = Chain()
        self.assertEqual(self.feed(ch.trip(1, T, 1000.0, 1012.5), ch.classify(1, "BUSINESS"), ch.trip(2, kst_ms(2025, 3, 6, 18, 30), 1012.5, 1020.0),
                                   ch.trip(3, kst_ms(2024, 2, 1, 9, 0), 2000.0, 2005.0)), [(200, {"ok": True})] * 4)    # trip 3: other month AND year
        car = self.car()
        self.assertEqual((car["chainStatus"], car["entries"], car["lastSeq"]), ("정상", 4, 4))
        self.assertEqual(car["lastHashShort"], (ch.prev[:4] + "-" + ch.prev[4:8]).upper())
        newest, oldest, stale = car["recentTrips"]
        self.assertEqual((newest["id"], newest["purpose"], newest["purposeLabel"], stale["id"]), ("TRIP-2", "UNCLASSIFIED", "미분류", "TRIP-3"))
        self.assertEqual((oldest["purpose"], oldest["user"], oldest["distanceKm"], oldest["date"], oldest["time"]),
                         ("BUSINESS", "홍길동", 12.5, "2025-03-06(목)", "09:00~09:30"))
        m = car["month"]
        self.assertEqual((m["ym"], m["totalKm"], m["businessKm"], m["generalBusinessKm"], m["commuteKm"], m["unclassifiedCount"], m["ratioPct"],
                          car["year"]["ym"], car["year"]["totalKm"]), ("2025-03", 20.0, 12.5, 12.5, 0.0, 1, 62.5, "2025", 20.0))

    def test_tampered_body_marks_car_suspect(self):
        ch = Chain()
        first, forged = ch.trip(1, T, 1000.0, 1010.0), ch.trip(2, T + H, 1010.0, 1020.0)
        forged["entry"]["body"] = forged["entry"]["body"].replace("1020.0", "1011.0")    # edited after hashing
        self.feed(first, forged)
        car = self.car()
        self.assertEqual((car["chainStatus"], car["recentTrips"][0]["status"], self.store.cars[VIN].by_seq[2]["status"]),
                         ("변조 의심", "변조 의심", "hash_mismatch"))
        self.assertEqual(car["alerts"][0], {"text": "기록 변조 의심 (순번 2): 해시 불일치", "level": "bad", "seq": 2, "tripId": None})

    def test_seq_gap_flags_missing_then_accepts_late_arrival(self):
        p = Chain().run(5)
        self.feed(p[0], p[1], p[4])                                  # seq 1, 2, 5
        car = self.car()
        self.assertEqual((car["chainStatus"], car["lastSeq"]), ("누락 있음", 5))
        self.assertIn({"text": "중간 기록 누락 (순번 3~4)", "level": "warn", "seq": 5, "tripId": None}, car["alerts"])
        self.feed(p[2])                                              # seq 3 arrives late: stored, status stays
        car = self.car()
        self.assertEqual((car["entries"], car["chainStatus"], "늦게 도착한 기록 (순번 3)" in self.texts()), (4, "누락 있음", True))

    def test_same_seq_same_hash_is_duplicate_other_hash_is_suspect(self):
        a, b = Chain().trip(1, T, 1000.0, 1010.0), Chain().trip(1, T, 1000.0, 1099.0)    # both seq 1, different hash
        self.feed(a)
        self.assertEqual(self.store.ingest(a), (200, {"ok": True, "duplicate": True}))
        self.assertEqual((self.car()["entries"], self.car()["chainStatus"]), (1, "정상"))
        self.feed(b)
        car = self.car()
        self.assertEqual((car["entries"], car["chainStatus"], car["recentTrips"][0]["distanceKm"]), (1, "변조 의심", 10.0))
        self.assertEqual(car["alerts"][0], {"text": "이미 받은 순번에 다른 기록이 도착했습니다 (순번 1)", "level": "bad", "seq": 1, "tripId": "TRIP-1"})

    def test_forged_resend_is_rejected_and_names_the_row_it_targeted(self):
        ch = Chain()
        trip, label = ch.trip(1, T, 1000.0, 1026.0), ch.classify(1, "PERSONAL")
        self.feed(trip, label, ch.trip(2, T + H, 1026.0, 1030.0))
        forged = json.loads(json.dumps(label))                       # forge_demo.py: PERSONAL -> BUSINESS, hash recomputed
        e, body = forged["entry"], json.loads(label["entry"]["body"])
        body["purpose"] = "BUSINESS"
        e["body"] = json.dumps(body, ensure_ascii=False)
        e["hash"] = sha(e["prevHash"], e["seq"], e["epochMs"], e["kind"], e["tripId"], e["body"])
        self.assertEqual(self.store.ingest(forged), (200, {"ok": True}))
        car = self.car()
        row = {t["id"]: t for t in car["recentTrips"]}["TRIP-1"]
        self.assertEqual((car["entries"], row["purpose"], row["status"], car["chainStatus"]), (3, "PERSONAL", "정상", "변조 의심"))    # the first copy stays
        self.assertEqual(car["alerts"][0], {"text": "이미 받은 순번에 다른 기록이 도착했습니다 (순번 2)", "level": "bad", "seq": 2, "tripId": "TRIP-1"})

    def test_alert_texts_say_sunbeon_not_seq(self):
        p = Chain().run(6)
        bad = json.loads(json.dumps(p[5]))
        bad["entry"]["body"] = bad["entry"]["body"].replace("1065.0", "1061.0")                  # edited after hashing
        other = Chain().trip(1, T, 1000.0, 1099.0)                                                # seq 1 again, other content
        self.feed(p[0], p[2], p[1], bad, other)                       # gap (2), late (2), hash mismatch (6, gap 4~5), conflict (1)
        texts = self.texts()
        self.assertEqual(len(texts), 5)
        self.assertTrue(all("순번" in t and "seq" not in t for t in texts), texts)

    def test_unknown_trip_keeps_entry_and_alerts(self):
        self.feed(Chain().classify(99, "BUSINESS"))
        car = self.car()
        self.assertEqual((car["entries"], car["recentTrips"], car["chainStatus"]), (1, [], "정상"))
        self.assertTrue(any("원장에 없는 운행" in a for a in self.texts()))

    def test_amend_changes_purpose_and_sets_amended(self):
        ch = Chain()
        self.feed(ch.trip(1, T, 1000.0, 1010.0), ch.classify(1, "PERSONAL", by="AUTO"))
        before = self.car()["recentTrips"][0]
        self.assertEqual((before["purpose"], before["amended"], before["by"]), ("PERSONAL", False, "AUTO"))
        self.feed(ch.add("AMEND", "TRIP-1", {"id": "TRIP-1", "purpose": "BUSINESS", "userName": "홍길동", "userDept": "영업팀",
                                             "memo": "거래처 방문", "by": "USER", "reason": "방문 확인", "previousPurpose": "PERSONAL"}))
        car = self.car()
        trip = car["recentTrips"][0]
        self.assertEqual((trip["purpose"], trip["amended"], trip["memo"], trip["by"], trip["amendReason"]),
                         ("BUSINESS", True, "거래처 방문", "USER", "방문 확인"))
        self.assertEqual((car["month"]["businessKm"], car["month"]["personalKm"], car["month"]["ratioPct"]), (10.0, 0.0, 100.0))

    def test_car_without_vin_is_keyed_by_plate_and_first_entry_must_be_seq_1(self):
        self.feed(Chain(vin=None).run(2)[1])                         # no vin -> plate key; seq 1 was never seen
        car = self.car(key="12가 3456")
        self.assertEqual((car["vin"], car["plate"], car["chainStatus"]), (None, "12가 3456", "누락 있음"))
        self.assertIn("중간 기록 누락 (순번 1~1)", self.texts(key="12가 3456"))
        self.assertTrue(os.path.isfile(os.path.join(self.tmp, "12가_3456.jsonl")))

class LimitTests(Base):
    def test_idempotency_keys_are_deduped_and_bounded(self):
        ch = Chain()
        a, b = ch.trip(1, T, 1000.0, 1010.0), ch.trip(2, T + H, 1010.0, 1020.0)
        self.assertEqual(self.store.ingest(a, "key-1"), (200, {"ok": True}))
        self.assertEqual(self.store.ingest(a, "key-1"), (200, {"ok": True, "duplicate": True}))
        self.assertEqual(self.store.ingest(b, "key-1"), (200, {"ok": True, "duplicate": True}))    # the key wins: no change
        self.assertEqual((self.car()["entries"], self.car()["month"]["totalKm"]), (1, 10.0))
        mem = self.store.cars[VIN]
        for i in range(fc.MAX_IDEMPOTENCY_KEYS + 5):
            mem.remember("k%d" % i)
        self.assertEqual((len(mem.idem_q), len(mem.idem_s)), (fc.MAX_IDEMPOTENCY_KEYS, fc.MAX_IDEMPOTENCY_KEYS))

    @mock.patch.object(fc, "MAX_ENTRIES_PER_CAR", 3)
    def test_entry_cap_keeps_newest_and_says_so(self):
        p = Chain().run(5)
        self.feed(*p)
        car = self.car()
        self.assertEqual((car["entries"], car["lastSeq"], car["chainStatus"]), (3, 5, "정상"))
        self.assertTrue(any("오래된 기록" in a for a in self.texts()))
        self.assertEqual(self.store.ingest(p[0]), (200, {"ok": True, "duplicate": True}))    # a trimmed seq is not re-added

    @mock.patch.object(fc, "MAX_CARS", 1)
    def test_car_cap_rejects_new_cars(self):
        self.feed(Chain().trip(1, T, 1000.0, 1010.0))
        code, out = self.store.ingest(Chain(vin="KMHOTHER000000002").trip(1, T, 1000.0, 1010.0))
        self.assertEqual((code, out["ok"], len(self.store.state()["cars"])), (429, False, 1))

    @mock.patch.object(fc, "MAX_ARCHIVES", 2)
    def test_archive_cap_deletes_oldest_on_disk_and_in_memory(self):
        for n in (1, 2, 3):
            self.assertEqual(self.store.ingest(archive_payload(name="log_%d.xlsx" % n, data=b"x%d" % n))[0], 200)
        self.assertEqual([a["fileName"] for a in self.car()["archives"]], ["log_3.xlsx", "log_2.xlsx"])
        self.assertEqual(sorted(os.listdir(os.path.join(self.tmp, "archives"))), [VIN + "_log_2.xlsx", VIN + "_log_3.xlsx"])

    def test_restart_rebuilds_identical_state_from_jsonl(self):
        ch = Chain()
        p = ch.run(2)
        self.feed(p[0], ch.classify(1, "BUSINESS"), ch.classify(77, "PERSONAL"))    # seq 1, 3 (2 never sent), 4
        self.feed(archive_payload())
        before = self.store.state()
        self.assertTrue(before["cars"][0]["alerts"] and before["cars"][0]["archives"])    # the comparison is not vacuous
        again = fc.Store(self.tmp)
        self.assertEqual({**before, "generatedAt": 0}, {**again.state(), "generatedAt": 0})
        self.assertEqual(again.ingest(ch.trip(3, T + 2 * H, 1020.0, 1030.0))[1], {"ok": True})    # and it keeps working

class HttpTests(Base):
    def setUp(self):
        super().setUp()
        self.log = io.StringIO()                                     # the server's per-request log lines land here
        patcher = mock.patch.object(sys, "stderr", self.log)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.server = fc.make_server(self.tmp, "127.0.0.1", 0)       # its own Store, replayed from the same dir
        self.store = self.server.store
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)                        # cleanups run LIFO: shutdown, then close
        self.port = self.server.server_address[1]

    def req(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request(method, path, body=body, headers=headers or {})
        r = conn.getresponse()
        data = r.read()
        conn.close()
        return r.status, {k.lower(): v for k, v in r.getheaders()}, data

    def post(self, payload, key=None):
        headers = {"Content-Type": "application/json", **({"Idempotency-Key": key} if key else {})}
        status, _, data = self.req("POST", "/hook", json.dumps(payload).encode("utf-8"), headers)
        return status, json.loads(data)

    def test_archive_saved_and_served_and_traversal_refused(self):
        self.assertEqual(self.post(archive_payload(data=b"PK\x03\x04hello")), (200, {"ok": True}))
        arch = json.loads(self.req("GET", "/api/state")[2])["cars"][0]["archives"][0]
        self.assertEqual((arch["fileName"], arch["periodEnd"], arch["verificationCode"]), ("운행기록부_2026-10-01_2026-10-31.xlsx", "2026-10-31", "ABCD-1234"))
        status, headers, body = self.req("GET", arch["url"])
        disposition = headers["content-disposition"]
        self.assertEqual((status, body, disposition.startswith("attachment;"), "filename*=UTF-8''" in disposition), (200, b"PK\x03\x04hello", True, True))
        self.assertIn(unquote(arch["url"])[len("/archives/"):], os.listdir(os.path.join(self.tmp, "archives")))
        open(os.path.join(self.tmp, "canary.txt"), "w").close()      # a real file just above archives/: a traversal would serve it
        for bad in ("/archives/../fleet_console.py", "/archives/../canary.txt", "/archives/%2e%2e/canary.txt", "/archives/..%2Fcanary.txt", "/archives/missing.xlsx"):
            self.assertEqual(self.req("GET", bad)[0], 404, bad)

    def test_pretendard_is_served_from_the_app_bundle_and_used_by_the_page(self):
        for name, src in fc.FONTS.items():
            status, headers, body = self.req("GET", "/fonts/" + name)
            with open(os.path.join(fc.FONT_DIR, src), "rb") as f:
                original = f.read()
            self.assertEqual((status, headers["content-type"], len(body)), (200, "font/ttf", len(original)), name)
            # only bit 6 of glyph flags changes (the bundled font sets it on most glyphs), plus the two checksums
            flips = sum(1 for a, b in zip(original, body) if a ^ b == 0x40)
            other = sum(1 for a, b in zip(original, body) if a ^ b not in (0, 0x40))
            self.assertTrue(flips > 1000 and other <= 8, (name, flips, other))
            self.assertEqual(fc._sfnt_checksum(body, 0, len(body)), 0xB1B0AFBA)        # whole-font checksum still valid
            self.assertEqual(fc.web_safe_ttf(body), body)                                # nothing left to clear
        for bad in ("/fonts/../fleet_console.py", "/fonts/pretendard_regular.ttf", "/fonts/", "/fonts/%2e%2e/fleet_console.py"):
            self.assertEqual(self.req("GET", bad)[0], 404, bad)
        page = self.req("GET", "/")[2].decode("utf-8")
        for needle in ("@font-face", "/fonts/Pretendard-Regular.ttf", "/fonts/Pretendard-Bold.ttf", "--personal:#E59AC0", "변조 시도", "banner"):
            self.assertIn(needle, page)

    def test_oversized_content_length_gets_413_before_any_body(self):
        status, _, data = self.req("POST", "/hook", None, {"Content-Length": str(fc.MAX_BODY_BYTES + 1)})    # headers only
        self.assertEqual((status, json.loads(data)["ok"]), (413, False))

    def test_bad_input_is_400_and_server_keeps_serving(self):
        for raw in (b"{not json", b"[1, 2]", b"\xff\xfe", b"{}", json.dumps({"type": "ledger_entry", "car": {"vin": "V"}}).encode()):
            status, _, data = self.req("POST", "/hook", raw)
            self.assertEqual((status, json.loads(data)["ok"]), (400, False), raw)
        self.assertEqual(self.post(dict(archive_payload(), xlsxBase64="@@@not-base64@@@"))[0], 400)
        self.assertEqual(self.req("POST", "/hook", None, {"Content-Length": "x"})[0], 411)
        self.assertEqual(self.req("POST", "/elsewhere", b"{}")[0], 404)
        self.assertEqual((self.req("GET", "/healthz")[2], json.loads(self.req("GET", "/api/state")[2])["cars"]), (b"ok", []))

    def test_hook_idempotency_header_dashboard_and_log_lines(self):
        p = Chain().trip(1, T, 1000.0, 1010.0, place="SECRETMARK")
        self.assertEqual(self.post(p, "abc"), (200, {"ok": True}))
        self.assertEqual(self.post(p, "abc"), (200, {"ok": True, "duplicate": True}))
        self.assertEqual(self.car()["entries"], 1)
        status, headers, body = self.req("GET", "/")
        self.assertEqual((status, headers["content-type"], "오토브리프 관리자 화면" in body.decode("utf-8")), (200, "text/html; charset=utf-8", True))
        log = self.log.getvalue()
        self.assertEqual((log.count("POST /hook 200"), log.count("GET / 200")), (2, 1))    # one line per request
        self.assertNotIn("SECRETMARK", log)                                                # bodies are never logged

if __name__ == "__main__":
    unittest.main()
