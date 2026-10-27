#!/usr/bin/env python3
"""Rehearse the whole 7-minute script of docs/DEMO.md, unattended, on the Pleos Connect emulator.

Scenes 1-3 drive the car with director.Director (the plans of `director.py --scenario all`). From scene 3 on, the app is
used the way the presenter uses it: real taps on elements found in `uiautomator dump` by their visible text (no fixed
coordinates). Every step waits for the screen state, log line or console record it expects; one that does not appear
fails its scene loudly (a screenshot and the UI dump are saved) and the rehearsal goes on with the next scene. Nothing in
the app is changed for the rehearsal. Scene 6 sends the workbook with '지금 보내기' and downloads it from the console's
운행기록부 보관함, the way the presenter gets it onto the laptop (adb may not read the driver user's app storage: on the
Pleos image both `adb pull` and `run-as` are refused). With --pause-recording-flag (CI), the screen recording is
stopped while the app writes the workbook, which it does on its main thread: on a 2-core runner the software video
encoder starved that thread past Android's 5 s input limit, and car UI mode closes an app without a dialog. Scene 7 sends Gleo's request through the debug APK's relay (adb cannot hold Gleo's
signature permission): it reproduces the request as a debug signal and is never speech.

Before scene 1 it waits until the fleet console holds the app's whole ledger (the console shows the verification code the
app shows), the state docs/DEMO.md prepares the day before.

Writes into --out: scenes.json (the timeline for the video compositor: host epoch seconds, captions, events, verdicts,
device-host clock samples, console screenshots, recording pauses), screenshots, failure dumps, the downloaded workbook
and gleo_reply.txt.

Python 3.9+ standard library only. Exit codes: 0 every scene passed, 1 a scene failed, 2 adb/SDK missing or usage,
3 the vehicle console or the fleet console is unreachable.
"""

import argparse
import contextlib
import http.client
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Sequence, Tuple

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import director  # noqa: E402  (the drive plans and the console client live there)
from setup_emulator import (  # noqa: E402
    APP_PACKAGE,
    DEFAULT_SERIAL,
    Adb,
    AdbError,
    ConsoleError,
    SetupError,
    configure_stdio,
    console_url,
    decode,
    demo_broadcast_args,
    find_sdk_tools,
    parse_current_user,
    say,
)

REPO = os.path.dirname(_HERE)
FORGE_SCRIPT = os.path.join(REPO, "tools", "fleet-console", "forge_demo.py")

# --------------------------------------------------------------------------- constants

TIMELINE_FILE = "scenes.json"
MAIN_DISPLAY = (
    0  # `input tap` goes to display 0; other displays (a cluster) are not touched
)
REMOTE_DUMP = "/data/local/tmp/autobrief-rehearsal-ui.xml"
DUMP_TRIES = (
    4  # `uiautomator dump` fails now and then while the screen is still changing
)
DUMP_RETRY_S = 1.0
DUMP_TIMEOUT_S = 30.0  # a dump takes seconds; a hung one is cut and retried
UI_POLL_S = 1.0
UI_WAIT_S = 30.0  # a screen state the app shows at once (a tap, a closed trip)
MAX_SCROLLS = 4  # the dump lists only what is on screen: a missing element below the fold is scrolled to
SWIPE_MS = 500
LOG_WAIT_S = 30.0
CONSOLE_WAIT_S = (
    120.0  # the outbox sends in order, paced, and flushes at least every 30 s
)
PREP_WAIT_S = 1200.0  # the whole sample ledger (972 entries) reaches the console at about 3 entries a second
PREP_POLL_S = 5.0
PREP_CODE_EVERY_S = 60.0  # re-read the app's verification code this often while waiting
CAPTION_LEAD_S = 3.0  # a scene's caption is on screen this long before the scene acts
HOLD_S = 4.0  # a scene's result stays on screen this long before the next caption
RECORDER_WAIT_S = 20.0  # screenrecord closes its file after SIGINT; the CI loop starts the next one within a second
RECORDER_POLL_S = 0.5
CHROME_TIMEOUT_S = 90.0
CONSOLE_SHOT_SIZE = (960, 1440)  # CSS px; the console's table needs 800 px
CHROME_BUDGET_MS = (
    6000  # virtual time for the page's first /api/state poll and its web font
)

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_TOOLS = 2
EXIT_CONSOLE = 3

CORRECTION_REASON = "개인 용무를 겸했어요"  # one of the app's reason chips (ui/Components.kt CORRECTION_REASONS)
SEND_BUTTON = "지금 보내기"  # ReportScreen: writes the workbook on the car and queues it for the console (보내기 주소)
GLEO_FUNCTION = "GET_LOGBOOK_STATUS"
GLEO_PARAMS = '{"period":"THIS_MONTH"}'
CONSOLE_OK = "정상"
CONSOLE_TAMPER = "변조 의심"
TAMPER_ALERT = "이미 받은 순번에 다른 기록이 도착했습니다 (순번 %d)"


@dataclass(frozen=True)
class SceneSpec:
    number: int
    key: str
    title: str
    caption: str  # shown in the video before and during the scene (the compositor reads it from scenes.json)


SCENES = (
    SceneSpec(
        1,
        "commute",
        "출근",
        "1. 출근: 집에서 출발해 18 km, 회사에서 P. 앱이 '출퇴근'으로 자동 기록합니다",
    ),
    SceneSpec(
        2,
        "client",
        "거래처",
        "2. 거래처: 회사에서 가상상사까지 11 km. 앱이 '일반 업무'로 자동 기록합니다",
    ),
    SceneSpec(
        3,
        "personal",
        "처음 가는 곳",
        "3. 처음 가는 곳: 등록 안 된 곳까지 7 km. 앱이 목적을 묻고, '비업무'를 누릅니다",
    ),
    SceneSpec(
        4,
        "correction",
        "정정",
        "4. 정정: 운행 목록에서 출근 기록을 '비업무'로 고치고 이유를 고릅니다",
    ),
    SceneSpec(
        5,
        "tamper",
        "위변조",
        "5. 위변조: 노트북에서 위조 기록 한 줄을 관리자 화면으로 보냅니다",
    ),
    SceneSpec(
        6,
        "export",
        "운행기록부",
        "6. 운행기록부: 사업연도 엑셀을 '지금 보내기'로 회사 서버에 보내고, 관리자 화면에서 받아 ⑪ ⑫ ⑬을 확인합니다",
    ),
    SceneSpec(
        7, "gleo", "음성", "7. 음성: Gleo 요청을 디버그 신호로 재현 (실제 음성 아님)"
    ),
)

# what the app's AutoConfirmCard says after an automatic classification (withRo in ui/Components.kt)
RECORDED_TITLE = {"COMMUTE": "출퇴근으로 기록했습니다", "BUSINESS": "일반 업무로 기록했습니다"}
NARRATION_LINE = re.compile(r"^(준비: |[①②③④] )")


WAITING = object()  # a reader's answer for "on screen but not ready yet" (a disabled button): wait, do not scroll


class StepFailed(Exception):
    """A screen state, log line or console record a step waits for did not appear in time."""

    def __init__(self, message: str, evidence: Optional[dict] = None):
        super().__init__(message)
        self.evidence = evidence or {}


# --------------------------------------------------------------------------- text


_INVISIBLE = dict.fromkeys(map(ord, "\u2060\u200b\ufeff"), None)


def normalize(text: Optional[str]) -> str:
    """Screen text without the app's invisible word joiners and with single spaces."""
    return " ".join((text or "").translate(_INVISIBLE).replace("\u00a0", " ").split())


def km_text(km: float) -> str:
    """The app's km1(): '45,200.0' (the Korean locale groups with commas)."""
    return "{:,.1f}".format(km)


def odometer_text(start: float, end: float) -> str:
    """The odometer cell of a row in the trips list (ui/TripsScreen.kt TripRow)."""
    return "%s → %s" % (km_text(start), km_text(end))


_NUMBER = re.compile(r"-?\d[\d,]*(?:\.\d+)?")


def parse_number(text: str) -> Optional[float]:
    """'9,654.0 km' -> 9654.0, '94.6%' -> 94.6; None without a number."""
    found = _NUMBER.search(text or "")
    return float(found.group(0).replace(",", "")) if found else None


def slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]+", "_", text).strip("_")[:40] or "step"


# --------------------------------------------------------------------------- the UI dump


@dataclass(frozen=True)
class UiNode:
    index: int
    parent: Optional[int]
    text: str
    desc: str
    bounds: Tuple[int, int, int, int]  # left, top, right, bottom on the screen
    clickable: bool = False
    enabled: bool = True
    scrollable: bool = False
    window: int = 0  # the <window> it belongs to (0 for a single-window dump)
    layer: int = 0  # that window's layer (z-order: higher is on top)
    display: int = MAIN_DISPLAY

    @property
    def center(self) -> Tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    @property
    def area(self) -> int:
        left, top, right, bottom = self.bounds
        return max(0, right - left) * max(0, bottom - top)


class UiDumpError(Exception):
    pass


_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_bounds(text: str) -> Tuple[int, int, int, int]:
    found = _BOUNDS.fullmatch((text or "").strip())
    return tuple(int(v) for v in found.groups()) if found else (0, 0, 0, 0)  # type: ignore[return-value]


def parse_ui_dump(xml_text: str) -> List[UiNode]:
    """Every <node> of a `uiautomator dump` (the active window) or `uiautomator dump --windows` (every window of every
    display), in document order, with its parent's index, its window and that window's display. The platform dumper
    leaves out nodes that are not visible and clips bounds to the screen."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise UiDumpError("UI 덤프를 읽지 못했습니다: %s" % exc)
    nodes: List[UiNode] = []
    windows = [-1]

    def walk(element, parent: Optional[int], window: int, display: int, layer: int = 0) -> None:
        if element.tag == "display":
            display = int(element.get("id") or MAIN_DISPLAY)
        elif element.tag == "window":
            windows[0] += 1
            window = windows[0]
            raw = element.get("layer") or ""
            layer = int(raw) if raw.lstrip("-").isdigit() else 0
        elif element.tag == "node":
            node = UiNode(
                index=len(nodes),
                parent=parent,
                text=element.get("text", ""),
                desc=element.get("content-desc", ""),
                bounds=parse_bounds(element.get("bounds", "")),
                clickable=element.get("clickable") == "true",
                enabled=element.get("enabled", "true") == "true",
                scrollable=element.get("scrollable") == "true",
                window=window,
                display=display,
                layer=layer,
            )
            nodes.append(node)
            parent = node.index
        for child in element:
            walk(child, parent, window, display, layer)

    walk(root, None, 0, MAIN_DISPLAY)
    return nodes


def has_text(node: UiNode, text: str, exact: bool = True) -> bool:
    """Exact: the node says `text` (also as one part of a merged Compose label "비업무, 개인 용무"). Not exact: contains."""
    want = normalize(text)
    for value in (node.text, node.desc):
        got = normalize(value)
        if not got:
            continue
        if not exact:
            if want in got:
                return True
        elif got == want or want in (
            normalize(part) for part in re.split(r"\n|, ", value)
        ):
            return True
    return False


def find_all(
    nodes: Sequence[UiNode],
    text: str,
    exact: bool = True,
    below: Optional[UiNode] = None,
    display: int = MAIN_DISPLAY,
) -> List[UiNode]:
    """Nodes on `display` that show `text`. With `below`: only those in the anchor's window from the anchor's top down,
    nearest first (the anchor is the heading the target sits under, e.g. '이번 운행의 목적을 골라 주세요')."""
    found = [
        n
        for n in nodes
        if n.display == display and n.area > 0 and has_text(n, text, exact)
    ]
    if below is None:
        return found
    found = [
        n
        for n in found
        if n.window == below.window
        and n.index != below.index
        and n.bounds[1] >= below.bounds[1]
    ]
    return sorted(found, key=lambda n: (n.bounds[1] - below.bounds[1], n.area))


def ancestors(nodes: Sequence[UiNode], node: UiNode) -> List[UiNode]:
    out = []
    while node.parent is not None:
        node = nodes[node.parent]
        out.append(node)
    return out


def clickable_ancestor(nodes: Sequence[UiNode], node: UiNode) -> UiNode:
    """What a finger on this text presses: the node itself or the nearest clickable ancestor (a Compose button's text
    is a child of the clickable node)."""
    for candidate in [node] + ancestors(nodes, node):
        if candidate.clickable:
            return candidate
    return node


def unique_targets(nodes: Sequence[UiNode], matches: Sequence[UiNode]) -> List[UiNode]:
    seen, out = set(), []
    for match in matches:
        target = clickable_ancestor(nodes, match)
        if target.index not in seen:
            seen.add(target.index)
            out.append(target)
    return out


def descendants(nodes: Sequence[UiNode], top: UiNode) -> List[UiNode]:
    """The subtree under `top` (document order keeps a subtree contiguous)."""
    inside, out = {top.index}, []
    for node in nodes[top.index + 1 :]:
        if node.parent not in inside:
            break
        inside.add(node.index)
        out.append(node)
    return out


def subtree_texts(nodes: Sequence[UiNode], top: UiNode) -> List[str]:
    return [
        normalize(n.text) for n in [top] + descendants(nodes, top) if normalize(n.text)
    ]


def value_right_of(nodes: Sequence[UiNode], label: UiNode) -> Optional[UiNode]:
    """The nearest text to the right of `label` on the same line (a label/value row such as '⑪ ... 9,654.0 km')."""
    cy = label.center[1]
    same_line = [
        n
        for n in nodes
        if n.window == label.window
        and normalize(n.text)
        and n.bounds[0] >= label.bounds[2] - 2
        and n.bounds[1] <= cy <= n.bounds[3]
    ]
    return min(same_line, key=lambda n: n.bounds[0]) if same_line else None


def scroll_container(nodes: Sequence[UiNode], anchor: Optional[UiNode] = None) -> Optional[UiNode]:
    """The scrolling panel around `anchor`; without an anchor on screen, the largest one in the topmost window."""
    if anchor is not None:
        for node in ancestors(nodes, anchor):
            if node.scrollable:
                return node
    panels = [n for n in nodes if n.scrollable and n.display == MAIN_DISPLAY and n.area > 0]
    return max(panels, key=lambda n: (n.layer, n.window, n.area)) if panels else None


# --------------------------------------------------------------------------- screen readers (one per screen state)


def trip_row(nodes: Sequence[UiNode], odometers: str) -> Optional[UiNode]:
    """The trips-list row whose odometer cell reads `odometers` (the row is the clickable ancestor)."""
    hits = find_all(nodes, odometers)
    return clickable_ancestor(nodes, hits[0]) if hits else None


def amended_row(
    nodes: Sequence[UiNode], odometers: str, purpose_label: str
) -> Optional[List[str]]:
    """The texts of that row once it shows the new purpose and the '수정됨' mark, else None."""
    row = trip_row(nodes, odometers)
    if row is None:
        return None
    texts = subtree_texts(nodes, row)
    if purpose_label in texts and any("수정됨" in t for t in texts):
        return texts
    return None


def last_trip_line(nodes: Sequence[UiNode], purpose_label: str, km: str) -> Optional[List[str]]:
    """The home card's '마지막 운행' line (purpose chip, 'MM-dd HH:mm · 7.0 km') once it shows `purpose_label` and `km`.
    A purpose picked on the screen closes the 'just ended' card, so this line is where the pick shows."""
    labels = find_all(nodes, "마지막 운행")
    if not labels:
        return None
    label, cy = labels[0], labels[0].center[1]
    texts = [
        normalize(n.text)
        for n in nodes
        if n.window == label.window and normalize(n.text) and n.bounds[0] >= label.bounds[2] - 2 and n.bounds[1] <= cy <= n.bounds[3]
    ]
    if purpose_label in texts and any(t.endswith(km) for t in texts):
        return texts
    return None


_CODE = re.compile(r"검증 코드 ([0-9A-F]{4}-[0-9A-F]{4})")


def app_ledger_code(nodes: Sequence[UiNode]) -> Optional[str]:
    """The verification code in the app's status bar ('원장 정상 · 검증 코드 AD90-6338')."""
    for node in find_all(nodes, "검증 코드", exact=False):
        found = _CODE.search(normalize(node.text))
        if found:
            return found.group(1)
    return None


