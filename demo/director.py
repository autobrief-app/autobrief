#!/usr/bin/env python3
"""Play scripted driving scenarios on the Pleos Vehicle Virtual Console (HTTP) for a live demo.

The scenarios are plain data (plan_beats); Director executes them against the console API
(PUT gear / odometer / controls / seat) and the emulator (adb emu geo fix, screencap), and
--verify checks the app's logcat for the expected TRIP_CLOSED line.

The console refuses every write (503 emulator_inactive) unless a browser session is alive, so
SessionKeeper beats `POST /browser/session` for the whole run; writes also wait for the
vehicle data bridge (gear.bridge_ready) and retry a 503.

Python 3.9+ standard library only. Narration is Korean (presenter-facing).
Exit codes: 0 ok, 1 a --verify check failed, 2 adb/SDK missing or usage, 3 console
unreachable or rejected a request.
"""

import argparse
import json
import os
import random
import re
import sys
import threading
import time
import urllib.parse
from dataclasses import dataclass
from typing import Callable, List, Optional, Sequence, Tuple

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from setup_emulator import (  # noqa: E402  (shared helpers live next to this script)
    CONSOLE_GUEST_PORT,
    DEFAULT_SERIAL,
    Adb,
    AdbError,
    ConsoleError,
    SetupError,
    configure_stdio,
    console_request,
    console_url,
    find_sdk_tools,
    say,
)

# --------------------------------------------------------------------------- constants

GEARS = ("Park", "Reverse", "Neutral", "Drive")
SEAT_AREAS = ("driver", "passenger", "rear_left", "rear_right")
ODOMETER_RANGE = (0, 1000000)  # the console takes whole km

DEFAULT_KM_INTERVAL = 1.2  # seconds per odometer km tick
FAST_KM_INTERVAL = 0.3
CRUISE_KPH = 70
JITTER_KPH = (55, 80)  # per-tick speed while cruising
RAMP_STEPS = 8
RAMP_UP_S = 4.0
RAMP_DOWN_S = 3.0
SETTLE_S = 1.5  # let the app react before the screenshot
VERIFY_TIMEOUT_S = 20.0
GEAR_CONFIRM_TIMEOUT_S = 10.0
SESSION_CLIENT = "autobrief-director"  # our client id on POST /browser/session
SESSION_INTERVAL_S = 1.0  # the console page itself beats once a second
BRIDGE_WAIT_S = 300.0  # gear.bridge_ready is false for a while after boot
WRITE_RETRIES = 5  # a write answered 503 is retried this many times
WRITE_RETRY_SLEEP_S = 1.0

EXIT_OK = 0
EXIT_VERIFY_FAILED = 1
EXIT_TOOLS = 2
EXIT_CONSOLE = 3

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


# --------------------------------------------------------------------------- scenario data


@dataclass(frozen=True)
class Place:
    name: str
    lat: float
    lon: float


# fictional demo places (labels only; the app's debug demo sample registers the same ones)
HOME = Place("집", 37.5444, 127.0374)
OFFICE = Place("회사", 37.3947, 127.1112)
CLIENT = Place("가상상사", 37.5126, 127.0590)
UNREGISTERED = Place("미등록 장소", 37.5665, 126.9780)


@dataclass(frozen=True)
class Scenario:
    key: str
    title: str
    origin: Place
    destination: Place
    odometer_start: int
    km: int
    expected_purpose: str
    park_note: str  # tail of the last narration line

    @property
    def odometer_end(self) -> int:
        return self.odometer_start + self.km


# odometers continue from one scenario to the next (45,200 -> 45,218 -> 45,229 -> 45,236)
SCENARIOS = (
    Scenario(
        "commute",
        "출퇴근 (집 -> 회사)",
        HOME,
        OFFICE,
        45200,
        18,
        "COMMUTE",
        "출퇴근으로 분류됩니다",
    ),
    Scenario(
        "client",
        "고객사 방문 (회사 -> 가상상사)",
        OFFICE,
        CLIENT,
        45218,
        11,
        "BUSINESS",
        "업무로 분류됩니다",
    ),
    Scenario(
        "personal",
        "개인 이동 (가상상사 -> 미등록 장소)",
        CLIENT,
        UNREGISTERED,
        45229,
        7,
        "UNCLASSIFIED",
        "앱이 운전자에게 목적을 묻습니다",
    ),
)

