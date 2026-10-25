#!/usr/bin/env python3
"""오토브리프 관리자 콘솔: 차량 원장 웹훅 수신 + 대시보드 (표준 라이브러리만, Python 3.9+).
실행: python3 fleet_console.py --port 8787 --bind 127.0.0.1 --data ./fleet-data"""

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import sys
import threading
from collections import deque
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote

# fmt: off
# Declared caps (memory-bounds mandate): every in-process store below is bounded. (Compact layout: formatter off.)
MAX_CARS = 200
MAX_ENTRIES_PER_CAR = 50000    # newest kept; the first trim is noted in the car's alerts
MAX_ARCHIVES = 100             # global; oldest deleted on disk and in memory
MAX_BODY_BYTES = 15 * 1024 * 1024
MAX_IDEMPOTENCY_KEYS = 5000    # per car
MAX_ENTRY_BODY_CHARS = 4096    # one ledger entry's body string
MAX_ALERTS, MAX_RECENT = 20, 30                      # per car
MAX_EPOCH_MS, SOCKET_TIMEOUT_S = 4102444800000, 30   # 2100-01-01

GENESIS, WEEKDAYS = "0" * 64, "월화수목금토일"
KST = timezone(timedelta(hours=9))
OK, GAP, TAMPER = "정상", "누락 있음", "변조 의심"
RANK = {OK: 0, GAP: 1, TAMPER: 2}
KINDS = ("TRIP", "GAP", "CLASSIFY", "AMEND")
PURPOSES = ("COMMUTE", "BUSINESS", "PERSONAL")
LABELS = {"COMMUTE": "출퇴근", "BUSINESS": "일반 업무", "PERSONAL": "비업무",
          "UNCLASSIFIED": "미분류", "UNLOGGED_GAP": "미기록 구간"}
KM_BUCKET = {"COMMUTE": "commuteKm", "BUSINESS": "generalBusinessKm",
             "PERSONAL": "personalKm", "UNLOGGED_GAP": "gapKm"}
BAD_ENTRY = {"hash_mismatch": "해시 불일치", "chain_break": "앞 기록과 연결 불일치", "bad_body": "본문 형식 오류"}
ARCHIVE_PUBLIC = ("fileName", "periodStart", "periodEnd", "verificationCode", "receivedAt")
UNSAFE = re.compile(r"[^0-9A-Za-z가-힣ㄱ-ㅣ_.\-]")
HERE = os.path.dirname(os.path.abspath(__file__))
XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

def entry_hash(prev_hash, seq, epoch_ms, kind, trip_id, body):
    raw = "%s|%d|%d|%s|%s|%s" % (prev_hash, seq, epoch_ms, kind, trip_id, body)
    return hashlib.sha256(raw.encode("utf-8", "surrogatepass")).hexdigest()

def sanitize(name, limit=120):
    """Keep Hangul, ASCII letters/digits and - _ . ; keep the extension when truncating."""
    s = UNSAFE.sub("_", str(name)).lstrip(".") or "_"
    stem, ext = os.path.splitext(s)
    return s if len(s) <= limit else stem[:limit - len(ext[:10])] + ext[:10]

def now_iso():
    return datetime.now(KST).isoformat(timespec="seconds")

to_kst = lambda ms: datetime.fromtimestamp(ms / 1000.0, KST)    # callers pass epoch() values: always in range

def txt(v):
    return "" if v is None else str(v)

def num(v):
    """A number below 1e18, else None (bool, NaN, inf and absurd ints are not numbers)."""
    ok = isinstance(v, (int, float)) and not isinstance(v, bool) and abs(v) < 1e18
    return float(v) if ok else None

def epoch(v, default):
    f = num(v)
    return f if f is not None and 0 <= f <= MAX_EPOCH_MS else default

def car_key(car):
    return next((car[k].strip() for k in ("vin", "plate") if isinstance(car.get(k), str) and car[k].strip()), None)

