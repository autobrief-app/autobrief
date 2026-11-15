#!/usr/bin/env python3
"""Probe (no verdict): what the Pleos Connect display shows when the navigation app is opened while AutoBrief is on
screen, and how the window handle changes the split. Saves a screenshot, the activity dump and the window frames at
each step so the layout work can be measured against the real windows.

Usage: python3 demo/split_probe.py --out e2e-out/split
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from setup_emulator import (
    APP_ACTIVITY,
    APP_PACKAGE,
    NAV_PACKAGE,
    Adb,
    AdbError,
    parse_current_user,
)  # noqa: E402

KEEP = (
    "Task{",
    "ActivityRecord{",
    "mBounds=",
    "bounds=",
    "mWindowingMode",
    "windowingMode",
    "Resumed",
    "visible=",
    "mLastReportedConfiguration",
    "TaskView",
    "taskId=",
)


def capture(adb: Adb, out: str, name: str) -> None:
    try:
        with open(os.path.join(out, name + ".png"), "wb") as f:
            f.write(adb.screencap_png())
    except AdbError as exc:
        print("  screenshot %s failed: %s" % (name, exc))
    for cmd, suffix in (
        (("dumpsys", "activity", "activities"), "activities"),
        (("dumpsys", "window", "windows"), "windows"),
    ):
        try:
            text = adb.shell(*cmd, timeout=60)
        except AdbError as exc:
            text = "failed: %s" % exc
        with open(
            os.path.join(out, "%s.%s.txt" % (name, suffix)), "w", encoding="utf-8"
        ) as f:
            f.write(text)
        if suffix == "activities":
            for line in text.splitlines():
                if (APP_PACKAGE in line or NAV_PACKAGE in line) and any(
                    k in line for k in KEEP
                ):
                    print("    " + line.strip()[:230])
    try:
        ui = adb.shell(
            "uiautomator", "dump", "--windows", "/sdcard/split_ui.xml", timeout=60
        )
        xml = adb.shell("cat", "/sdcard/split_ui.xml", timeout=60)
        with open(os.path.join(out, name + ".ui.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
        print("    ui dump: %s, %d bytes" % (ui.strip()[:60], len(xml)))
    except AdbError as exc:
        print("  ui dump %s failed: %s" % (name, exc))
    print("captured", name)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="e2e-out/split")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    adb = Adb(shutil.which("adb") or "adb", serial=None)
    user = parse_current_user(adb.shell("am", "get-current-user")) or 0
    size = adb.shell("wm", "size").strip()
    print("user", user, "|", size, "|", adb.shell("wm", "density").strip())

    def start(*args: str) -> None:
        print(
            "  am start:",
            adb.shell("am", "start", "-W", "--user", str(user), *args, timeout=90)
            .strip()
            .replace("\n", " | ")[:300],
        )

    start("-n", APP_ACTIVITY)
    time.sleep(5)
    capture(adb, a.out, "s0_app_alone")

    # 1. the navigation app's launcher entry, as a tap on its icon would do
    start(
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
        "-p",
        NAV_PACKAGE,
    )
    time.sleep(8)
    capture(adb, a.out, "s1_navigation_opened")

    # 2. the app again, as a tap on its icon would do
    start("-n", APP_ACTIVITY)
    time.sleep(6)
    capture(adb, a.out, "s2_app_reopened")

    # 3. the window handle at the right edge of the application area (2560x1440 display): drag it left, as a
    #    driver does to put two apps side by side
    for n, (x1, x2) in enumerate(((2546, 1700), (2546, 1280)), start=3):
        adb.shell("input", "swipe", str(x1), "720", str(x2), "720", "700")
        time.sleep(6)
        capture(adb, a.out, "s%d_handle_dragged_to_%d" % (n, x2))

    # 4. a tap on the handle (the guide says it swaps the two windows)
    adb.shell("input", "tap", "2546", "720")
    time.sleep(5)
    capture(adb, a.out, "s5_handle_tapped")

    # 5. the app once more, to see which window it lands in when two are shown
    start("-n", APP_ACTIVITY)
    time.sleep(6)
    capture(adb, a.out, "s6_app_started_in_split")


if __name__ == "__main__":
    main()
