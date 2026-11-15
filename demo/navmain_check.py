#!/usr/bin/env python3
"""Navigation is the main screen: AutoBrief records a trip in the background and shows its status
pill over the front app. This check proves it on the emulator.

It allows "display over other apps" for the app, puts another app in front (navigation or the
home), drives one short trip through the Vehicle Virtual Console without ever bringing AutoBrief to
the front, and reads the front activity, the app's logcat (HUD_SHOWN, TRIP_CLOSED, HUD_HIDDEN) and
`dumpsys window windows` (the overlay window). Each check prints its own [PASS] / [FAIL] line.

    python3 demo/navmain_check.py --front navigation --fast --out e2e-out/navmain

The navigation app is never force-stopped (see setup_emulator.quiet_emulator_navigation): it is
only started, like Director.show_navigation does.
"""

import argparse
import os
import re
import sys
import time
from dataclasses import dataclass
from typing import Callable, List, Optional, Sequence, Tuple

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import director  # noqa: E402  (the kit's console client, plan and logcat helpers)
from director import (  # noqa: E402
    AwaitPosition,
    CLIENT,
    Console,
    DEFAULT_KM_INTERVAL,
    Director,
    EXIT_CONSOLE,
    EXIT_OK,
    EXIT_VERIFY_FAILED,
    FAST_KM_INTERVAL,
    GeoFix,
    NAV_PACKAGE,
    NAV_SHOW_WAIT_S,
    OFFICE,
    PNG_SIGNATURE,
    Scenario,
    SessionKeeper,
    ShowApp,
    ShowNavigation,
    TripClosed,
    VERIFY_TIMEOUT_S,
    ensure_app_in_front,
    forward_ports,
    plan_beats,
    trip_closed_events,
    unreachable_message,
    wait_for_bridge,
)
from setup_emulator import (  # noqa: E402
    APP_PACKAGE,
    DEFAULT_SERIAL,
    HOME_ACTIVITY,
    Adb,
    AdbError,
    ConsoleError,
    SetupError,
    app_resumed,
    configure_stdio,
    console_url,
    find_sdk_tools,
    parse_current_user,
    parse_front_activity,
    resumed_records,
    say,
)

# --------------------------------------------------------------------------- constants

OVERLAY_OP = "SYSTEM_ALERT_WINDOW"
# WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY: `ty=APPLICATION_OVERLAY` in the dump (the number on older images)
OVERLAY_TYPES = ("APPLICATION_OVERLAY", "2038")
FRONTS = ("navigation", "home")
# after the optional scenes (director.OPTIONAL_SCENARIOS end at 45,259): 45,259 -> 45,264
ODOMETER_START = 45259
TRIP_KM = 5
OUT_DIR = os.path.join("e2e-out", "navmain")
SHOT_DRIVE = "navmain_drive.png"
SHOT_PARK = "navmain_park.png"
CHECK_TOTAL = 6
# steps of the kit's plan that change what is in front or need the map: left out here, the rest plays as it is
FRONT_CHANGING_STEPS = (ShowApp, ShowNavigation, AwaitPosition, GeoFix)


def navmain_scenario(
    odometer_start: int = ODOMETER_START, km: int = TRIP_KM
) -> Scenario:
    return Scenario(
        "navmain",
        "내비가 주 화면 (앱은 뒤에서 기록)",
        OFFICE,
        CLIENT,
        odometer_start,
        km,
        "",  # the purpose is not part of this check
        "앱 화면을 띄우지 않고 기록을 확인합니다",
    )


def background_beats(scenario: Scenario, km_interval: float) -> List[Tuple[str, tuple]]:
    """The kit's own beats for the scenario (seat, gear D, odometer km by km, gear P), without the
    steps that bring an app to the front or wait for the map: (beat name, steps)."""
    return [
        (
            beat.name,
            tuple(s for s in beat.steps if not isinstance(s, FRONT_CHANGING_STEPS)),
        )
        for beat in plan_beats(scenario, km_interval)
    ]