# Korean particles are fixed on purpose: every demo place ends in a vowel (-에서 / -로 / -에)
NARRATION = {
    "ready": "준비: {origin} 앞에 주차한 상태입니다 (기어 P, 계기판 {start} km, 운전석 착석)",
    "depart": "① {origin}에서 출발합니다: 기어 D",
    "drive": "② {dest}로 이동합니다: 계기판이 1 km씩 올라갑니다 ({start} -> {end} km, 총 {km} km)",
    "arrive": "③ {dest}에 도착했습니다: 속도를 줄입니다",
    "park": "④ 주차합니다: 기어 P, 운행기록이 닫히고 {note}",
}


def scenario_keys() -> List[str]:
    return [s.key for s in SCENARIOS]


def select_scenarios(choice: str) -> List[Scenario]:
    if choice == "all":
        return list(SCENARIOS)
    chosen = [s for s in SCENARIOS if s.key == choice]
    if not chosen:
        raise ValueError("알 수 없는 시나리오: %s" % choice)
    return chosen


def narration_for(scenario: Scenario, beat_name: str) -> str:
    return NARRATION[beat_name].format(
        origin=scenario.origin.name,
        dest=scenario.destination.name,
        start="{:,}".format(scenario.odometer_start),
        end="{:,}".format(scenario.odometer_end),
        km=scenario.km,
        note=scenario.park_note,
    )


# --------------------------------------------------------------------------- steps and plans


@dataclass(frozen=True)
class SetGear:
    gear: str


@dataclass(frozen=True)
class SetOdometer:
    km: int


@dataclass(frozen=True)
class SetControls:
    speed: int
    accelerator: Optional[int] = None
    brake: Optional[int] = None
    steering: Optional[int] = None


@dataclass(frozen=True)
class SetSeat:
    area: str
    occupied: bool


@dataclass(frozen=True)
class GeoFix:
    lat: float
    lon: float


@dataclass(frozen=True)
class Pause:
    seconds: float


@dataclass(frozen=True)
class ParkVehicle:
    """The console's own Park routine: speed/pedals to 0, gear Park, confirm via /vehicle/state."""


@dataclass(frozen=True)
class Beat:
    name: str
    narration: str
    steps: Tuple[object, ...]
    prompt: bool = True  # --mode step waits for Enter before this beat


def lerp_point(a: Place, b: Place, fraction: float) -> Tuple[float, float]:
    return (a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)


def ramp_speeds(start: int, end: int, steps: int) -> List[int]:
    """`steps` linear speeds from start (exclusive) to end (inclusive)."""
    return [int(round(start + (end - start) * i / steps)) for i in range(1, steps + 1)]


def jitter_speeds(key: str, count: int) -> List[int]:
    """Cruise speeds in JITTER_KPH; seeded by the scenario so a rerun looks identical."""
    rng = random.Random("autobrief-demo:" + key)
    return [rng.randint(*JITTER_KPH) for _ in range(count)]


def time_scale(km_interval: float) -> float:
    """Ramps and pauses shrink with the km tick (--fast keeps the same shape, just quicker)."""
    return km_interval / DEFAULT_KM_INTERVAL


def plan_beats(
    scenario: Scenario, km_interval: float = DEFAULT_KM_INTERVAL
) -> List[Beat]:
    scale = time_scale(km_interval)
    origin, dest = scenario.origin, scenario.destination
    speeds = jitter_speeds(scenario.key, scenario.km)

    ready = (
        ParkVehicle(),
        SetSeat("driver", True),
        GeoFix(origin.lat, origin.lon),
        SetOdometer(scenario.odometer_start),
        Pause(SETTLE_S * scale),
    )

    depart = [SetGear("Drive")]
    for speed in ramp_speeds(0, CRUISE_KPH, RAMP_STEPS):
        depart += [
            SetControls(speed, accelerator=45, brake=0, steering=0),
            Pause(RAMP_UP_S / RAMP_STEPS * scale),
        ]

    drive = []
    for tick in range(1, scenario.km + 1):
        # strictly between origin and destination: the arrival fix is the beat's own step
        lat, lon = lerp_point(origin, dest, tick / (scenario.km + 1))
        drive += [
            SetControls(speeds[tick - 1], accelerator=20, brake=0, steering=0),
            SetOdometer(scenario.odometer_start + tick),
            GeoFix(lat, lon),
            Pause(km_interval),
        ]

    arrive = [GeoFix(dest.lat, dest.lon)]
    for speed in ramp_speeds(CRUISE_KPH, 0, RAMP_STEPS):
        arrive += [
            SetControls(speed, accelerator=0, brake=30, steering=0),
            Pause(RAMP_DOWN_S / RAMP_STEPS * scale),
        ]

    park = (ParkVehicle(), Pause(SETTLE_S * scale))

    bodies = (
        ("ready", ready, False),
        ("depart", tuple(depart), True),
        ("drive", tuple(drive), True),
        ("arrive", tuple(arrive), True),
        ("park", park, True),
    )
    return [
        Beat(name, narration_for(scenario, name), steps, prompt)
        for name, steps, prompt in bodies
    ]