def validate(p):
    """Error string for a malformed payload, else None."""
    if not isinstance(p, dict) or not isinstance(p.get("car"), dict) or car_key(p["car"]) is None:
        return "JSON 객체와 car.vin 또는 car.plate가 필요합니다"
    if p.get("type") == "logbook_archive":
        return next(("%s가 없습니다" % k for k in ("fileName", "xlsxBase64") if not isinstance(p.get(k), str) or not p[k]), None)
    e = p.get("entry")
    if p.get("type") != "ledger_entry" or not isinstance(e, dict):
        return "type은 ledger_entry(entry 포함) 또는 logbook_archive여야 합니다"
    for k, kind in (("seq", int), ("epochMs", int), ("tripId", str), ("body", str), ("prevHash", str), ("hash", str)):
        v = e.get(k)
        if type(v) is not kind or (kind is str and not 0 < len(v) <= (MAX_ENTRY_BODY_CHARS if k == "body" else 200)):
            return "entry.%s가 올바르지 않습니다" % k
    if e.get("kind") not in KINDS or e["seq"] < 1 or not 0 <= e["epochMs"] <= MAX_EPOCH_MS:
        return "entry.kind, seq 또는 epochMs 범위가 올바르지 않습니다"
    return None


class Car:
    """One vehicle's chain state; entries are keyed by seq and bounded by MAX_ENTRIES_PER_CAR."""
    def __init__(self, key):
        self.key, self.meta = key, {"vin": None, "plate": None, "model": None, "entity": None}
        self.by_seq, self.trip_ids, self.floor = {}, set(), 0     # floor = highest trimmed seq
        self.last_seq, self.last_hash = 0, GENESIS
        self.status, self.alerts, self.view = OK, [], None        # view None = projection stale
        self.idem_q, self.idem_s = deque(), set()

    def update_meta(self, car):
        self.meta.update({k: v for k, v in car.items() if k in self.meta and isinstance(v, str) and v})

    def alert(self, msg, status=None):
        self.alerts.insert(0, msg)
        del self.alerts[MAX_ALERTS:]
        if status and RANK[status] > RANK[self.status]:
            self.status = status

    def remember(self, idem):
        if len(self.idem_q) >= MAX_IDEMPOTENCY_KEYS:
            self.idem_s.discard(self.idem_q.popleft())
        self.idem_q.append(idem)
        self.idem_s.add(idem)

    def is_dup(self, e):
        old = self.by_seq.get(e["seq"])
        return e["seq"] <= self.floor or (old is not None and old["hash"] == e["hash"])

    def add_entry(self, raw):
        """Verify the hash chain and store one entry (callers skip is_dup entries first)."""
        seq = raw["seq"]
        try:
            body = json.loads(raw["body"])
        except (ValueError, RecursionError):
            body = None
        e = dict(raw, bodyObj=body if isinstance(body, dict) else None, status="ok")
        if entry_hash(e["prevHash"], seq, e["epochMs"], e["kind"], e["tripId"], e["body"]) != e["hash"]:
            e["status"] = "hash_mismatch"
        elif e["bodyObj"] is None:
            e["status"] = "bad_body"
        if seq in self.by_seq:                   # same seq, different hash (same hash is is_dup)
            self.alert("같은 번호의 다른 기록이 도착했습니다 (seq %d)" % seq, TAMPER)
            return
        if seq <= self.last_seq:                 # late arrival into an earlier gap
            self.alert("늦게 도착한 기록 (seq %d)" % seq)
        else:
            expect = self.last_seq + 1
            if seq > expect or (self.last_seq == 0 and e["prevHash"] != GENESIS):   # first record must be genesis
                self.alert("중간 기록 누락 (seq %d~%d)" % (expect, max(seq - 1, expect)), GAP)
            elif e["prevHash"] != self.last_hash and e["status"] == "ok":
                e["status"] = "chain_break"
            self.last_seq, self.last_hash = seq, e["hash"]
        if e["status"] in BAD_ENTRY:
            self.alert("기록 변조 의심 (seq %d): %s" % (seq, BAD_ENTRY[e["status"]]), TAMPER)
        if e["kind"] in ("TRIP", "GAP"):
            self.trip_ids.add(e["tripId"])
        elif not {e["tripId"], txt((e["bodyObj"] or {}).get("id"))} & self.trip_ids:
            self.alert("원장에 없는 운행 (%s)" % e["tripId"])
        self.by_seq[seq] = e
        if len(self.by_seq) > MAX_ENTRIES_PER_CAR:        # keep the newest entries, say so once
            if not self.floor:
                self.alert("오래된 기록은 정리되었습니다 (최근 %d건만 유지)" % MAX_ENTRIES_PER_CAR)
            self.floor = min(self.by_seq)
            gone = self.by_seq.pop(self.floor)
            if gone["kind"] in ("TRIP", "GAP"):
                self.trip_ids.discard(gone["tripId"])
        self.view = None