# --------------------------------------------------------------------------- dumpsys window


@dataclass(frozen=True)
class WindowInfo:
    """One window of `dumpsys window windows`."""

    title: str
    user: int
    package: Optional[str]  # None: the dump names no owner for it
    type: Optional[str]  # `ty=` of mAttrs: APPLICATION_OVERLAY, BASE_APPLICATION, ...
    visible: Optional[bool]  # None: the dump says nothing about it

    @property
    def overlay(self) -> bool:
        return self.type in OVERLAY_TYPES


_WINDOW_HEAD = re.compile(r"^\s*Window #\d+ Window\{\S+ u(\d+) (.*)\}:\s*$")
_PACKAGE = re.compile(r"\bpackage=(\S+)")
_TYPE = re.compile(r"\bty=(\S+)")


def _flag(block: str, name: str) -> Optional[bool]:
    found = re.search(r"(?<![\w.])%s=(true|false)\b" % re.escape(name), block)
    return None if found is None else found.group(1) == "true"


def window_visible(block: str) -> Optional[bool]:
    """`isVisible=` when the dump has it, else a surface that is ready for display; None when the
    dump has neither field (unknown is not visible and not hidden)."""
    visible = _flag(block, "isVisible")
    if visible is not None:
        return visible
    surface, ready = _flag(block, "mHasSurface"), _flag(block, "isReadyForDisplay()")
    if surface is None or ready is None:
        return None
    return surface and ready


def parse_windows(dump: str) -> List[WindowInfo]:
    windows = []
    head, lines = None, []

    def close():
        if head is None:
            return
        block = "\n".join(lines)
        package, kind = _PACKAGE.search(block), _TYPE.search(block)
        windows.append(
            WindowInfo(
                head.group(2),
                int(head.group(1)),
                package.group(1) if package else None,
                kind.group(1) if kind else None,
                window_visible(block),
            )
        )

    for line in dump.splitlines():
        found = _WINDOW_HEAD.match(line)
        if found:
            close()
            head, lines = found, []
        elif head is not None:
            lines.append(line)
    close()
    return windows


def app_overlays(
    windows: Sequence[WindowInfo], package: str = APP_PACKAGE
) -> List[WindowInfo]:
    return [w for w in windows if w.package == package and w.overlay]


def describe_windows(windows: Sequence[WindowInfo]) -> str:
    if not windows:
        return "없음"
    return ", ".join(
        "%s(ty=%s visible=%s)"
        % (w.title, w.type, {True: "true", False: "false", None: "?"}[w.visible])
        for w in windows
    )


# --------------------------------------------------------------------------- logcat


@dataclass(frozen=True)
class LogEvent:
    kind: str  # TRIP_OPENED | HUD_SHOWN | HUD_HIDDEN | TRIP_CLOSED
    line: str
    reason: Optional[str] = None  # HUD_HIDDEN reason=


_EVENT = re.compile(r"\b(TRIP_OPENED|HUD_SHOWN|HUD_HIDDEN|TRIP_CLOSED)\b(.*)")
_REASON = re.compile(r"\breason=(\S+)")


def log_events(logcat: str) -> List[LogEvent]:
    events = []
    for line in logcat.splitlines():
        found = _EVENT.search(line)
        if not found:
            continue
        reason = (
            _REASON.search(found.group(2)) if found.group(1) == "HUD_HIDDEN" else None
        )
        events.append(
            LogEvent(found.group(1), line.strip(), reason.group(1) if reason else None)
        )
    return events


def shown_after_trip_start(events: Sequence[LogEvent]) -> Optional[LogEvent]:
    """The first HUD_SHOWN that follows a TRIP_OPENED."""
    opened = False
    for event in events:
        if event.kind == "TRIP_OPENED":
            opened = True
        elif opened and event.kind == "HUD_SHOWN":
            return event
    return None