def screenshot_name(index: int, scenario_key: str, beat_name: str) -> str:
    return "%02d_%s_%s.png" % (index, scenario_key, beat_name)


# --------------------------------------------------------------------------- logcat


@dataclass(frozen=True)
class TripClosed:
    trip_id: str
    start: float
    end: float
    km: Optional[float]
    purpose: str
    line: str


_TRIP_CLOSED = re.compile(r"\bTRIP_CLOSED\b")
_FIELD = re.compile(r"(?:^|\s)([A-Za-z_]+)=(\S+)")


def parse_trip_closed(line: str) -> Optional[TripClosed]:
    """`TRIP_CLOSED id=.. start=45200.0 end=45218.0 km=18.0 purpose=COMMUTE ..` -> TripClosed."""
    found = _TRIP_CLOSED.search(line)
    if not found:
        return None
    fields = dict(_FIELD.findall(line[found.end() :]))
    try:
        start, end = float(fields["start"]), float(fields["end"])
        km = float(fields["km"]) if "km" in fields else None
    except (KeyError, ValueError):
        return (
            None  # a TRIP_CLOSED line without usable start/end can match no expectation
        )
    return TripClosed(
        fields.get("id", ""), start, end, km, fields.get("purpose", ""), line.strip()
    )


def trip_closed_events(logcat: str) -> List[TripClosed]:
    parsed = (parse_trip_closed(line) for line in logcat.splitlines())
    return [event for event in parsed if event is not None]


def matches_expected(
    trip: TripClosed, scenario: Scenario, tolerance: float = 0.05
) -> bool:
    return (
        abs(trip.start - scenario.odometer_start) <= tolerance
        and abs(trip.end - scenario.odometer_end) <= tolerance
        and trip.purpose == scenario.expected_purpose
    )


def find_expected_trip(
    events: Sequence[TripClosed], scenario: Scenario
) -> Optional[TripClosed]:
    for event in events:
        if matches_expected(event, scenario):
            return event
    return None


@dataclass
class Verdict:
    scenario: Scenario
    ok: bool
    trip: Optional[TripClosed]
    seen: List[TripClosed]