def project(entries):
    """Fold seq-ordered entries into {tripId: trip}; CLASSIFY/AMEND for unknown trips are skipped."""
    trips = {}
    for e in entries:
        b, bad = e["bodyObj"] or {}, e["status"] in BAD_ENTRY
        if e["kind"] in ("TRIP", "GAP"):
            start, gap = epoch(b.get("startEpochMs"), e["epochMs"]), e["kind"] == "GAP"
            trips[e["tripId"]] = {
                "id": e["tripId"], "start": start, "end": epoch(b.get("endEpochMs"), start),
                "so": num(b.get("startOdometerKm")), "eo": num(b.get("endOdometerKm")),
                "purpose": "UNLOGGED_GAP" if gap else "UNCLASSIFIED", "user": "",
                "memo": txt(b.get("reason")) if gap else "", "by": "", "autoReason": "",
                "amended": False, "amendReason": "", "bad": bad}
            continue
        t = trips.get(e["tripId"]) or trips.get(txt(b.get("id")))
        if t is None:
            continue
        t["bad"] = t["bad"] or bad
        if b.get("purpose") in PURPOSES:
            t["purpose"] = b["purpose"]
        t.update(user=txt(b.get("userName")), memo=txt(b.get("memo")), by=txt(b.get("by")),
                 autoReason=txt(b.get("autoReason")))
        if e["kind"] == "AMEND":
            t["amended"], t["amendReason"] = True, txt(b.get("reason"))
    return trips

def stats(rows, label):
    s = dict.fromkeys(("totalKm", "businessKm", "commuteKm", "generalBusinessKm", "personalKm", "gapKm"), 0.0)
    s.update(ym=label, unclassifiedCount=0)
    for t in rows:
        s["totalKm"] += t["dist"]
        if t["purpose"] in KM_BUCKET:
            s[KM_BUCKET[t["purpose"]]] += t["dist"]
        else:
            s["unclassifiedCount"] += 1
    s["businessKm"] = s["commuteKm"] + s["generalBusinessKm"]    # 업무용 = 출퇴근 + 일반 업무
    ratio = s["businessKm"] / s["totalKm"] * 100 if s["totalKm"] > 0 else None
    return dict({k: round(v, 1) if isinstance(v, float) else v for k, v in s.items()},
                ratioPct=None if ratio is None else round(ratio, 1))

def build_view(car):
    """Projection -> month/year stats (of the newest trip's KST month/year) + recent trips."""
    rows = list(project(sorted(car.by_seq.values(), key=lambda e: e["seq"])).values())
    for t in rows:
        t["dist"] = round(max(t["eo"] - t["so"], 0.0), 1) if None not in (t["so"], t["eo"]) else 0.0
        t["dt"] = to_kst(t["start"])
    rows.sort(key=lambda t: t["start"], reverse=True)
    if not rows:
        return {"month": stats([], None), "year": stats([], None), "recentTrips": []}
    ym = rows[0]["dt"].strftime("%Y-%m")
    recent = []
    for t in rows[:MAX_RECENT]:
        a, b = t["dt"], to_kst(t["end"])
        recent.append({
            "id": t["id"], "date": "%s(%s)" % (a.strftime("%Y-%m-%d"), WEEKDAYS[a.weekday()]),
            "time": "%s~%s" % (a.strftime("%H:%M"), b.strftime("%H:%M")),
            "startOdometerKm": t["so"], "endOdometerKm": t["eo"], "distanceKm": t["dist"],
            "purpose": t["purpose"], "purposeLabel": LABELS[t["purpose"]], "user": t["user"],
            "memo": t["memo"], "by": t["by"], "autoReason": t["autoReason"], "amended": t["amended"],
            "amendReason": t["amendReason"], "status": TAMPER if t["bad"] else OK})
    return {"month": stats([t for t in rows if t["dt"].strftime("%Y-%m") == ym], ym),
            "year": stats([t for t in rows if t["dt"].year == rows[0]["dt"].year], ym[:4]),
            "recentTrips": recent}