def hidden_after_shown(
    events: Sequence[LogEvent], reason: str = "trip_ended"
) -> Optional[LogEvent]:
    """The first HUD_HIDDEN with `reason` that follows a HUD_SHOWN."""
    shown = False
    for event in events:
        if event.kind == "HUD_SHOWN":
            shown = True
        elif shown and event.kind == "HUD_HIDDEN" and event.reason == reason:
            return event
    return None


def find_trip(
    logcat: str, scenario: Scenario, tolerance: float = 0.05
) -> Optional[TripClosed]:
    """The TRIP_CLOSED of the driven trip: its odometers and its km (the purpose is not read here)."""
    for trip in trip_closed_events(logcat):
        if (
            abs(trip.start - scenario.odometer_start) <= tolerance
            and abs(trip.end - scenario.odometer_end) <= tolerance
            and trip.km is not None
            and abs(trip.km - scenario.km) <= tolerance
        ):
            return trip
    return None


# --------------------------------------------------------------------------- the front app


def front_component(front: str) -> str:
    """What `--front` names: the navigation app's package, or the home activity."""
    if front == "navigation":
        return NAV_PACKAGE
    if front == "home":
        return HOME_ACTIVITY
    raise ValueError("--front 는 %s 중 하나입니다: %r" % (", ".join(FRONTS), front))


def front_state(dumpsys: str, user: int, front: str) -> Tuple[bool, str]:
    """(ok, what is resumed): the chosen app is resumed in `user` and AutoBrief is not resumed at
    all (the Pleos home shows apps in panels, so every resumed activity counts as on screen)."""
    wanted = front_component(front)
    resumed = [a for _, a in resumed_records(dumpsys) if a.user == user]
    chosen = any(a.component == wanted or a.package == wanted for a in resumed)
    ours = app_resumed(dumpsys, user)
    top = parse_front_activity(dumpsys)
    seen = "맨 앞 %s, 보이는 화면 %s" % (
        top or "없음",
        ", ".join(sorted({str(a) for a in resumed})) or "없음",
    )
    return chosen and ours is None, seen


# --------------------------------------------------------------------------- verdict


@dataclass(frozen=True)
class Check:
    number: int
    name: str
    ok: bool
    detail: str

    def line(self) -> str:
        return "[%s] %d %s: %s" % (
            "PASS" if self.ok else "FAIL",
            self.number,
            self.name,
            self.detail,
        )


@dataclass
class Evidence:
    """What the run read from the emulator; judge() turns it into the six checks."""

    front: str
    user: int
    scenario: Scenario
    activities_drive: str = ""
    logcat_drive: str = ""
    windows_drive: str = ""
    logcat_park: str = ""
    windows_park: str = ""
    activities_after: str = ""
    front_error: Optional[str] = (
        None  # the chosen app did not come to the front: nothing was driven
    )
    reopen_error: Optional[str] = None


CHECK_NAMES = (
    "주행 중 앞 화면",
    "운행 시작 뒤 HUD_SHOWN",
    "오버레이 창",
    "뒤에서 기록한 TRIP_CLOSED",
    "주차 뒤 HUD_HIDDEN, 창 없음",
    "앱을 열면 앞에 뜸",
)