FORM_MARKS = (("⑪", "총주행"), ("⑫", "업무용 사용거리"), ("⑬", "업무사용비율"))


def form_values(nodes: Sequence[UiNode]) -> Optional[Dict[str, dict]]:
    """⑪ ⑫ ⑬ of the report screen's form panel: {mark: {label, text, value}}, None until all three are shown."""
    out = {}
    for mark, words in FORM_MARKS:
        labels = [
            n
            for n in find_all(nodes, words, exact=False)
            if normalize(n.text).startswith(mark)
        ]
        value = value_right_of(nodes, labels[0]) if labels else None
        number = parse_number(value.text) if value is not None else None
        if number is None:
            return None
        out[mark] = {
            "label": normalize(labels[0].text),
            "text": normalize(value.text),
            "value": number,
        }
    return out


_SENT = re.compile(r"저장하고 전송을 시작했습니다: (\S+\.xlsx)")


def sent_workbook(nodes: Sequence[UiNode]) -> Optional[str]:
    """The file name from the report screen's '저장하고 전송을 시작했습니다: <file>' line ('지금 보내기')."""
    for node in find_all(nodes, "저장하고 전송을 시작했습니다:", exact=False):
        found = _SENT.search(normalize(node.text))
        if found:
            return found.group(1)
    return None


# --------------------------------------------------------------------------- device helpers


def parse_device_epoch(text: str) -> float:
    """`date +%s.%N` on the device -> epoch seconds. Toybox prints nanoseconds; a date without %N leaves the seconds
    and something else after the dot, and then the seconds are the sample (one second of resolution)."""
    found = re.match(r"\s*(\d{9,})(?:\.(\d+))?", text or "")
    if not found:
        raise ValueError("device time not understood: %r" % (text or "")[:80])
    return float("%s.%s" % (found.group(1), found.group(2) or "0"))


def measure_clock(adb, wall: Callable[[], float] = time.time) -> dict:
    """One sample of the device clock against this computer's: offset = device - host (the screen recording carries
    device time). rtt bounds the sample's error."""
    before = wall()
    device = parse_device_epoch(adb.shell("date", "+%s.%N"))
    after = wall()
    host = (before + after) / 2
    return {
        "host": host,
        "device": device,
        "offset": device - host,
        "rtt": after - before,
    }


_ANR = re.compile(r"\bANR in %s\b" % re.escape(APP_PACKAGE))


def anr_count(adb) -> int:
    """How many times Android closed the app for not responding (car UI mode closes it without a dialog)."""
    return len(_ANR.findall(adb.logcat_dump("ActivityManager", "E")))


def gleo_broadcast_args(
    user: int, function: str = GLEO_FUNCTION, params: str = GLEO_PARAMS
) -> List[str]:
    """The debug relay of docs/DEMO.md (`--es cmd gleo`), sent to the driver's user only."""
    return demo_broadcast_args("gleo", user=user) + [
        "--es",
        "function",
        function,
        "--es",
        "params",
        params,
    ]


_GLEO_REPLY = re.compile(r"\bGLEO_REPLY function=(\S+) status=(\S+) message=(.*)$")


def gleo_replies(logcat: str) -> List[dict]:
    out = []
    for line in logcat.splitlines():
        found = _GLEO_REPLY.search(line)
        if found:
            out.append(
                {
                    "function": found.group(1),
                    "status": found.group(2),
                    "message": found.group(3).strip(),
                    "line": line.strip(),
                }
            )
    return out


def recorder_running(adb) -> bool:
    """Whether the CI screen recording (`adb shell screenrecord`) runs on the device now."""
    rc, out, _ = adb.run("shell", "pidof screenrecord", check=False, timeout=30)
    return rc == 0 and bool(decode(out).strip())


_XLSX_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
_REF = re.compile(r"([A-Z]+)(\d+)")


def read_xlsx_totals(path: str) -> Dict[str, dict]:
    """⑪ ⑫ ⑬ of the logbook workbook: each label cell and the value cell under it ({mark: {label, cell, value}}).
    The form sheet keeps formulas with their cached values, and ⑬ is a ratio (0.946), not a percentage."""
    with zipfile.ZipFile(path) as book:
        sheets = sorted(
            n
            for n in book.namelist()
            if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", n)
        )
        for name in sheets:
            cells = {}
            for cell in ET.fromstring(book.read(name)).iter(_XLSX_NS + "c"):
                ref = cell.get("r", "")
                if cell.get("t") == "inlineStr":
                    cells[ref] = "".join(
                        t.text or "" for t in cell.iter(_XLSX_NS + "t")
                    )
                else:
                    value = cell.find(_XLSX_NS + "v")
                    if value is not None and value.text is not None:
                        cells[ref] = (
                            float(value.text)
                            if _NUMBER.fullmatch(value.text)
                            else value.text
                        )
            found = {}
            for mark, _ in FORM_MARKS:
                label = next(
                    (
                        r
                        for r, v in cells.items()
                        if isinstance(v, str) and v.startswith(mark)
                    ),
                    None,
                )
                if label is None:
                    break
                column, row = _REF.fullmatch(label).groups()
                below = "%s%d" % (column, int(row) + 1)
                found[mark] = {
                    "label": cells[label],
                    "cell": below,
                    "value": cells.get(below),
                }
            if len(found) == len(FORM_MARKS):
                return found
    raise StepFailed(
        "엑셀에서 ⑪ ⑫ ⑬ 칸을 찾지 못했습니다 (%s)" % os.path.basename(path)
    )


def compare_totals(screen: Dict[str, dict], book: Dict[str, dict]) -> List[str]:
    """What disagrees between the report screen and the workbook (empty = they agree to the printed precision)."""
    problems = []
    values = {mark: book[mark]["value"] for mark in book}
    if not all(isinstance(v, float) for v in values.values()):
        return ["엑셀의 ⑪ ⑫ ⑬ 값이 숫자가 아닙니다: %r" % values]
    for mark in ("⑪", "⑫"):
        if abs(values[mark] - screen[mark]["value"]) > 0.05:
            problems.append(
                "%s 화면 %s, 엑셀 %.1f" % (mark, screen[mark]["text"], values[mark])
            )
    if abs(values["⑬"] * 100 - screen["⑬"]["value"]) > 0.05:
        problems.append(
            "⑬ 화면 %s, 엑셀 %.1f%%" % (screen["⑬"]["text"], values["⑬"] * 100)
        )
    if values["⑪"] > 0 and abs(values["⑬"] - values["⑫"] / values["⑪"]) > 1e-9:
        problems.append(
            "엑셀 ⑬ %.6f 이 ⑫/⑪ %.6f 과 다릅니다"
            % (values["⑬"], values["⑫"] / values["⑪"])
        )
    if "사업연도" not in book["⑪"]["label"]:
        problems.append("엑셀 ⑪ 칸이 사업연도 합계가 아닙니다: %s" % book["⑪"]["label"])
    return problems


_FORGE_SEQ = re.compile(r"순번 (\d+)")


def parse_forge_seq(text: str) -> Optional[int]:
    found = _FORGE_SEQ.search(text or "")
    return int(found.group(1)) if found else None


# --------------------------------------------------------------------------- fleet console state readers


def console_trips(state: dict):
    for car in state.get("cars") or []:
        for trip in car.get("recentTrips") or []:
            yield car, trip


def _close(value, want: float) -> bool:
    return isinstance(value, (int, float)) and abs(value - want) < 0.05


def find_console_trip(
    state: dict, start: float, end: float, purpose: str, amended: Optional[bool] = None
) -> Optional[dict]:
    for car, trip in console_trips(state):
        if (
            _close(trip.get("startOdometerKm"), start)
            and _close(trip.get("endOdometerKm"), end)
            and trip.get("purpose") == purpose
            and (amended is None or trip.get("amended") is amended)
        ):
            return {
                "car": car.get("key"),
                "chainStatus": car.get("chainStatus"),
                "trip": trip,
            }
    return None


def console_archives(state: dict, car: Optional[str] = None) -> List[dict]:
    """The workbooks in the console's 운행기록부 보관함 (newest first), each with the key of its car."""
    out = []
    for entry in state.get("cars") or []:
        if car is None or entry.get("key") == car:
            out += [dict(item, car=entry.get("key")) for item in entry.get("archives") or []]
    return out


