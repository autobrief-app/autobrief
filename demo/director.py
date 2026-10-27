#!/usr/bin/env python3
"""Play scripted driving scenarios on the Pleos Vehicle Virtual Console (HTTP) for a live demo.

The scenarios are plain data (plan_beats); Director executes them against the console API
(PUT gear / odometer / controls / seat) and the emulator (adb emu geo fix, screencap), and
--verify checks the app's logcat for the expected TRIP_CLOSED line.

The console refuses every write (503 emulator_inactive) unless a browser session is alive, so
SessionKeeper beats `POST /browser/session` for the whole run; writes also wait for the
vehicle data bridge (gear.bridge_ready) and retry a 503.

Before the first scenario it records the device state (current user, what is in front, the
console state) in <out>/preflight.txt, and before every scenario it brings the app back to the
front if something else took it (the scenarios drive the app's own screen).

Python 3.9+ standard library only. Narration is Korean (presenter-facing).
Exit codes: 0 ok, 1 a --verify check failed, 2 adb/SDK missing or usage, 3 console
unreachable or rejected a request, 4 the app could not be brought to the front.
"""

import argparse
import datetime
import json
import math
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
    app_resumed,
    configure_stdio,
    console_request,
    console_url,
    find_sdk_tools,
    nmea_fix_sentences,
    parse_current_user,
    parse_front_activity,
    resumed_lines,
    resumed_records,
    say,
    start_app,
    wait_for_app_in_front,
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
REQUEST_RETRIES = 5  # a console request answered 503, or dropped without an answer, is retried this many times
REQUEST_RETRY_SLEEP_S = 1.0

EXIT_OK = 0
EXIT_VERIFY_FAILED = 1
EXIT_TOOLS = 2
EXIT_CONSOLE = 3
EXIT_APP = 4

# The app asks navigation for the position every 60 s while parked and every 6 s while driving
# (and at every P/D shift); the director holds the car at a place until the app has seen it there.
POSITION_WAIT_PARKED_S = 100.0
POSITION_WAIT_DRIVING_S = 30.0
# On Pleos Connect NaviHelper's position comes from the navigation app, and that app reads the GPS
# only while it is on screen (it shares the home's one driver slot with AutoBrief). The director
# shows the map while the app has a position to learn and AutoBrief when the trip opens and closes.
# On the emulator image the map never follows the injected GPS at all; the app then takes the
# platform GPS fix (LocationManager, every 5 s, granted by `install -g`), which does follow it,
# and logs `POSITION place=<label> source=gps`.
NAV_PACKAGE = "ai.umos.maps.android.navigation.app"
NAV_SHOW_WAIT_S = 30.0
# The app's own GPS fix comes every 5 s: a place it has not seen after this long is waited for with the
# map in front. Each switch between the map and the app is a focus change for the app, and Android
# Automotive closes an app at once when it does not take one within 5 s (no ANR dialog in the car UI
# mode): on a slow emulator a needless switch can end the run.
MAP_GRACE_S = 15.0
POSITION_RESEND_S = 1.0  # a GPS receiver reports once a second

FRONT_STABLE_S = 2.0  # before a scenario the app must hold the front this long
PREFLIGHT_FILE = "preflight.txt"

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


# --------------------------------------------------------------------------- scenario data


@dataclass(frozen=True)
class Place:
    name: str
    lat: float
    lon: float
    registered: bool = True  # the demo sample registers it in the app under the same name

    @property
    def app_label(self) -> str:
        """What the app logs for this place (`POSITION place=<label>`); '-' = no registered place."""
        return self.name if self.registered else "-"


# fictional demo places (labels only; the app's debug demo sample registers the same ones)
HOME = Place("집", 37.5444, 127.0374)
OFFICE = Place("회사", 37.3947, 127.1112)
CLIENT = Place("가상상사", 37.5126, 127.0590)
UNREGISTERED = Place("미등록 장소", 37.5665, 126.9780, registered=False)


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
class ShowApp:
    """AutoBrief in front (its own screen shows the trip opening and closing)."""


@dataclass(frozen=True)
class ShowNavigation:
    """The navigation map in front: on Pleos it only follows the GPS while it is on screen."""