def verify_scenario(
    adb,
    scenario: Scenario,
    timeout: Optional[float] = None,
    interval: float = 1.0,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> Verdict:
    """Poll `adb logcat -d -s AutoBriefLog:I` until the expected TRIP_CLOSED appears or time is up."""
    timeout = VERIFY_TIMEOUT_S if timeout is None else timeout
    now = now or time.monotonic
    sleep = sleep or time.sleep
    deadline = now() + timeout
    while True:
        events = trip_closed_events(adb.logcat_dump())
        hit = find_expected_trip(events, scenario)
        if hit is not None:
            return Verdict(scenario, True, hit, events)
        if now() >= deadline:
            return Verdict(scenario, False, None, events)
        sleep(interval)


def expectation_text(scenario: Scenario) -> str:
    return "start=%.1f end=%.1f purpose=%s" % (
        scenario.odometer_start,
        scenario.odometer_end,
        scenario.expected_purpose,
    )


# --------------------------------------------------------------------------- console API


def gear_of(state: dict) -> Optional[str]:
    try:
        return state["gear"]["actual"]["value"]
    except (KeyError, TypeError):
        return None  # state without gear.actual.value: gear unknown


def bridge_ready(state: dict) -> bool:
    """gear.bridge_ready: the vehicle data bridge behind the console is up (false right after boot)."""
    try:
        return state["gear"]["bridge_ready"] is True
    except (KeyError, TypeError):
        return False


class Console:
    """The Vehicle Virtual Console write API: every write is `PUT <endpoint>?<query>`, empty body.

    A write answered 503 (emulator_inactive: no live browser session yet, or the vehicle data
    bridge is still coming up) is retried WRITE_RETRIES times, WRITE_RETRY_SLEEP_S apart; any
    other failure is raised at once. Reads are never retried.
    """

    def __init__(self, base_url: str, request=console_request, sleep=None, notify=None):
        self.base = base_url.rstrip("/")
        self._request = request
        self._sleep = sleep or time.sleep
        self._notify = notify

    def _put(self, endpoint: str, params: Sequence[Tuple[str, object]]) -> str:
        query = urllib.parse.urlencode(list(params))
        url = "%s%s?%s" % (self.base, endpoint, query)
        for attempt in range(WRITE_RETRIES + 1):
            try:
                return self._request("PUT", url)
            except ConsoleError as exc:
                if exc.status != 503 or attempt == WRITE_RETRIES:
                    raise
                if self._notify:
                    self._notify(
                        "  (503 %s) 콘솔이 아직 준비되지 않았습니다. 재시도 %d/%d"
                        % (exc.body[:60], attempt + 1, WRITE_RETRIES)
                    )
                self._sleep(WRITE_RETRY_SLEEP_S)

    def state(self) -> dict:
        text = self._request("GET", self.base + "/vehicle/state")
        try:
            data = json.loads(text)
        except ValueError:
            data = None
        if not isinstance(data, dict):
            raise ConsoleError(
                "GET /vehicle/state 응답이 JSON 객체가 아닙니다: %s" % text[:200]
            )
        return data

    def set_gear(self, gear: str) -> None:
        if gear not in GEARS:
            raise ValueError("gear must be one of %s, got %r" % (GEARS, gear))
        self._put("/vehicle/gear", [("gear", gear)])

    def set_odometer(self, km: int) -> None:
        low, high = ODOMETER_RANGE
        if not (isinstance(km, int) and low <= km <= high):
            raise ValueError(
                "odometer must be an int in %d..%d km, got %r" % (low, high, km)
            )
        self._put("/vehicle/odometer", [("traveled_distance_km", km)])

    def set_controls(
        self,
        speed: int,
        accelerator: Optional[int] = None,
        brake: Optional[int] = None,
        steering: Optional[int] = None,
    ) -> None:
        for name, value in (("accelerator", accelerator), ("brake", brake)):
            if value is not None and not 0 <= value <= 100:
                raise ValueError("%s must be 0..100, got %r" % (name, value))
        params = [("speed", speed)]
        params += [
            (name, value)
            for name, value in (
                ("accelerator", accelerator),
                ("brake", brake),
                ("steering", steering),
            )
            if value is not None
        ]
        self._put("/vehicle/controls", params)

    def set_seat_occupied(self, area: str, occupied: bool) -> None:
        if area not in SEAT_AREAS:
            raise ValueError("seat area must be one of %s, got %r" % (SEAT_AREAS, area))
        self._put(
            "/vehicle/seat",
            [
                ("kind", "occupied"),
                ("value", "true" if occupied else "false"),
                ("area", area),
            ],
        )


class SessionKeeper:
    """Keeps the console's browser session alive for the whole run.

    The console refuses every write (503 emulator_inactive) unless a browser session is alive.
    Its web page keeps one with `POST /browser/session?client=<id>` once a second and sends
    `...&closing=1` when it closes; we do the same under our own client id, so the demo also
    works headless (CI) or with the console page closed.
    """

    def __init__(
        self,
        base_url: str,
        client: str = SESSION_CLIENT,
        interval: Optional[float] = None,
        request=console_request,
        say_fn: Callable[[str], None] = say,
    ):
        self.url = base_url.rstrip("/") + "/browser/session"
        self.client = client
        self.interval = SESSION_INTERVAL_S if interval is None else interval
        self._request = request
        self._say = say_fn
        self._stop = threading.Event()
        self._thread = None
        self.beats = 0
        self.errors = 0

    def _beat(self, closing: bool = False) -> None:
        params = [("client", self.client)]
        if closing:
            params.append(("closing", "1"))
        self._request("POST", "%s?%s" % (self.url, urllib.parse.urlencode(params)))

    def start(self) -> None:
        """First beat now (the session is alive before the first write), then one per interval."""
        if self._thread is not None:
            return
        self._beat()
        self.beats += 1
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._run, name="console-session", daemon=True
        )
        self._thread.start()

    def _run(self) -> None:
        while not self._stop.wait(self.interval):
            try:
                self._beat()
                self.beats += 1
            except ConsoleError as exc:
                self.errors += 1
                if self.errors == 1:
                    self._say("  (경고) 콘솔 세션 유지 신호가 실패했습니다: %s" % exc)

    def stop(self) -> None:
        """Stop beating, then tell the console the session is closing. Never raises (it runs on
        the error and Ctrl+C paths and must not mask the original failure)."""
        thread, self._thread = self._thread, None
        if thread is None:
            return
        self._stop.set()
        thread.join(timeout=5.0)
        try:
            self._beat(closing=True)
        except ConsoleError as exc:
            self._say("  (경고) 콘솔 세션 종료 신호를 보내지 못했습니다: %s" % exc)

    def __enter__(self) -> "SessionKeeper":
        self.start()
        return self

    def __exit__(self, *exc_info) -> None:
        self.stop()