def judge(e: Evidence) -> List[Check]:
    if e.front_error is not None:
        skipped = "앞 화면이 준비되지 않아 확인하지 못했습니다"
        return [Check(1, CHECK_NAMES[0], False, e.front_error)] + [
            Check(n, CHECK_NAMES[n - 1], False, skipped)
            for n in range(2, CHECK_TOTAL + 1)
        ]
    checks = []

    ok, seen = front_state(e.activities_drive, e.user, e.front)
    checks.append(Check(1, CHECK_NAMES[0], ok, "%s (--front %s)" % (seen, e.front)))

    shown = shown_after_trip_start(log_events(e.logcat_drive))
    checks.append(
        Check(
            2,
            CHECK_NAMES[1],
            shown is not None,
            shown.line
            if shown
            else "TRIP_OPENED 뒤에 HUD_SHOWN 줄이 없습니다%s"
            % _hud_lines(e.logcat_drive),
        )
    )

    overlays = app_overlays(parse_windows(e.windows_drive))
    visible = [w for w in overlays if w.visible is True]
    checks.append(
        Check(
            3,
            CHECK_NAMES[2],
            bool(visible),
            "%s 의 오버레이 창: %s" % (APP_PACKAGE, describe_windows(overlays)),
        )
    )

    trip = find_trip(e.logcat_park, e.scenario)
    checks.append(
        Check(
            4,
            CHECK_NAMES[3],
            trip is not None,
            trip.line
            if trip
            else "TRIP_CLOSED start=%.1f end=%.1f km=%.1f 을 찾지 못했습니다 (로그의 TRIP_CLOSED %d줄)"
            % (
                e.scenario.odometer_start,
                e.scenario.odometer_end,
                e.scenario.km,
                len(trip_closed_events(e.logcat_park)),
            ),
        )
    )

    hidden = hidden_after_shown(log_events(e.logcat_park))
    left = app_overlays(parse_windows(e.windows_park))
    checks.append(
        Check(
            5,
            CHECK_NAMES[4],
            hidden is not None and not left,
            "%s; 남은 오버레이 창: %s"
            % (
                hidden.line
                if hidden
                else "HUD_SHOWN 뒤에 HUD_HIDDEN reason=trip_ended 줄이 없습니다%s"
                % _hud_lines(e.logcat_park),
                describe_windows(left),
            ),
        )
    )

    ours = None if e.reopen_error else app_resumed(e.activities_after, e.user)
    checks.append(
        Check(
            6,
            CHECK_NAMES[5],
            ours is not None,
            str(ours) if ours else (e.reopen_error or "앱이 앞에 없습니다"),
        )
    )
    return checks


def _hud_lines(logcat: str) -> str:
    lines = [ev.line for ev in log_events(logcat) if ev.kind.startswith("HUD_")]
    return " (로그의 HUD 줄: %s)" % ("; ".join(lines[-3:]) if lines else "없음")


def result_line(checks: Sequence[Check]) -> str:
    return "검증 결과: %d/%d PASS" % (sum(1 for c in checks if c.ok), len(checks))


# --------------------------------------------------------------------------- the run