@dataclass(frozen=True)
class AwaitPosition:
    """Hold the car at `place` (the fix re-sent every second) until the app logs that it is there.

    The app's purpose rules use the position it last received, so departing before it has seen
    the origin, or parking before it has seen the destination, records the wrong places."""

    place: Place
    timeout: float


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


def bearing_deg(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    """Initial great-circle bearing from a to b ((lat, lon) in degrees), 0..360, north = 0."""
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    d_lon = math.radians(b[1] - a[1])
    y = math.sin(d_lon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(d_lon)
    return math.degrees(math.atan2(y, x)) % 360.0


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
        GeoFix(origin.lat, origin.lon),
        ParkVehicle(),
        SetSeat("driver", True),
        SetOdometer(scenario.odometer_start),
        Pause(SETTLE_S * scale),
        AwaitPosition(origin, POSITION_WAIT_PARKED_S),
        ShowApp(),
    )

    depart = [SetGear("Drive")]
    for speed in ramp_speeds(0, CRUISE_KPH, RAMP_STEPS):
        depart += [
            SetControls(speed, accelerator=45, brake=0, steering=0),
            Pause(RAMP_UP_S / RAMP_STEPS * scale),
        ]

    drive = [ShowNavigation()]
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
    arrive.append(AwaitPosition(dest, POSITION_WAIT_DRIVING_S))

    park = (ParkVehicle(), ShowApp(), Pause(SETTLE_S * scale))

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


_POSITION = re.compile(r"\bPOSITION place=(\S+)")


def last_position_label(logcat: str) -> Optional[str]:
    """The place label of the app's latest `POSITION place=<label>` line, or None."""
    found = _POSITION.findall(logcat)
    return found[-1] if found else None


_POSITION_LINE = re.compile(r"\bPOSITION place=\S+[^\n]*")


def app_on_own_gps(logcat: str) -> bool:
    """True when the app's latest position came from the car's own GPS (`... source=gps`): it then
    follows the car without the map in front."""
    found = _POSITION_LINE.findall(logcat)
    return bool(found) and "source=gps" in found[-1]


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
    """The Vehicle Virtual Console API: every write is `PUT <endpoint>?<query>`, empty body; the state is `GET`.

    A request answered 503 (emulator_inactive: no live browser session yet, or the vehicle data
    bridge is still coming up), or whose connection the console closed without an answer, is
    retried REQUEST_RETRIES times, REQUEST_RETRY_SLEEP_S apart: a write sets a value and a read
    changes nothing, so sending either again is safe (e2e run 40466387103 ended on a dropped
    GET /vehicle/state). Any other failure is raised at once.
    """

    def __init__(self, base_url: str, request=console_request, sleep=None, notify=None):
        self.base = base_url.rstrip("/")
        self._request = request
        self._sleep = sleep or time.sleep
        self._notify = notify

    def _put(self, endpoint: str, params: Sequence[Tuple[str, object]]) -> str:
        query = urllib.parse.urlencode(list(params))
        return self._send("PUT", "%s%s?%s" % (self.base, endpoint, query))

    def _send(self, method: str, url: str) -> str:
        for attempt in range(REQUEST_RETRIES + 1):
            try:
                return self._request(method, url)
            except ConsoleError as exc:
                if not (exc.status == 503 or exc.dropped) or attempt == REQUEST_RETRIES:
                    raise
                if self._notify:
                    what = (
                        "연결이 응답 없이 끊겼습니다"
                        if exc.dropped
                        else "(503 %s) 콘솔이 아직 준비되지 않았습니다" % exc.body[:60]
                    )
                    self._notify(
                        "  %s. 재시도 %d/%d" % (what, attempt + 1, REQUEST_RETRIES)
                    )
                self._sleep(REQUEST_RETRY_SLEEP_S)

    def state(self) -> dict:
        text = self._send("GET", self.base + "/vehicle/state")
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


# --------------------------------------------------------------------------- device checks


def preflight_lines(adb, state: dict) -> List[str]:
    """What the device looks like right before the scenarios: user, its state, the resumed
    activities, the users and the console state (one line each, for preflight.txt)."""
    lines = []
    user_text = adb.shell("am", "get-current-user").strip()
    lines.append("current user: %s" % user_text)
    user = parse_current_user(user_text)
    if user is not None:
        lines.append(
            "user state: %s"
            % adb.shell("am", "get-started-user-state", str(user)).strip()
        )
    activities = adb.activities()
    lines.append("front: %s" % (parse_front_activity(activities) or "none"))
    lines += ["activities: %s" % line for line in resumed_lines(activities)]
    users = adb.shell("dumpsys", "user")
    lines += [
        "users: %s" % line.strip()
        for line in users.splitlines()
        if "UserInfo{" in line or "State:" in line or "urrent user" in line
    ][:20]
    lines.append("console: %s" % json.dumps(state, ensure_ascii=False, sort_keys=True))
    return lines


def write_preflight(out_dir: Optional[str], lines: Sequence[str]) -> Optional[str]:
    if not out_dir:
        return None
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, PREFLIGHT_FILE)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return path