def bridge_timeout_message(timeout: float, state: dict) -> str:
    gear = state.get("gear") if isinstance(state.get("gear"), dict) else {}
    active = state.get("emulator_active", gear.get("emulator_active"))
    return "\n".join(
        [
            "차량 데이터 연결(bridge)이 %.0f초 안에 준비되지 않았습니다. / The vehicle data bridge did not become ready within %.0f s."
            % (timeout, timeout),
            "  - 에뮬레이터를 방금 켰다면 1~2분 뒤에 다시 실행하세요. / If the emulator just booted, wait a minute or two and rerun.",
            "  - 마지막 상태 / Last state: gear.bridge_ready=%r, emulator_active=%r"
            % (gear.get("bridge_ready"), active),
        ]
    )


def wait_for_bridge(
    console,
    timeout: Optional[float] = None,
    interval: float = 1.0,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
    say_fn: Callable[[str], None] = say,
) -> None:
    """Poll GET /vehicle/state until gear.bridge_ready is true (writes fail with 503 before that)."""
    timeout = BRIDGE_WAIT_S if timeout is None else timeout
    now = now or time.monotonic
    sleep = sleep or time.sleep
    deadline = now() + timeout
    noted = None
    while True:
        state = console.state()
        if bridge_ready(state):
            return
        if now() >= deadline:
            raise ConsoleError(bridge_timeout_message(timeout, state))
        if noted is None or now() - noted >= 30:
            noted = now()
            say_fn("  차량 데이터 연결(bridge)을 기다립니다 (최대 %.0f초)..." % timeout)
        sleep(interval)


# --------------------------------------------------------------------------- executor