def archive_mark(item: dict) -> Tuple[str, str]:
    return item.get("url") or "", item.get("receivedAt") or ""


def new_archive(state: dict, file_name: str, seen, car: Optional[str] = None) -> Optional[dict]:
    """The stored workbook named `file_name` that was not there before (`seen` = archive_mark of the earlier ones)."""
    for item in console_archives(state, car):
        if item.get("fileName") == file_name and archive_mark(item) not in seen:
            return item
    return None


def ready_chain(state: dict, code: str) -> Optional[dict]:
    """The console copy that holds the app's whole ledger: its last hash shows the app's code and it is 정상."""
    for car in state.get("cars") or []:
        if car.get("lastHashShort") == code and car.get("chainStatus") == CONSOLE_OK:
            return {
                "car": car.get("key"),
                "label": car.get("label"),
                "entries": car.get("entries"),
                "code": code,
            }
    return None


def tamper_flag(state: dict, seq: Optional[int]) -> Optional[dict]:
    """The car the forged entry hit: status 변조 의심, the 'same sequence number, other content' alert and its row."""
    for car in state.get("cars") or []:
        if car.get("chainStatus") != CONSOLE_TAMPER:
            continue
        for alert in car.get("alerts") or []:
            text = alert.get("text") or ""
            if (
                alert.get("level") == "bad"
                and (seq is None or text == TAMPER_ALERT % seq)
                and "이미 받은 순번" in text
            ):
                row = next(
                    (
                        t
                        for t in car.get("recentTrips") or []
                        if t.get("id") == alert.get("tripId")
                    ),
                    None,
                )
                return {"car": car.get("key"), "alert": alert, "row": row}
    return None


# --------------------------------------------------------------------------- the timeline file


class Timeline:
    """scenes.json: everything the video compositor needs, rewritten after every change so a cut-short run keeps it."""

    def __init__(self, path: str, wall: Callable[[], float] = time.time):
        self.path, self.wall = path, wall
        self.data = {
            "version": 1,
            "tool": "demo/rehearsal.py",
            "started": wall(),
            "finished": None,
            "user": None,
            "clock": [],
            "prep": None,
            "console_shots": [],
            "scenes": [],
            "result": None,
        }
        self.current: Optional[dict] = None

    def save(self) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(self.data, handle, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def clock(self, sample: dict) -> None:
        self.data["clock"].append(sample)
        self.save()

    def begin(self, spec: SceneSpec) -> dict:
        self.current = {
            "number": spec.number,
            "key": spec.key,
            "title": spec.title,
            "caption": spec.caption,
            "start": self.wall(),
            "end": None,
            "ok": None,
            "detail": "",
            "events": [],
            "evidence": {},
        }
        self.data["scenes"].append(self.current)
        self.save()
        return self.current

    def event(self, text: str) -> None:
        if self.current is not None:
            self.current["events"].append({"t": self.wall(), "text": text})
            self.save()

    def end(self, ok: bool, detail: str, evidence: dict) -> None:
        if self.current is None:
            return
        self.current.update(end=self.wall(), ok=ok, detail=detail, evidence=evidence)
        self.current = None
        self.save()

    def console_shot(self, at: float, name: str, scene: int) -> None:
        self.data["console_shots"].append({"t": at, "file": name, "scene": scene})
        self.save()

    def finish(self) -> None:
        scenes = self.data["scenes"]
        self.data["finished"] = self.wall()
        self.data["result"] = {
            "passed": sum(1 for s in scenes if s["ok"]),
            "total": len(SCENES),
            "played": len(scenes),
        }
        self.save()


# --------------------------------------------------------------------------- the screen (uiautomator + input)


class UiDriver:
    """Reads the screen with `uiautomator dump --windows` and presses with `input tap`, both through adb."""

    def __init__(
        self,
        adb,
        out_dir: str,
        now: Optional[Callable[[], float]] = None,
        sleep: Optional[Callable[[float], None]] = None,
        say_fn: Callable[[str], None] = say,
        record: Optional[Callable[[str], None]] = None,
    ):
        self.adb, self.out_dir = adb, out_dir
        self.now = now or time.monotonic
        self.sleep = sleep or time.sleep
        self.say = say_fn
        self.record = record or (lambda text: None)
        self.last_xml = ""
        self.failures = 0

    def dump(self) -> List[UiNode]:
        last = ""
        for _ in range(DUMP_TRIES):
            try:
                self.adb.shell(
                    "rm", "-f", REMOTE_DUMP
                )  # never read the file of an earlier dump
                self.adb.shell(
                    "uiautomator",
                    "dump",
                    "--windows",
                    REMOTE_DUMP,
                    timeout=DUMP_TIMEOUT_S,
                )
                xml_text = self.adb.shell("cat", REMOTE_DUMP)
                nodes = parse_ui_dump(xml_text)
            except (AdbError, UiDumpError) as exc:
                last = str(exc)
            else:
                self.last_xml = xml_text
                if nodes:
                    return nodes
                last = "노드가 없는 덤프"
            self.sleep(DUMP_RETRY_S)
        raise self.fail(
            "ui_dump", "화면 구조(uiautomator dump)를 읽지 못했습니다: %s" % last[:200]
        )

    def screenshot(self, name: str) -> Optional[str]:
        try:
            png = self.adb.screencap_png()
        except AdbError as exc:
            self.say("  (경고) 스크린샷 실패: %s" % exc)
            return None
        if not png.startswith(director.PNG_SIGNATURE):
            self.say("  (경고) 스크린샷이 PNG가 아닙니다 (%d bytes)" % len(png))
            return None
        with open(os.path.join(self.out_dir, name), "wb") as handle:
            handle.write(png)
        return name

    def fail(self, step: str, detail: str) -> StepFailed:
        """A step whose state did not appear: keep what the screen showed, say it loudly, return the exception."""
        self.failures += 1
        stem = "FAIL_%02d_%s" % (self.failures, slug(step))
        shot = self.screenshot(stem + ".png")
        dump_name = None
        if self.last_xml:
            dump_name = stem + ".xml"
            with open(
                os.path.join(self.out_dir, dump_name), "w", encoding="utf-8"
            ) as handle:
                handle.write(self.last_xml)
        self.say(
            "  [실패] %s: %s (화면 %s, 덤프 %s)"
            % (step, detail, shot or "없음", dump_name or "없음")
        )
        self.record("실패: %s" % detail)
        return StepFailed(
            "%s: %s" % (step, detail), {"screenshot": shot, "dump": dump_name}
        )

    def wait_for(
        self,
        step: str,
        what: str,
        find: Callable[[List[UiNode]], object],
        timeout: Optional[float] = None,
        scroll: bool = False,
        scroll_anchor: Optional[str] = None,
    ):
        """Dump until `find(nodes)` returns something. With `scroll`, a target that is not on screen (the dumper
        lists only what is) is scrolled to: the panel around `scroll_anchor` when it is on screen, else the largest
        scrolling panel of the topmost window. Returns (found, nodes)."""
        timeout = UI_WAIT_S if timeout is None else timeout
        deadline = self.now() + timeout
        scrolls = 0
        while True:
            nodes = self.dump()
            found = find(nodes)
            if found is not None and found is not WAITING:
                return found, nodes
            if found is None and scroll and scrolls < MAX_SCROLLS:
                anchors = find_all(nodes, scroll_anchor) if scroll_anchor else []
                panel = scroll_container(nodes, anchors[0] if anchors else None)
                if panel is not None:
                    self.swipe_up(panel)
                    scrolls += 1
                    continue
            if self.now() >= deadline:
                raise self.fail(
                    step, "%.0f초 안에 화면에 나타나지 않았습니다: %s" % (timeout, what)
                )
            self.sleep(UI_POLL_S)

    def wait_text(
        self, step: str, text: str, exact: bool = True, timeout: Optional[float] = None
    ) -> UiNode:
        def find(nodes):
            hits = find_all(nodes, text, exact=exact)
            return hits[0] if hits else None

        return self.wait_for(step, "'%s'" % text, find, timeout)[0]

    def tap(self, node: UiNode, what: str) -> None:
        x, y = node.center
        self.adb.shell("input", "tap", str(x), str(y))
        self.say("  - 누름: %s (%d, %d)" % (what, x, y))
        self.record("누름: %s" % what)

    def swipe_up(self, panel: UiNode) -> None:
        """Scroll `panel` one half-height towards its end (content moves up)."""
        left, top, right, bottom = panel.bounds
        x, height = (left + right) // 2, bottom - top
        y_from, y_to = top + int(height * 0.75), top + int(height * 0.25)
        self.adb.shell(
            "input", "swipe", str(x), str(y_from), str(x), str(y_to), str(SWIPE_MS)
        )
        self.say("  - 화면을 올립니다 (찾는 항목이 아래에 있음)")

    def tap_text(
        self,
        step: str,
        text: str,
        what: Optional[str] = None,
        below: Optional[str] = None,
        timeout: Optional[float] = None,
        scroll: bool = False,
    ) -> UiNode:
        """Press the element showing `text`, once it is on screen and enabled. Without `below`, exactly one element
        may show it (two would make the tap a guess); with `below`, the nearest under that heading in its window."""
        what = what or "'%s'" % text
        ambiguous: List[int] = []

        def find(nodes):
            anchor = None
            if below is not None:
                anchors = find_all(nodes, below)
                if not anchors:
                    return None
                anchor = anchors[0]
            targets = unique_targets(nodes, find_all(nodes, text, below=anchor))
            if anchor is None and len(targets) > 1:
                ambiguous.append(len(targets))
                return targets  # resolved below: a guess is a failure
            if not targets:
                return None
            return targets[0] if targets[0].enabled else WAITING  # shown but disabled: wait, never scroll away

        found, _ = self.wait_for(
            step, what, find, timeout, scroll=scroll, scroll_anchor=below
        )
        if ambiguous:
            raise self.fail(
                step,
                "%s 이(가) 화면 %d곳에 있어 어느 것을 누를지 정할 수 없습니다"
                % (what, ambiguous[-1]),
            )
        self.tap(found, what)
        return found


# --------------------------------------------------------------------------- the fleet console


def fleet_download(url: str, timeout: float = 30.0) -> bytes:
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({})
    )  # loopback: never through a proxy
    try:
        with opener.open(url, timeout=timeout) as resp:
            return resp.read()
    except (OSError, http.client.HTTPException) as exc:
        raise ConsoleError("GET %s -> %s" % (url, exc))


