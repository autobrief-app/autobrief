#!/usr/bin/env python3
"""Tamper demo for the presentation: rewrite one past purpose the way a dishonest resync would.

Reads the console's own stored copy (<data>/*.jsonl), takes the newest CLASSIFY entry whose purpose is
PERSONAL, changes it to BUSINESS, recomputes the hash with the same previous hash (anyone can compute
SHA-256), and posts it as a "resent" entry. The console already holds a different hash for that sequence
number, so it flags the car 변조 의심. Nothing on the car is touched.

Usage: python3 forge_demo.py [--console http://127.0.0.1:8787] [--data ./fleet-data]
"""

import argparse
import glob
import hashlib
import json
import os
import sys
import urllib.request


def entry_hash(prev_hash, seq, epoch_ms, kind, trip_id, body):
    text = "%s|%s|%s|%s|%s|%s" % (prev_hash, seq, epoch_ms, kind, trip_id, body)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def newest_personal_classify(data_dir):
    best = None
    for path in glob.glob(os.path.join(data_dir, "*.jsonl")):
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    payload = json.loads(line)
                except ValueError:
                    continue
                if payload.get("type") != "ledger_entry":
                    continue
                entry = payload.get("entry") or {}
                if entry.get("kind") != "CLASSIFY":
                    continue
                try:
                    body = json.loads(entry.get("body", ""))
                except ValueError:
                    continue
                if body.get("purpose") == "PERSONAL" and (
                    best is None or entry["seq"] > best[1]["seq"]
                ):
                    best = (payload, entry, body)
    return best


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--console", default="http://127.0.0.1:8787")
    ap.add_argument("--data", default="./fleet-data")
    args = ap.parse_args()

    found = newest_personal_classify(args.data)
    if found is None:
        print(
            "비업무(개인)로 정해진 기록을 찾지 못했습니다. 관리자 화면에 기록이 들어온 뒤 다시 실행해 주세요."
        )
        return 1
    payload, entry, body = found
    body["purpose"] = "BUSINESS"
    body["memo"] = (body.get("memo") or "") + " (거래처 방문으로 고침)"
    new_body = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    forged = dict(entry, body=new_body)
    forged["hash"] = entry_hash(
        entry["prevHash"],
        entry["seq"],
        entry["epochMs"],
        entry["kind"],
        entry["tripId"],
        new_body,
    )
    out = dict(payload, entry=forged)
    req = urllib.request.Request(
        args.console.rstrip("/") + "/hook",
        data=json.dumps(out, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Idempotency-Key": "forge-%s-%s" % (entry["seq"], forged["hash"][:8]),
        },
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        status = r.status
    print(
        "위조 기록을 보냈습니다: 순번 %s, 개인 운행을 업무로 바꾼 내용 (HTTP %s)"
        % (entry["seq"], status)
    )
    print(
        "관리자 화면에서 이 차의 상태가 '변조 의심'으로 바뀌었는지 확인하세요. 차의 기록은 바뀌지 않았습니다."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