class Store:
    """All shared state behind ONE lock; persistence = per-car jsonl + archives dir."""
    def __init__(self, data_dir):
        self.lock = threading.Lock()
        self.dir = os.path.abspath(data_dir)
        self.arch_dir = os.path.join(self.dir, "archives")
        os.makedirs(self.arch_dir, exist_ok=True)
        self.cars, self.archives = {}, []          # archives: oldest first
        self._replay()

    def _car(self, key):
        if key not in self.cars and len(self.cars) < MAX_CARS:
            self.cars[key] = Car(key)
        return self.cars.get(key)

    def _append(self, key, obj):
        path = os.path.join(self.dir, sanitize(key) + ".jsonl")
        with open(path, "a", encoding="utf-8", errors="backslashreplace") as f:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")

    def _register(self, rec):
        self.archives = [a for a in self.archives if a["savedAs"] != rec["savedAs"]] + [rec]
        while len(self.archives) > MAX_ARCHIVES:
            try:
                os.remove(os.path.join(self.arch_dir, self.archives.pop(0)["savedAs"]))
            except OSError as exc:
                sys.stderr.write("%s 보관 파일 삭제 실패: %s\n" % (now_iso(), type(exc).__name__))

    def _rec(self, key, p, name, at):
        return {"key": key, "savedAs": name, "receivedAt": at, "fileName": txt(p["fileName"]),
                "periodStart": txt(p.get("periodStart")), "periodEnd": txt(p.get("periodEnd")),
                "verificationCode": txt(p.get("verificationCode"))}

    def _replay(self):
        pending, skipped = [], 0
        for fn in sorted(os.listdir(self.dir)):
            if not fn.endswith(".jsonl"):
                continue
            with open(os.path.join(self.dir, fn), encoding="utf-8", errors="replace") as f:
                for line in f:
                    try:
                        self._replay_one(json.loads(line), pending)
                    except (ValueError, TypeError, KeyError, AttributeError, RecursionError):
                        skipped += 1
        for rec in sorted(pending, key=lambda a: a["receivedAt"]):    # files replay per car: restore global order
            self._register(rec)
        if skipped:
            sys.stderr.write("%s 저장 파일에서 %d줄을 건너뛰었습니다\n" % (now_iso(), skipped))

    def _replay_one(self, p, pending):
        key = car_key(p["car"])
        car = self._car(key) if key else None
        if car is None:
            raise ValueError("car")
        car.update_meta(p["car"])
        if p.get("type") == "logbook_archive":
            name = sanitize(p["savedAs"])
            if os.path.isfile(os.path.join(self.arch_dir, name)):
                pending.append(self._rec(key, p, name, txt(p["receivedAt"])))
        elif validate(p) is None and not car.is_dup(p["entry"]):
            car.add_entry(p["entry"])

    def ingest(self, p, idem=None):
        """Apply one webhook payload -> (http_status, body). Never raises on bad input."""
        err = validate(p)
        if err:
            return 400, {"ok": False, "error": err}
        data = None
        if p["type"] == "logbook_archive":
            try:
                data = base64.b64decode(p["xlsxBase64"], validate=True)
            except (binascii.Error, ValueError):
                return 400, {"ok": False, "error": "xlsxBase64가 올바른 base64가 아닙니다"}
        with self.lock:
            car = self._car(car_key(p["car"]))
            if car is None:
                return 429, {"ok": False, "error": "차량 수 한도(%d대)를 넘었습니다" % MAX_CARS}
            if idem and idem in car.idem_s:
                return 200, {"ok": True, "duplicate": True}
            try:
                dup = self._apply(car, p, data)
            except OSError as exc:
                sys.stderr.write("%s 저장 실패: %s\n" % (now_iso(), type(exc).__name__))
                return 500, {"ok": False, "error": "저장에 실패했습니다"}
            if idem:
                car.remember(idem)
        return 200, ({"ok": True, "duplicate": True} if dup else {"ok": True})

    def _apply(self, car, p, data):
        car.update_meta(p["car"])
        if p["type"] == "ledger_entry":
            if car.is_dup(p["entry"]):
                return True
            self._append(car.key, p)               # persist first: memory never runs ahead of disk
            car.add_entry(p["entry"])
            return False
        name = sanitize("%s_%s" % (car.key, p["fileName"]))
        path = os.path.join(self.arch_dir, name)
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
        rec = self._rec(car.key, p, name, now_iso())
        line = {k: v for k, v in p.items() if k != "xlsxBase64"}
        self._append(car.key, dict(line, savedAs=name, receivedAt=rec["receivedAt"]))
        self._register(rec)
        return False

    def archive_path(self, name):
        with self.lock:
            known = any(a["savedAs"] == name for a in self.archives)
        return os.path.join(self.arch_dir, name) if known else None

    def state(self):
        with self.lock:
            cars = []
            for car in self.cars.values():
                if car.view is None:
                    car.view = build_view(car)
                h = car.last_hash if car.last_seq else ""
                arch = [dict({k: a[k] for k in ARCHIVE_PUBLIC}, url="/archives/" + quote(a["savedAs"]))
                        for a in reversed(self.archives) if a["key"] == car.key]
                cars.append({"key": car.key, **car.meta, "chainStatus": car.status, "entries": len(car.by_seq),
                             "lastSeq": car.last_seq, "lastHashShort": (h[:4] + "-" + h[4:8]).upper() if h else "",
                             **car.view, "archives": arch, "alerts": list(car.alerts)})
            return {"generatedAt": now_iso(), "cars": cars}