def nav_resumed(dumpsys: str, user: Optional[int]) -> bool:
    return any(
        a.package == NAV_PACKAGE and (user is None or a.user == user)
        for _, a in resumed_records(dumpsys)
    )


def ensure_app_in_front(
    adb,
    stable: Optional[float] = None,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
    say_fn: Callable[[str], None] = say,
):
    """The scenarios play on the app's own screen: if another activity (the home, navigation) is
    in front, start the app again in the current user and wait until it holds the front."""
    stable = FRONT_STABLE_S if stable is None else stable
    user = parse_current_user(adb.shell("am", "get-current-user"))
    if user is None:
        raise SetupError(EXIT_APP, "현재 사용자를 알 수 없습니다 (am get-current-user)")
    dumpsys = adb.activities()
    ours = app_resumed(dumpsys, user)
    if ours is not None:
        return ours
    front = parse_front_activity(dumpsys)
    say_fn("  (앱이 화면 앞에 없습니다: %s. 다시 띄웁니다)" % (front or "없음"))
    start_app(adb, user)
    return wait_for_app_in_front(
        adb, user, stable=stable, now=now, sleep=sleep, log=say_fn
    )


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
        utc: Optional[Callable[[], datetime.datetime]] = None,
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
        self.utc = utc or (lambda: datetime.datetime.now(datetime.timezone.utc))
        self.beat_number = 0
        self.speed = 0
        self._geo_warned = False
        self._nmea_warned = False
        self._shot_warned = False
        self._last_fix = None  # (lat, lon) of the previous GeoFix
        self._positions_stuck = False  # a position wait timed out: later ones are skipped
        self._own_gps_said = False  # said once: the app follows the car on its own GPS, no map
        self._course = 0.0

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
        elif isinstance(step, AwaitPosition):
            self.await_position(step)
        elif isinstance(step, ShowApp):
            ensure_app_in_front(self.adb, now=self.clock, sleep=self.sleep, say_fn=self.say)
        elif isinstance(step, ShowNavigation):
            if app_on_own_gps(self.adb.logcat_dump()):
                if not self._own_gps_said:
                    self._own_gps_said = True
                    self.say("  (앱이 차량 GPS로 위치를 받고 있어 지도 화면 없이 진행합니다)")
            else:
                self.show_navigation()
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
        """Moves the emulated GPS: `geo fix` for the platform location provider, and the same fix
        as NMEA with a valid checksum for the Pleos navigation app (NaviHelper), which drops the
        emulator's own `geo fix` sentences. The app's purpose rules read NaviHelper's position, or the
        platform GPS fix (which takes both) when navigation's is stale or does not follow the car."""
        if self._last_fix is not None and self._last_fix != (step.lat, step.lon):
            self._course = bearing_deg(self._last_fix, (step.lat, step.lon))
        self._last_fix = (step.lat, step.lon)
        if not self._geo_warned:
            try:
                # `adb emu geo fix` takes longitude first
                self.adb.geo_fix(step.lon, step.lat)
            except AdbError as exc:
                self._geo_warned = True
                self.say("  (경고) 위치 이동을 건너뜁니다: %s" % exc)
        if not self._nmea_warned:
            try:
                for sentence in nmea_fix_sentences(
                    step.lat, step.lon, self.utc(), self.speed, self._course
                ):
                    self.adb.geo_nmea(sentence)
            except AdbError as exc:
                self._nmea_warned = True
                self.say("  (경고) 내비게이션 위치(NMEA) 전송을 건너뜁니다: %s" % exc)

    def show_navigation(self) -> bool:
        """Start the navigation app (its launcher activity) in the current user and wait until it is
        resumed. A refusal is a warning: the trip still runs, only the place rules may miss."""
        user = parse_current_user(self.adb.shell("am", "get-current-user"))
        if user is not None and nav_resumed(self.adb.activities(), user):
            return True
        self.say("  (지도 화면으로 바꿉니다)")
        reply = self.adb.shell(
            "am", "start", "-W", "--user", str(user if user is not None else 0),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-p", NAV_PACKAGE,
            timeout=60,
        )
        if re.search(r"\bError\b|Exception", reply):
            self.say("  (경고) 지도 앱을 띄우지 못했습니다: %s" % reply.strip()[:200])
            return False
        deadline = self.clock() + NAV_SHOW_WAIT_S
        while not nav_resumed(self.adb.activities(), user):
            if self.clock() >= deadline:
                self.say("  (경고) %.0f초 안에 지도 화면이 뜨지 않았습니다." % NAV_SHOW_WAIT_S)
                return False
            self.sleep(1.0)
        return True

    def await_position(self, step: AwaitPosition) -> None:
        if self._positions_stuck:
            return  # explained once, when the first wait ran out
        want = step.place.app_label
        started = self.clock()
        deadline = started + step.timeout
        shown = False
        while True:
            seen = last_position_label(self.adb.logcat_dump())
            if seen != want and not shown and self.clock() - started >= MAP_GRACE_S:
                shown = True  # the app's own GPS did not see it: the map has to be on screen to follow the GPS
                self.show_navigation()
            if seen == want:
                self.say("  - 앱이 위치를 확인했습니다: %s" % step.place.name)
                return
            if self.clock() >= deadline:
                self.say(
                    "  (경고) %.0f초 안에 앱이 위치(%s)를 확인하지 못했습니다 (앱의 마지막 위치: %s). 그대로 진행합니다."
                    % (step.timeout, step.place.name, seen or "없음")
                )
                # On the Pleos Connect emulator the map drops the emulator's GPS sentences (their
                # checksum is a constant *47), so NaviHelper never moves: waiting again only costs time
                self.say(
                    "  (경고) 내비게이션 위치가 GPS를 따라가지 않습니다. 이후 위치 확인은 건너뜁니다 "
                    "(장소 규칙: 출퇴근, 업무 분류는 맞지 않을 수 있습니다)."
                )
                self._positions_stuck = True
                return
            self.sleep(POSITION_RESEND_S)
            self.geo_fix(GeoFix(step.place.lat, step.place.lon))

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
            "종료 코드: 0 성공, 1 --verify 실패, 2 adb/SDK 없음 또는 사용법 오류, 3 콘솔 연결/요청 실패, "
            "4 앱을 화면 앞에 띄우지 못함"
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
        lines = preflight_lines(adb, console.state())
        for line in lines:
            say("  [점검] " + line[:300])
        saved = write_preflight(args.out, lines)
        if saved:
            say("  (점검 기록: %s)" % saved)
        if args.verify:
            adb.logcat_clear()
        for scenario in select_scenarios(args.scenario):
            ensure_app_in_front(adb)
            director.run_scenario(scenario)
            if args.verify:
                say("  logcat 확인 중 (최대 %.0f초)..." % VERIFY_TIMEOUT_S)
                verdicts.append(verify_scenario(adb, scenario))
                report_verdict(verdicts[-1])
    except (AdbError, ConsoleError) as exc:
        say("")
        say("시연 중 오류 / Error during the demo: %s" % exc, err=True)
        return EXIT_CONSOLE
    except SetupError as exc:
        say("")
        say("앱을 화면 앞에 띄우지 못했습니다 / The app is not in front: %s" % exc, err=True)
        return EXIT_APP
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