class NavMainCheck:
    """Plays the check against a console + emulator; sleep/clock/say are injectable."""

    def __init__(
        self,
        console,
        adb,
        front: str,
        out_dir: Optional[str] = OUT_DIR,
        km_interval: float = DEFAULT_KM_INTERVAL,
        scenario: Optional[Scenario] = None,
        sleep: Optional[Callable[[float], None]] = None,
        clock: Optional[Callable[[], float]] = None,
        say_fn: Callable[[str], None] = say,
        wait: float = VERIFY_TIMEOUT_S,
    ):
        front_component(
            front
        )  # an unknown --front is refused before anything is touched
        self.adb = adb
        self.front = front
        self.out_dir = out_dir
        self.km_interval = km_interval
        self.scenario = scenario or navmain_scenario()
        self.sleep = sleep or time.sleep
        self.clock = clock or time.monotonic
        self.say = say_fn
        self.wait = wait
        # out_dir=None: the director takes no screenshots of its own (run_step is used, not run_beat)
        self.director = Director(
            console,
            adb,
            out_dir=None,
            km_interval=km_interval,
            sleep=self.sleep,
            clock=self.clock,
            say_fn=say_fn,
        )

    def run(self) -> List[Check]:
        user = parse_current_user(self.adb.shell("am", "get-current-user"))
        if user is None:
            raise SetupError(
                EXIT_VERIFY_FAILED, "현재 사용자를 알 수 없습니다 (am get-current-user)"
            )
        evidence = Evidence(self.front, user, self.scenario)
        self.allow_overlay(user)
        self.adb.logcat_clear(sleep=self.sleep)
        evidence.front_error = self.show_front(user)
        if evidence.front_error is not None:
            return judge(evidence)

        beats = dict(background_beats(self.scenario, self.km_interval))
        self.say("")
        self.say("=== %s ===" % self.scenario.title)
        for name in ("ready", "depart", "drive"):
            self.play(name, beats[name])
        # still in D: the front app, the pill's log line and its window
        evidence.activities_drive = self.adb.activities()
        evidence.logcat_drive = self.poll(
            self.adb.logcat_dump,
            lambda text: shown_after_trip_start(log_events(text)) is not None,
        )
        evidence.windows_drive = self.poll(
            self.windows,
            lambda dump: any(w.visible for w in app_overlays(parse_windows(dump))),
        )
        self.screenshot(SHOT_DRIVE)
        self.keep("navmain_windows_drive.txt", evidence.windows_drive)

        for name in ("arrive", "park"):
            self.play(name, beats[name])
        evidence.logcat_park = self.poll(
            self.adb.logcat_dump,
            lambda text: (
                find_trip(text, self.scenario) is not None
                and hidden_after_shown(log_events(text)) is not None
            ),
        )
        evidence.windows_park = self.poll(
            self.windows, lambda dump: not app_overlays(parse_windows(dump))
        )
        self.screenshot(SHOT_PARK)
        self.keep("navmain_windows_park.txt", evidence.windows_park)
        self.keep("navmain_logcat.txt", evidence.logcat_park)

        # only now is AutoBrief brought to the front
        try:
            ensure_app_in_front(
                self.adb, now=self.clock, sleep=self.sleep, say_fn=self.say
            )
        except SetupError as exc:
            evidence.reopen_error = str(exc)
        evidence.activities_after = self.adb.activities()
        return judge(evidence)

    def allow_overlay(self, user: int) -> None:
        self.adb.shell(
            "appops", "set", "--user", str(user), APP_PACKAGE, OVERLAY_OP, "allow"
        )
        mode = self.adb.shell(
            "appops", "get", "--user", str(user), APP_PACKAGE, OVERLAY_OP
        )
        self.say(
            "  [점검] appops %s (user %d): %s"
            % (OVERLAY_OP, user, " ".join(mode.split())[:200])
        )

    def show_front(self, user: int) -> Optional[str]:
        """Start the chosen app and wait until it is resumed and AutoBrief is not; the reason when
        that does not happen (a FAIL: no other app is tried in its place)."""
        self.say(
            "  (%s 화면을 앞에 둡니다)"
            % ("지도" if self.front == "navigation" else "홈")
        )
        if self.front == "navigation":
            # the kit's own way to open the map: its launcher entry, never a force-stop
            if not self.director.show_navigation():
                return "지도 앱(%s)이 앞에 뜨지 않았습니다" % NAV_PACKAGE
        else:
            reply = self.adb.shell(
                "am",
                "start",
                "-W",
                "--user",
                str(user),
                "-n",
                HOME_ACTIVITY,
                timeout=60,
            )
            if re.search(r"\bError\b|Exception", reply):
                return "홈 화면을 띄우지 못했습니다: %s" % reply.strip()[:200]
        deadline = self.clock() + NAV_SHOW_WAIT_S
        while True:
            ok, seen = front_state(self.adb.activities(), user, self.front)
            if ok:
                self.say("  앞 화면: %s" % seen)
                return None
            if self.clock() >= deadline:
                return (
                    "%.0f초 안에 --front %s 화면만 앞에 있는 상태가 되지 않았습니다 (%s)"
                    % (NAV_SHOW_WAIT_S, self.front, seen)
                )
            self.sleep(1.0)

    def play(self, name: str, steps: Sequence[object]) -> None:
        self.say(director.narration_for(self.scenario, name))
        for step in steps:
            self.director.run_step(step)

    def windows(self) -> str:
        return self.adb.shell("dumpsys", "window", "windows")

    def poll(self, read: Callable[[], str], done: Callable[[str], bool]) -> str:
        """Read until `done` or `wait` seconds passed; the last reading either way (judge decides)."""
        deadline = self.clock() + self.wait
        while True:
            text = read()
            if done(text) or self.clock() >= deadline:
                return text
            self.sleep(1.0)

    def screenshot(self, name: str) -> None:
        if not self.out_dir:
            return
        png = self.adb.screencap_png()  # the kit's screenshot method
        if not png.startswith(PNG_SIGNATURE):
            raise AdbError("스크린샷이 PNG 형식이 아닙니다 (%d bytes)" % len(png))
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, name), "wb") as handle:
            handle.write(png)
        self.say("  (화면 저장: %s)" % name)

    def keep(self, name: str, text: str) -> None:
        if not self.out_dir:
            return
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, name), "w", encoding="utf-8") as handle:
            handle.write(text)