class Director:
    """Runs plan_beats() against a console + emulator; sleep/clock/ask/say are injectable."""

    def __init__(
        self,
        console,
        adb,
        out_dir: Optional[str] = None,
        mode: str = "auto",
        km_interval: float = DEFAULT_KM_INTERVAL,
        sleep: Optional[Callable[[float], None]] = None,
        clock: Optional[Callable[[], float]] = None,
        ask: Callable[[str], str] = input,
        say_fn: Callable[[str], None] = say,
    ):
        self.console = console
        self.adb = adb
        self.out_dir = out_dir
        self.mode = mode
        self.km_interval = km_interval
        self.sleep = sleep or time.sleep
        self.clock = clock or time.monotonic
        self.ask = ask
        self.say = say_fn
        self.beat_number = 0
        self.speed = 0
        self._geo_warned = False
        self._shot_warned = False

    def run_scenario(self, scenario: Scenario) -> None:
        self.say("")
        self.say("=== %s ===" % scenario.title)
        for beat in plan_beats(scenario, self.km_interval):
            self.run_beat(scenario, beat)

    def run_beat(self, scenario: Scenario, beat: Beat) -> None:
        self.say(beat.narration)
        if self.mode == "step" and beat.prompt:
            self.ask("  [Enter] 실행 > ")
        for step in beat.steps:
            self.run_step(step)
        self.beat_number += 1
        self.screenshot(scenario, beat)

    def run_step(self, step: object) -> None:
        if isinstance(step, Pause):
            self.sleep(step.seconds)
        elif isinstance(step, SetGear):
            self.console.set_gear(step.gear)
            self.confirm_gear(step.gear)
        elif isinstance(step, SetOdometer):
            self.console.set_odometer(step.km)
            self.say("  - 계기판 {:,} km, 속도 {} km/h".format(step.km, self.speed))
        elif isinstance(step, SetControls):
            self.console.set_controls(
                step.speed, step.accelerator, step.brake, step.steering
            )
            self.speed = step.speed
        elif isinstance(step, SetSeat):
            self.console.set_seat_occupied(step.area, step.occupied)
        elif isinstance(step, GeoFix):
            self.geo_fix(step)
        elif isinstance(step, ParkVehicle):
            self.park()
        else:
            raise TypeError("unknown step: %r" % (step,))

    def park(self) -> None:
        self.console.set_controls(0, accelerator=0, brake=0)
        self.speed = 0
        self.console.set_gear("Park")
        self.confirm_gear("Park")

    def confirm_gear(self, wanted: str) -> None:
        deadline = self.clock() + GEAR_CONFIRM_TIMEOUT_S
        while True:
            actual = gear_of(self.console.state())
            if actual == wanted:
                return
            if self.clock() >= deadline:
                raise ConsoleError(
                    "기어가 %s(으)로 바뀌지 않았습니다 (/vehicle/state: %s)"
                    % (wanted, actual)
                )
            self.sleep(0.5)

    def geo_fix(self, step: GeoFix) -> None:
        if self._geo_warned:
            return  # the emulator console refused once; the scenario does not depend on it
        try:
            # `adb emu geo fix` takes longitude first
            self.adb.geo_fix(step.lon, step.lat)
        except AdbError as exc:
            self._geo_warned = True
            self.say("  (경고) 위치 이동을 건너뜁니다: %s" % exc)

    def screenshot(self, scenario: Scenario, beat: Beat) -> None:
        if not self.out_dir or self._shot_warned:
            return
        name = screenshot_name(self.beat_number, scenario.key, beat.name)
        try:
            png = self.adb.screencap_png()
            if not png.startswith(PNG_SIGNATURE):
                raise AdbError("PNG 형식이 아닙니다 (%d bytes)" % len(png))
        except AdbError as exc:
            self._shot_warned = True
            self.say("  (경고) 스크린샷을 건너뜁니다: %s" % exc)
            return
        os.makedirs(self.out_dir, exist_ok=True)
        with open(os.path.join(self.out_dir, name), "wb") as handle:
            handle.write(png)
        self.say("  (화면 저장: %s)" % name)


# --------------------------------------------------------------------------- CLI


def forward_ports(url: str) -> Optional[Tuple[int, int]]:
    """(host port, guest port) for `adb forward`, or None when the console is not on loopback."""
    parts = urllib.parse.urlsplit(url)
    if parts.hostname not in ("127.0.0.1", "localhost", "::1") or not parts.port:
        return None
    return parts.port, CONSOLE_GUEST_PORT