def fleet_get(url: str, timeout: float = 10.0) -> str:
    return fleet_download(url, timeout).decode("utf-8", "replace")


class Fleet:
    """The company's fleet console (tools/fleet-console): its /api/state, and forge_demo.py against it."""

    def __init__(
        self,
        url: str,
        data_dir: str,
        out_dir: str,
        fetch: Callable[[str], str] = fleet_get,
        run=subprocess.run,
        now: Optional[Callable[[], float]] = None,
        sleep: Optional[Callable[[float], None]] = None,
        download: Callable[[str], bytes] = fleet_download,
    ):
        self.url, self.data_dir, self.out_dir = url.rstrip("/"), data_dir, out_dir
        self.fetch, self.run, self.download = fetch, run, download
        self.now = now or time.monotonic
        self.sleep = sleep or time.sleep
        self.last_state: Optional[dict] = None

    def state(self) -> dict:
        data = json.loads(self.fetch(self.url + "/api/state"))
        if not isinstance(data, dict):
            raise ConsoleError("/api/state 응답이 JSON 객체가 아닙니다")
        self.last_state = data
        return data

    def wait(
        self,
        step: str,
        what: str,
        find: Callable[[dict], Optional[dict]],
        timeout: float = CONSOLE_WAIT_S,
    ) -> dict:
        deadline = self.now() + timeout
        while True:
            found = find(self.state())
            if found is not None:
                return found
            if self.now() >= deadline:
                name = "FAIL_console_%s.json" % slug(step)
                with open(
                    os.path.join(self.out_dir, name), "w", encoding="utf-8"
                ) as handle:
                    json.dump(self.last_state, handle, ensure_ascii=False, indent=1)
                raise StepFailed(
                    "%s: 관리자 화면에 %.0f초 안에 나타나지 않았습니다: %s"
                    % (step, timeout, what),
                    {"console_state": name},
                )
            self.sleep(2.0)

    def save_archive(self, item: dict, name: str) -> str:
        """Download a stored workbook (its /archives/... link on the console page) into out_dir as `name`."""
        url = self.url + item["url"]
        data = self.download(url)
        if not data.startswith(b"PK\x03\x04"):
            raise StepFailed(
                "관리자 화면에서 받은 파일이 xlsx가 아닙니다: %s (%d bytes)" % (url, len(data)),
                {"archive": item},
            )
        local = os.path.join(self.out_dir, os.path.basename(name))
        with open(local, "wb") as handle:
            handle.write(data)
        return local

    def forge(self) -> str:
        """Run tools/fleet-console/forge_demo.py against this console, as the presenter does."""
        proc = self.run(
            [
                sys.executable,
                FORGE_SCRIPT,
                "--console",
                self.url,
                "--data",
                self.data_dir,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=60,
        )
        out = decode(proc.stdout).strip()
        if proc.returncode != 0:
            raise StepFailed(
                "forge_demo.py 실패 (rc=%d): %s" % (proc.returncode, out[-300:]),
                {"forge": out},
            )
        return out


class ConsoleCamera:
    """Headless Chrome screenshots of the fleet console page, for the video's right half (none without --chrome)."""

    def __init__(
        self,
        chrome: Optional[str],
        url: str,
        out_dir: str,
        timeline: Timeline,
        run=subprocess.run,
        wall: Callable[[], float] = time.time,
        say_fn: Callable[[str], None] = say,
    ):
        self.chrome, self.url, self.out_dir, self.timeline = (
            chrome,
            url.rstrip("/") + "/",
            out_dir,
            timeline,
        )
        self.run, self.wall, self.say = run, wall, say_fn
        self.errors: List[str] = []

    def command(self, path: str) -> List[str]:
        return [
            self.chrome or "",
            "--headless=new",
            "--no-sandbox",
            "--disable-gpu",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--window-size=%d,%d" % CONSOLE_SHOT_SIZE,
            "--virtual-time-budget=%d" % CHROME_BUDGET_MS,
            "--screenshot=%s" % path,
            self.url,
        ]

    def shot(self, name: str, scene: int) -> Optional[str]:
        if not self.chrome:
            return None
        path = os.path.abspath(os.path.join(self.out_dir, name))
        at = (
            self.wall()
        )  # the page reads /api/state right away: the shot shows the console as of now
        try:
            proc = self.run(
                self.command(path),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=CHROME_TIMEOUT_S,
            )
            failed = proc.returncode != 0 or not os.path.exists(path)
            detail = (
                "rc=%d %s" % (proc.returncode, decode(proc.stderr)[-200:])
                if failed
                else ""
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            failed, detail = True, str(exc)
        if failed:
            self.errors.append("%s: %s" % (name, detail))
            self.say("  (경고) 관리자 화면 캡처 실패 %s: %s" % (name, detail))
            return None
        self.timeline.console_shot(at, name, scene)
        return name


# --------------------------------------------------------------------------- the rehearsal


class Rehearsal:
    def __init__(
        self,
        adb,
        ui: UiDriver,
        fleet: Fleet,
        camera: ConsoleCamera,
        timeline: Timeline,
        drive,
        user: int,
        out_dir: str,
        say_fn: Callable[[str], None] = say,
        sleep: Optional[Callable[[float], None]] = None,
        now: Optional[Callable[[], float]] = None,
        wall: Callable[[], float] = time.time,
        pause_flag: Optional[str] = None,
    ):
        self.pause_flag = pause_flag  # CI: while this file exists the recording loop starts no new segment
        self.adb, self.ui, self.fleet, self.camera, self.timeline = (
            adb,
            ui,
            fleet,
            camera,
            timeline,
        )
        self.drive_director, self.user, self.out_dir = drive, user, out_dir
        self.say = say_fn
        self.sleep = sleep or time.sleep
        self.now = now or time.monotonic
        self.wall = wall
        self.trips: Dict[str, director.TripClosed] = {}

    def record(self, text: str) -> None:
        self.timeline.event(text)

    # ----------------------------------------------------------------- flow

    def prepare(self) -> dict:
        """Wait until the console holds the app's whole ledger (docs/DEMO.md, the day before: the console shows the
        app's verification code and '정상'). The app sends its sample ledger in order, a few entries a second."""
        director.ensure_app_in_front(self.adb, say_fn=self.say)
        started = self.now()
        code, read_at = None, None
        self.say(
            "준비: 관리자 화면에 앱의 원장 전체가 들어올 때까지 기다립니다 (최대 %.0f초)"
            % PREP_WAIT_S
        )
        while True:
            if code is None or self.now() - read_at >= PREP_CODE_EVERY_S:
                code, _ = self.ui.wait_for(
                    "app_code", "앱 상태줄의 검증 코드", app_ledger_code
                )
                read_at = self.now()
            state = self.fleet.state()
            ready = ready_chain(state, code)
            if ready is not None:
                ready["waited_s"] = round(self.now() - started, 1)
                self.say(
                    "  관리자 화면 준비됨: 원장 %s건, 검증 코드 %s (%.0f초)"
                    % (ready["entries"], code, ready["waited_s"])
                )
                self.timeline.data["prep"] = ready
                self.timeline.save()
                return ready
            if self.now() - started >= PREP_WAIT_S:
                seqs = [
                    (c.get("key"), c.get("lastSeq"), c.get("lastHashShort"))
                    for c in state.get("cars") or []
                ]
                raise StepFailed(
                    "관리자 화면이 %.0f초 안에 앱의 원장(검증 코드 %s)을 다 받지 못했습니다: %s"
                    % (PREP_WAIT_S, code, seqs)
                )
            self.sleep(PREP_POLL_S)

    def run(self, specs: Sequence[SceneSpec] = SCENES) -> List[bool]:
        self.camera.shot("console_00_start.png", 0)
        return [self.play(spec) for spec in specs]

    def play(self, spec: SceneSpec) -> bool:
        self.timeline.begin(spec)
        self.say("")
        self.say("=== 장면 %d. %s ===" % (spec.number, spec.title))
        self.say(spec.caption)
        try:
            self.timeline.clock(measure_clock(self.adb, self.wall))
        except (AdbError, ValueError) as exc:
            self.say("  (경고) 기기 시계를 읽지 못했습니다: %s" % exc)
        anr_before = anr_count(self.adb)
        self.sleep(CAPTION_LEAD_S)
        try:
            evidence = getattr(self, "scene_" + spec.key)()
            ok, detail = True, evidence.pop("summary")
        except StepFailed as exc:
            ok, detail, evidence = False, str(exc), dict(exc.evidence)
            if not evidence.get("screenshot"):  # a check that failed on data (not a missing screen) still shows the screen
                evidence["screenshot"] = self.ui.screenshot("FAIL_scene%d_%s.png" % (spec.number, spec.key))
                self.say("  [실패] %s (화면 %s)" % (detail, evidence["screenshot"] or "없음"))
        except (AdbError, ConsoleError, SetupError) as exc:
            detail = "%s: %s" % (type(exc).__name__, exc)
            ok, evidence = False, dict(self.ui.fail(spec.key, detail).evidence)
        anr_new = anr_count(self.adb) - anr_before
        if anr_new:
            evidence["anr"] = (
                anr_new  # Android closed the app for not responding during the scene
            )
            self.say(
                "  (경고) 이 장면에서 앱이 응답 없음(ANR)으로 %d번 닫혔습니다" % anr_new
            )
        self.camera.shot("console_%02d_%s.png" % (spec.number, spec.key), spec.number)
        self.timeline.end(ok, detail, evidence)
        self.say(
            "[%s] 장면 %d %s: %s"
            % ("PASS" if ok else "FAIL", spec.number, spec.title, detail)
        )
        self.sleep(HOLD_S)
        return ok

    # ----------------------------------------------------------------- shared steps

    def ensure_front(self) -> None:
        """The app in front of the driver's user, and no trip dialog left open by a scene that failed."""
        director.ensure_app_in_front(self.adb, say_fn=self.say)
        nodes = self.ui.dump()
        for heading in ("목적 고치기", "목적 정하기"):
            anchors = find_all(nodes, heading)
            if anchors:
                close = [
                    n for n in find_all(nodes, "닫기") if n.window == anchors[0].window
                ]
                if close:
                    self.ui.tap(
                        clickable_ancestor(nodes, close[0]), "열려 있던 운행 상세 닫기"
                    )
                return

    def wait_log(
        self, step: str, pattern: str, what: str, timeout: float = LOG_WAIT_S
    ) -> str:
        regex = re.compile(pattern)
        deadline = self.now() + timeout
        while True:
            for line in reversed(self.adb.logcat_dump().splitlines()):
                if regex.search(line):
                    return line.strip()
            if self.now() >= deadline:
                raise self.ui.fail(
                    step,
                    "앱 로그에 %s 줄이 %.0f초 안에 나오지 않았습니다" % (what, timeout),
                )
            self.sleep(1.0)

    def drive(self, key: str) -> director.TripClosed:
        scenario = director.select_scenarios(key)[0]
        director.ensure_app_in_front(self.adb, say_fn=self.say)
        self.drive_director.run_scenario(scenario)
        verdict = director.verify_scenario(self.adb, scenario)
        if not verdict.ok:
            seen = "; ".join(t.line for t in verdict.seen[-2:]) or "없음"
            raise self.ui.fail(
                "trip_closed_" + key,
                "앱 로그에 기대한 운행(%s)이 없습니다 (있던 줄: %s)"
                % (director.expectation_text(scenario), seen),
            )
        self.trips[key] = verdict.trip
        self.record(
            "앱 로그: 운행 종료 %.1f km, 목적 %s"
            % (verdict.trip.km or 0.0, verdict.trip.purpose)
        )
        return verdict.trip

    def console_trip(
        self, step: str, key: str, purpose: str, amended: Optional[bool] = None
    ) -> dict:
        scenario = director.select_scenarios(key)[0]
        return self.fleet.wait(
            step,
            "%s 운행 (%s, %s)"
            % (
                key,
                odometer_text(scenario.odometer_start, scenario.odometer_end),
                purpose,
            ),
            lambda state: find_console_trip(
                state, scenario.odometer_start, scenario.odometer_end, purpose, amended
            ),
        )

    def auto_scene(self, key: str, purpose: str) -> dict:
        trip = self.drive(key)
        title = RECORDED_TITLE[purpose]
        self.ui.wait_text("recorded_" + key, title)
        self.record("앱 화면: '%s'" % title)
        console = self.console_trip("console_" + key, key, purpose)
        self.record(
            "관리자 화면: 같은 운행이 바로 도착 (%s)"
            % console["trip"].get("purposeLabel", purpose)
        )
        return {
            "summary": "%s, 관리자 화면에 같은 줄" % title,
            "trip": trip.line,
            "console": console,
        }

    # ----------------------------------------------------------------- the seven scenes

    def scene_commute(self) -> dict:
        return self.auto_scene("commute", "COMMUTE")

    def scene_client(self) -> dict:
        return self.auto_scene("client", "BUSINESS")

    def scene_personal(self) -> dict:
        trip = self.drive("personal")
        scenario = director.select_scenarios("personal")[0]
        hero = "%s km 운행했습니다" % km_text(scenario.km)
        self.ui.wait_text("ask_purpose", "이번 운행의 목적을 골라 주세요")
        self.ui.wait_text("ask_km", hero)
        self.record("앱 화면: '%s, 목적을 골라 주세요'" % hero)
        self.ui.tap_text(
            "pick_personal",
            "비업무",
            "'비업무'",
            below="이번 운행의 목적을 골라 주세요",
        )
        last, _ = self.ui.wait_for(
            "recorded_personal",
            "홈의 '마지막 운행' 줄에 '비업무' %s km" % km_text(scenario.km),
            lambda nodes: last_trip_line(nodes, "비업무", "%s km" % km_text(scenario.km)),
        )
        line = self.wait_log(
            "classified_personal",
            r"TRIP_CLASSIFIED id=%s purpose=PERSONAL by=화면\s*$"
            % re.escape(trip.trip_id),
            "TRIP_CLASSIFIED (비업무, 화면)",
        )
        self.record("앱 화면: 마지막 운행 '비업무' %s km" % km_text(scenario.km))
        console = self.console_trip("console_personal", "personal", "PERSONAL")
        self.record("관리자 화면: '비업무'로 도착")
        return {
            "summary": "목적을 물었고 '비업무'를 눌러 기록",
            "home": last,
            "trip": trip.line,
            "log": line,
            "console": console,
        }

    def scene_correction(self) -> dict:
        commute = director.select_scenarios("commute")[0]
        odometers = odometer_text(commute.odometer_start, commute.odometer_end)
        self.ensure_front()
        self.ui.tap_text("open_trips", "운행 목록", "'운행 목록' 탭")
        row, _ = self.ui.wait_for(
            "trip_row",
            "출근 운행 줄 (%s)" % odometers,
            lambda nodes: trip_row(nodes, odometers),
        )
        self.ui.tap(row, "출근 운행 줄 (%s)" % odometers)
        self.ui.wait_text("trip_dialog", "목적 고치기")
        self.ui.tap_text("pick_personal", "비업무", "'비업무'", below="목적 고치기")
        reasons = "고치는 이유 (기록 원장에 남습니다)"
        self.ui.tap_text(
            "pick_reason",
            CORRECTION_REASON,
            "이유 '%s'" % CORRECTION_REASON,
            below=reasons,
            scroll=True,
        )
        self.ui.tap_text(
            "save", "저장", "'저장'", scroll=True
        )  # enabled only once a reason is picked; the one 저장 on screen, so no heading is needed to find it
        texts, _ = self.ui.wait_for(
            "amended_mark",
            "출근 줄의 '비업무'와 '수정됨'",
            lambda nodes: amended_row(nodes, odometers, "비업무"),
        )
        self.record("앱 화면: 출근 기록이 '비업무 · 수정됨'")
        known = self.trips.get("commute")
        trip_id = re.escape(known.trip_id) if known else r"\S+"
        line = self.wait_log(
            "amend_log",
            r"TRIP_CLASSIFIED id=%s purpose=PERSONAL by=화면 amended" % trip_id,
            "TRIP_CLASSIFIED ... amended",
        )
        console = self.console_trip(
            "console_amended", "commute", "PERSONAL", amended=True
        )
        reason = console["trip"].get("amendReason") or ""
        if CORRECTION_REASON not in reason:
            raise StepFailed(
                "관리자 화면의 정정 이유가 다릅니다: %r" % reason, {"console": console}
            )
        self.record("관리자 화면: 같은 줄에 '수정됨' (이유: %s)" % CORRECTION_REASON)
        return {
            "summary": "이유를 골라야 저장됨, 앱과 관리자 화면 모두 '수정됨'",
            "row": texts,
            "log": line,
            "console": console,
        }

    def scene_tamper(self) -> dict:
        out = self.fleet.forge()
        seq = parse_forge_seq(out)
        for line in out.splitlines():
            self.say("  forge_demo.py: %s" % line)
        self.record(
            "노트북: 위조 기록 한 줄 전송 (순번 %s)" % (seq if seq is not None else "?")
        )
        flag = self.fleet.wait(
            "console_tamper",
            "'%s' 알림" % CONSOLE_TAMPER,
            lambda state: tamper_flag(state, seq),
        )
        row = flag["row"]
        if row is None:
            raise StepFailed(
                "변조 알림은 떴지만 표시할 운행 줄이 최근 목록에 없습니다",
                {"console": flag, "forge": out},
            )
        if row.get("purpose") != "PERSONAL":
            raise StepFailed(
                "회사 사본의 그 운행이 바뀌었습니다: %s" % row.get("purpose"),
                {"console": flag},
            )
        personal = self.trips.get("personal")
        self.record(
            "관리자 화면: '%s', 그 운행 줄에 '변조 시도' (회사 사본은 그대로 '비업무')"
            % CONSOLE_TAMPER
        )
        return {
            "summary": "관리자 화면 '%s' + 알림 띠 + 줄 표시, 회사 사본은 그대로"
            % CONSOLE_TAMPER,
            "forge": out,
            "console": flag,
            "forged_scene3_trip": bool(personal and row.get("id") == personal.trip_id),
        }

    def scene_export(self) -> dict:
        self.ensure_front()
        self.ui.tap_text("open_report", "운행기록부", "'운행기록부' 탭")
        self.ui.wait_text("report", "업무용승용차 운행기록부")
        self.ui.tap_text("pick_year", "사업연도", "'사업연도'")
        screen, nodes = self.ui.wait_for("form_values", "⑪ ⑫ ⑬ 값", form_values)
        code = app_ledger_code(nodes)
        self.record(
            "앱 화면: ⑪ %s, ⑫ %s, ⑬ %s"
            % (screen["⑪"]["text"], screen["⑫"]["text"], screen["⑬"]["text"])
        )
        seen = {archive_mark(item) for item in console_archives(self.fleet.state())}
        with self.recording_paused("앱이 엑셀을 쓰는 동안"):
            self.ui.tap_text("send", SEND_BUTTON, "'%s'" % SEND_BUTTON)
            name, _ = self.ui.wait_for(
                "sent", "'저장하고 전송을 시작했습니다' 안내", sent_workbook
            )
        self.record("앱 화면: 저장하고 전송을 시작했습니다 (%s)" % name)
        line = self.wait_log(
            "export_log",
            r"EXPORT file=%s rows=\d+ queued=true" % re.escape(name),
            "EXPORT ... queued=true",
        )
        rows = int(re.search(r"rows=(\d+)", line).group(1))
        archive = self.fleet.wait(
            "console_archive",
            "운행기록부 보관함의 %s" % name,
            lambda state: new_archive(state, name, seen),
        )
        self.record(
            "관리자 화면: 운행기록부 보관함에 도착 (%s, 검증 코드 %s)"
            % (name, archive.get("verificationCode"))
        )
        local = self.fleet.save_archive(archive, name)
        book = read_xlsx_totals(local)
        problems = compare_totals(screen, book)
        if code is not None and archive.get("verificationCode") != code:
            problems.append(
                "검증 코드: 앱 %s, 보관된 엑셀 %s" % (code, archive.get("verificationCode"))
            )
        evidence = {
            "screen": screen,
            "xlsx": book,
            "file": os.path.basename(local),
            "rows": rows,
            "app_code": code,
            "archive": archive,
            "log": line,
        }
        if problems:
            raise StepFailed(
                "관리자 화면에서 받은 엑셀과 앱 화면이 다릅니다: %s" % "; ".join(problems),
                evidence,
            )
        self.record(
            "노트북: 관리자 화면에서 받은 엑셀 ⑪ %.1f km, ⑫ %.1f km, ⑬ %.1f%% (= ⑫/⑪)"
            % (book["⑪"]["value"], book["⑫"]["value"], book["⑬"]["value"] * 100)
        )
        evidence["summary"] = (
            "엑셀 %d행을 회사 서버로 보내 관리자 화면에서 받았고, ⑪ ⑫ ⑬과 검증 코드가 앱 화면과 같음" % rows
        )
        return evidence

    @contextlib.contextmanager
    def recording_paused(self, why: str):
        """CI only (pause_flag): stop the screen recording around a step whose work runs on the app's main thread, so
        the software encoder does not starve it past the 5 s input limit; the pause is recorded for the video."""
        if not self.pause_flag:
            yield
            return
        with open(self.pause_flag, "w", encoding="utf-8") as handle:
            handle.write(why + "\n")
        pause = {"why": why, "start": self.wall(), "end": None, "stopped": False, "resumed": False}
        self.timeline.data.setdefault("recording_pauses", []).append(pause)
        try:
            self.adb.run("shell", "pkill -SIGINT screenrecord", check=False, timeout=30)
            pause["stopped"] = self.wait_recorder(False)
            pause["start"] = self.wall()
            self.record("녹화 잠시 멈춤 (%s, CI 서버 부하 줄이기)" % why)
            yield
        finally:
            os.remove(self.pause_flag)
            pause["resumed"] = self.wait_recorder(True)
            pause["end"] = self.wall()
            self.timeline.save()
            if pause["resumed"]:
                self.record("녹화 다시 시작")
            else:
                self.say("  (경고) 녹화가 %.0f초 안에 다시 시작되지 않았습니다" % RECORDER_WAIT_S)
                self.record("녹화가 다시 시작되지 않았습니다")

    def wait_recorder(self, running: bool) -> bool:
        deadline = self.now() + RECORDER_WAIT_S
        while recorder_running(self.adb) != running:
            if self.now() >= deadline:
                return False
            self.sleep(RECORDER_POLL_S)
        return True

    def scene_gleo(self) -> dict:
        self.ensure_front()
        self.ui.tap_text("open_today", "오늘", "'오늘' 탭")
        self.ui.wait_text("today", "월 업무사용비율", exact=False)
        before = len(gleo_replies(self.adb.logcat_dump()))
        self.record(
            "Gleo 요청을 디버그 신호로 재현: %s %s" % (GLEO_FUNCTION, GLEO_PARAMS)
        )
        self.adb.shell(*gleo_broadcast_args(self.user), timeout=90)
        deadline = self.now() + LOG_WAIT_S
        while True:
            replies = [
                r
                for r in gleo_replies(self.adb.logcat_dump())[before:]
                if r["function"] == GLEO_FUNCTION
            ]
            if replies:
                break
            if self.now() >= deadline:
                raise self.ui.fail(
                    "gleo_reply",
                    "앱 로그에 GLEO_REPLY 줄이 %.0f초 안에 나오지 않았습니다"
                    % LOG_WAIT_S,
                )
            self.sleep(1.0)
        reply = replies[-1]
        with open(
            os.path.join(self.out_dir, "gleo_reply.txt"), "w", encoding="utf-8"
        ) as handle:
            handle.write(reply["line"] + "\n")
        if reply["status"] != "success":
            raise StepFailed(
                "Gleo 함수가 성공하지 않았습니다: %s" % reply["line"], {"reply": reply}
            )
        self.record("앱 응답 (로그 GLEO_REPLY): %s" % reply["message"])
        return {
            "summary": "GLEO_REPLY status=success: %s" % reply["message"],
            "reply": reply,
            "note": "디버그 신호로 재현한 Gleo 요청 (음성 아님)",
        }


# --------------------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rehearsal.py",
        description="docs/DEMO.md의 7분 시연 전체를 에뮬레이터에서 무인으로 리허설합니다 (실제 탭, 장면별 PASS/FAIL).",
        epilog="종료 코드: 0 모든 장면 PASS, 1 실패한 장면 있음, 2 adb/SDK 없음 또는 사용법 오류, 3 가상 콘솔 또는 관리자 화면 연결 실패",
    )
    parser.add_argument(
        "--out",
        required=True,
        metavar="DIR",
        help="scenes.json, 스크린샷, 엑셀, 실패 덤프를 둘 폴더",
    )
    parser.add_argument(
        "--fleet-data",
        required=True,
        metavar="DIR",
        help="관리자 화면 서버의 --data 폴더 (forge_demo.py 가 읽음)",
    )
    parser.add_argument(
        "--fleet-url",
        default="http://127.0.0.1:8787",
        metavar="URL",
        help="관리자 화면 주소",
    )
    parser.add_argument(
        "--chrome",
        metavar="PATH",
        help="관리자 화면을 장면마다 캡처할 크롬 (없으면 캡처 안 함)",
    )
    parser.add_argument(
        "--pause-recording-flag",
        metavar="FILE",
        help="CI 녹화 루프가 보는 파일: 앱이 엑셀을 쓰는 동안 이 파일을 만들고 screenrecord를 멈춥니다 (영상에 표시)",
    )
    parser.add_argument(
        "--km-interval",
        type=float,
        default=director.DEFAULT_KM_INTERVAL,
        metavar="SEC",
        help="계기판 1 km마다 쉬는 초 (기본 %(default)s, 발표 속도)",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help="km 간격을 %.1f초로" % director.FAST_KM_INTERVAL,
    )
    parser.add_argument(
        "--serial", default=DEFAULT_SERIAL, help="adb 대상 (기본 %(default)s)"
    )
    parser.add_argument(
        "--console",
        default=console_url(),
        metavar="URL",
        help="가상 콘솔 주소 (기본 %(default)s)",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    configure_stdio()
    parser = build_parser()
    args = parser.parse_args(argv)
    km_interval = director.FAST_KM_INTERVAL if args.fast else args.km_interval
    if km_interval <= 0:
        parser.error("--km-interval 은 0보다 커야 합니다")
    os.makedirs(args.out, exist_ok=True)
    try:
        tools = find_sdk_tools(need_emulator=False)
    except SetupError as exc:
        say(str(exc), err=True)
        return exc.code
    adb = Adb(tools.adb, serial=args.serial)
    console = director.Console(args.console, notify=say)
    try:
        forward = director.forward_ports(args.console)
        if forward:
            adb.forward(*forward)
        console.state()
    except (AdbError, ConsoleError) as exc:
        say(director.unreachable_message(args.console, str(exc)), err=True)
        return EXIT_CONSOLE
    fleet = Fleet(args.fleet_url, args.fleet_data, args.out)
    try:
        fleet.state()
    except (ConsoleError, ValueError) as exc:
        say(
            "관리자 화면(%s)에 연결할 수 없습니다: %s" % (args.fleet_url, exc), err=True
        )
        return EXIT_CONSOLE

    timeline = Timeline(os.path.join(args.out, TIMELINE_FILE))
    ui = UiDriver(adb, args.out, record=timeline.event)
    camera = ConsoleCamera(args.chrome, args.fleet_url, args.out, timeline)

    def narrate(
        line: str,
    ) -> None:  # the director's beat lines become the video's sub-captions too
        say(line)
        if NARRATION_LINE.match(line.strip()):
            timeline.event(line.strip().replace("->", "→"))

    drive = director.Director(
        console, adb, out_dir=args.out, km_interval=km_interval, say_fn=narrate
    )
    session = director.SessionKeeper(args.console)
    results: List[bool] = []
    try:
        session.start()  # without a live browser session the vehicle console refuses every write
        director.wait_for_bridge(console)
        user = parse_current_user(adb.shell("am", "get-current-user"))
        if user is None:
            say("현재 사용자를 알 수 없습니다 (am get-current-user)", err=True)
            return EXIT_FAILED
        timeline.data["user"] = user
        director.write_preflight(
            args.out, director.preflight_lines(adb, console.state())
        )
        adb.logcat_clear()
        try:
            timeline.clock(measure_clock(adb))
        except (AdbError, ValueError) as exc:  # each scene samples the clock again; the video needs one sample
            say("  (경고) 기기 시계를 읽지 못했습니다: %s" % exc)
        rehearsal = Rehearsal(
            adb, ui, fleet, camera, timeline, drive, user, args.out,
            pause_flag=args.pause_recording_flag,
        )
        try:
            rehearsal.prepare()
        except StepFailed as exc:
            say("준비 실패 (장면은 계속 진행, 결과는 실패): %s" % exc, err=True)
            timeline.data["prep"] = {"ok": False, "detail": str(exc), "evidence": ui.fail("prep", str(exc)).evidence}
            timeline.save()
        results = rehearsal.run()
    except (AdbError, ConsoleError) as exc:
        say("리허설 중 오류: %s" % exc, err=True)
        return EXIT_CONSOLE
    except KeyboardInterrupt:
        say("\n중단했습니다.")
        return 130
    finally:
        session.stop()
        timeline.data["console_shot_errors"] = camera.errors
        timeline.finish()

    say("")
    for scene in timeline.data["scenes"]:
        say(
            "[%s] %d. %s: %s"
            % (
                "PASS" if scene["ok"] else "FAIL",
                scene["number"],
                scene["title"],
                scene["detail"],
            )
        )
    say("리허설 결과: %d/%d PASS" % (sum(results), len(SCENES)))
    prep_ok = bool(timeline.data["prep"]) and timeline.data["prep"].get("ok", True)
    return EXIT_OK if prep_ok and len(results) == len(SCENES) and all(results) else EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