# --------------------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="navmain_check.py",
        description=(
            "다른 앱(내비게이션 또는 홈)을 주 화면으로 둔 채 운행 한 번을 재생하고, 앱이 뒤에서 기록하며 "
            "상태 표시를 띄우는지 확인합니다. 종료 코드: 0 전부 PASS, 1 FAIL 있음, 2 adb/SDK 없음, 3 콘솔 연결/요청 실패"
        ),
    )
    parser.add_argument(
        "--front",
        required=True,
        choices=FRONTS,
        help="주 화면으로 둘 앱 (navigation = 지도 앱, home = 홈 화면)",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help="km 간격을 %.1f초로 (CI용)" % FAST_KM_INTERVAL,
    )
    parser.add_argument(
        "--out",
        metavar="DIR",
        default=OUT_DIR,
        help="스크린샷과 창 목록을 저장할 폴더 (기본 %(default)s)",
    )
    parser.add_argument(
        "--odometer-start",
        type=int,
        default=ODOMETER_START,
        metavar="KM",
        help="출발 계기판 km (기본 %(default)s: 선택 장면 뒤)",
    )
    parser.add_argument(
        "--km", type=int, default=TRIP_KM, help="운행 거리 km (기본 %(default)s)"
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
    if args.km <= 0:
        parser.error("--km 는 0보다 커야 합니다")
    try:
        tools = find_sdk_tools(need_emulator=False)
    except SetupError as exc:
        say(str(exc), err=True)
        return exc.code
    adb = Adb(tools.adb, serial=args.serial)
    console = Console(args.console, notify=say)
    try:
        forward = forward_ports(args.console)
        if forward:
            adb.forward(*forward)
        console.state()
    except (AdbError, ConsoleError) as exc:
        say(unreachable_message(args.console, str(exc)), err=True)
        return EXIT_CONSOLE

    session = SessionKeeper(args.console)
    check = NavMainCheck(
        console,
        adb,
        args.front,
        out_dir=args.out,
        km_interval=FAST_KM_INTERVAL if args.fast else DEFAULT_KM_INTERVAL,
        scenario=navmain_scenario(args.odometer_start, args.km),
    )
    try:
        session.start()  # without a live browser session every console write is refused (503)
        wait_for_bridge(console)
        checks = check.run()
    except (AdbError, ConsoleError) as exc:
        say("")
        say("확인 중 오류 / Error during the check: %s" % exc, err=True)
        return EXIT_CONSOLE
    except SetupError as exc:
        say("")
        say("확인을 시작하지 못했습니다: %s" % exc, err=True)
        return EXIT_VERIFY_FAILED
    finally:
        session.stop()

    say("")
    for item in checks:
        say(item.line())
    say(result_line(checks))
    return EXIT_OK if all(c.ok for c in checks) else EXIT_VERIFY_FAILED


if __name__ == "__main__":
    sys.exit(main())