def unreachable_message(url: str, detail: str) -> str:
    return "\n".join(
        [
            "가상 콘솔(%s)에 연결할 수 없습니다. / Cannot reach the Vehicle Virtual Console at %s."
            % (url, url),
            "  - 에뮬레이터가 켜져 있나요? 먼저 python3 demo/setup_emulator.py 를 실행하세요.",
            "    Is the emulator running? Run demo/setup_emulator.py first.",
            "  - adb devices 에 에뮬레이터가 보이나요? / Does `adb devices` list it?",
            "원인 / Detail: %s" % detail,
        ]
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="director.py",
        description=(
            "Pleos 가상 콘솔로 운행 시나리오를 재생합니다. "
            "(Play scripted drives on the Pleos Vehicle Virtual Console.)"
        ),
        epilog=(
            "시나리오: commute 집->회사 18km(출퇴근), client 회사->가상상사 11km(업무), "
            "personal 가상상사->미등록 장소 7km(미분류). "
            "종료 코드: 0 성공, 1 --verify 실패, 2 adb/SDK 없음 또는 사용법 오류, 3 콘솔 연결/요청 실패"
        ),
    )
    parser.add_argument(
        "--scenario",
        required=True,
        choices=scenario_keys() + ["all"],
        help="재생할 시나리오 (all = 순서대로 전부, 계기판은 이어짐)",
    )
    parser.add_argument(
        "--mode",
        choices=("auto", "step"),
        default="auto",
        help="auto: 시간표대로 자동 / step: 장면마다 Enter를 기다림 (발표용)",
    )
    parser.add_argument(
        "--km-interval",
        type=float,
        default=DEFAULT_KM_INTERVAL,
        metavar="SEC",
        help="계기판 1 km 올릴 때마다 쉬는 초 (기본 %(default)s)",
    )
    parser.add_argument(
        "--fast",
        action="store_true",
        help="리허설/CI용: km 간격을 %.1f초로 (--km-interval 보다 우선)"
        % FAST_KM_INTERVAL,
    )
    parser.add_argument(
        "--out", metavar="DIR", help="장면마다 스크린샷(PNG)을 저장할 폴더"
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="시나리오가 끝날 때마다 logcat 에서 기대한 TRIP_CLOSED 를 확인 (PASS/FAIL)",
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


def report_verdict(verdict: Verdict) -> None:
    key = verdict.scenario.key
    if verdict.ok:
        say("[PASS] %s: %s" % (key, verdict.trip.line))
        return
    say(
        "[FAIL] %s: %.0f초 안에 기대한 TRIP_CLOSED(%s)를 찾지 못했습니다"
        % (key, VERIFY_TIMEOUT_S, expectation_text(verdict.scenario))
    )
    if verdict.seen:
        for event in verdict.seen[-3:]:
            say("       로그에 있던 TRIP_CLOSED: %s" % event.line)
    else:
        say(
            "       로그에 TRIP_CLOSED 줄이 하나도 없습니다 (adb logcat -d -s AutoBriefLog:I 로 확인하세요)"
        )


def main(argv: Optional[Sequence[str]] = None) -> int:
    configure_stdio()
    parser = build_parser()
    args = parser.parse_args(argv)
    km_interval = FAST_KM_INTERVAL if args.fast else args.km_interval
    if km_interval <= 0:
        parser.error("--km-interval 은 0보다 커야 합니다")
    if args.mode == "step" and not sys.stdin.isatty():
        parser.error(
            "--mode step 은 터미널 입력(Enter)이 필요합니다. 자동 재생은 --mode auto"
        )

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
    director = Director(
        console, adb, out_dir=args.out, mode=args.mode, km_interval=km_interval
    )
    verdicts = []
    try:
        session.start()  # without a live browser session every write is refused (503)
        say(
            "콘솔 세션을 유지합니다 (client=%s, %.1f초마다)."
            % (session.client, session.interval)
        )
        wait_for_bridge(console)
        if args.verify:
            adb.logcat_clear()
        for scenario in select_scenarios(args.scenario):
            director.run_scenario(scenario)
            if args.verify:
                say("  logcat 확인 중 (최대 %.0f초)..." % VERIFY_TIMEOUT_S)
                verdicts.append(verify_scenario(adb, scenario))
                report_verdict(verdicts[-1])
    except (AdbError, ConsoleError) as exc:
        say("")
        say("시연 중 오류 / Error during the demo: %s" % exc, err=True)
        return EXIT_CONSOLE
    except KeyboardInterrupt:
        say(
            "\n중단했습니다. 차량이 주행 중일 수 있습니다 (다시 실행하면 먼저 주차 P 로 맞춥니다)."
        )
        return 130
    finally:
        session.stop()  # closing=1, also after an error or Ctrl+C

    say("")
    say("시나리오 재생을 마쳤습니다.")
    if not args.verify:
        return EXIT_OK
    passed = sum(1 for v in verdicts if v.ok)
    say("검증 결과: %d/%d PASS" % (passed, len(verdicts)))
    return EXIT_OK if passed == len(verdicts) else EXIT_VERIFY_FAILED


if __name__ == "__main__":
    sys.exit(main())