class Handler(BaseHTTPRequestHandler):
    timeout = SOCKET_TIMEOUT_S

    def log_request(self, code="-", size="-"):     # method, path, status only; never bodies
        path = getattr(self, "path", "-").encode("ascii", "backslashreplace").decode()[:200]   # unset on a malformed request line
        sys.stderr.write("%s %s %s %s\n" % (now_iso(), self.command or "-", path, code))

    def log_message(self, fmt, *args):             # protocol-level errors: same KST stamp, control chars escaped
        sys.stderr.write("%s %s\n" % (now_iso(), ascii(fmt % args)[1:-1][:200]))

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=()):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8", "replace")
        self.send_response(code)
        for k, v in [("Content-Type", ctype), ("Content-Length", str(len(body))), ("Cache-Control", "no-store"), *extra]:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _file(self, path, ctype, extra=()):
        if path is None or not os.path.isfile(path):
            return self._send(404, {"ok": False, "error": "없는 파일입니다"})
        with open(path, "rb") as f:
            self._send(200, f.read(), ctype, extra)

    def _guard(self, fn):
        try:
            fn()
        except ConnectionError:                         # client went away (HTTP/1.0: the connection closes anyway)
            pass
        except Exception as exc:                        # never crash on bad input; log the type only
            sys.stderr.write("%s ERROR %s\n" % (now_iso(), type(exc).__name__))
            self._send(500, {"ok": False, "error": "서버 내부 오류"})

    do_GET = lambda self: self._guard(self._get)
    do_POST = lambda self: self._guard(self._post)

    def _get(self):
        path, store = self.path.split("?", 1)[0], self.server.store
        if path == "/":
            return self._file(os.path.join(HERE, "dashboard.html"), "text/html; charset=utf-8")
        if path == "/api/state":
            return self._send(200, store.state())
        if path == "/healthz":
            return self._send(200, b"ok", "text/plain; charset=utf-8")
        if path.startswith("/archives/"):               # only registered names resolve: no traversal
            name = unquote(path[len("/archives/"):])
            fallback = re.sub(r'[^\x21-\x7e]|["\\]', "_", name)
            disp = "attachment; filename=\"%s\"; filename*=UTF-8''%s" % (fallback, quote(name, safe=""))
            return self._file(store.archive_path(name), XLSX_MIME, [("Content-Disposition", disp)])
        self._send(404, {"ok": False, "error": "없는 경로입니다"})

    def _post(self):
        if self.path.split("?", 1)[0] != "/hook":
            return self._send(404, {"ok": False, "error": "없는 경로입니다"})
        length = self.headers.get("Content-Length", "")
        if not length.isdecimal():
            return self._send(411, {"ok": False, "error": "Content-Length가 필요합니다"})
        if len(length) > 12 or int(length) > MAX_BODY_BYTES:    # rejected before reading any body byte
            return self._send(413, {"ok": False, "error": "본문이 너무 큽니다 (최대 15MB)"})
        try:
            payload = json.loads(self.rfile.read(int(length)).decode("utf-8"))
        except (UnicodeDecodeError, ValueError, RecursionError):
            return self._send(400, {"ok": False, "error": "JSON을 읽을 수 없습니다"})
        idem = (self.headers.get("Idempotency-Key") or "")[:200] or None
        self._send(*self.server.store.ingest(payload, idem))

def make_server(data_dir, bind="127.0.0.1", port=8787):
    server = ThreadingHTTPServer((bind, port), Handler)
    server.store = Store(data_dir)
    return server

def main(argv=None):
    ap = argparse.ArgumentParser(description="오토브리프 관리자 콘솔 (차량 원장 웹훅 수신 + 대시보드)")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--data", default="./fleet-data")
    a = ap.parse_args(argv)
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(errors="replace")      # cp1252 Windows consoles must not crash on Hangul
    try:
        server = make_server(a.data, a.bind, a.port)
    except OSError as exc:
        sys.exit("시작 실패: %s" % exc)
    sys.stderr.write("%s 콘솔 시작: http://%s:%d/ (데이터: %s)\n" % (now_iso(), a.bind, a.port, server.store.dir))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
