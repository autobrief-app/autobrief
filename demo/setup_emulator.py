#!/usr/bin/env python3
"""Install and boot the official Pleos Connect v2.1.0 emulator for the AutoBrief demo.

One command: find the Android SDK, download + verify + extract the Pleos system image
(HTTP Range resume, sparse-aware extraction), write the AVD by hand (no avdmanager), cold
boot it, install the APK, forward the Vehicle Virtual Console (tcp:8088) and open it.

Python 3.9+ standard library only. Printed messages are Korean (presenter-facing).
director.py imports the shared helpers defined here (SDK discovery, adb, console HTTP).

Exit codes: 0 ok, 1 other failure, 2 missing tools or usage, 3 download/verify failure,
4 boot timeout, 5 console unreachable.
"""

import argparse
import datetime
import hashlib
import http.client
import os
import platform
import re
import shlex
import shutil
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import xml.etree.ElementTree as ET
import zipfile
import zlib
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Sequence, Tuple

# --------------------------------------------------------------------------- constants

AVD_NAME = "Pleos_Connect_v2.1.0"
AVD_DISPLAY_NAME = "Pleos Connect v2.1.0"
API_LEVEL = 34
# <sdk>/system-images/android-34/pleos-connect/<abi>/
IMAGE_SUBDIRS = ("system-images", "android-%d" % API_LEVEL, "pleos-connect")
# where Android Studio's SDK Manager puts the same image (repository path system-images;android-34;pleos_car_emulator;<abi>)
OFFICIAL_IMAGE_SUBDIRS = (
    "system-images",
    "android-%d" % API_LEVEL,
    "pleos_car_emulator",
)

REPO_BASE = "https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/"
REPO_XML_URLS = {
    "x86_64": REPO_BASE + "crp-sys-img.xml",
    "arm64-v8a": REPO_BASE + "crp-arm64-sys-img.xml",
}
MAX_XML_BYTES = 5 * 1024 * 1024
USER_AGENT = "autobrief-demo-kit/1.0"

AVD_RAM_MB = 4096
AVD_LCD = (2560, 1440, 160)  # width, height, dpi

APP_PACKAGE = "app.autobrief.car"
APP_ACTIVITY = APP_PACKAGE + "/.ui.MainActivity"
DEMO_ACTION = APP_PACKAGE + ".debug.DEMO"
LOG_TAG = "AutoBriefLog"

DEFAULT_SERIAL = "emulator-5554"
CONSOLE_HOST_PORT = 8088  # read at call time (tests patch it)
CONSOLE_GUEST_PORT = 8088

BOOT_TIMEOUT_HEADLESS_S = 900
BOOT_TIMEOUT_GUI_S = 420
CONSOLE_WAIT_S = 180
DEMO_READY_WAIT_S = 60
# sys.boot_completed comes from the headless system user (0); the driver's user (10) unlocks and
# the home comes up behind the lock screen tens of seconds later (measured: about 50 s on CI)
USER_READY_WAIT_S = 300
APP_FRONT_WAIT_S = 120
# the home opens its own panels right after unlock: ours must stay in front this long
APP_FRONT_STABLE_S = 5.0
APP_RELAUNCH_EVERY_S = 5.0
VEHICLE_CONNECTED_WAIT_S = 120
# the default logcat ring rotates out the setup lines within minutes
LOGCAT_BUFFER_SIZE = "32M"
LOGCAT_CLEAR_TRIES = 6  # logd refuses a clear while a reader lags behind; it catches up within seconds
LOGCAT_CLEAR_RETRY_S = 2.0
HSUM_PROP = "ro.fw.mu.headless_system_user"
USER_UNLOCKED = "RUNNING_UNLOCKED"
SETUP_WIZARD_MARK = "setupwizard"  # ai.umos.setupwizard on Pleos Connect
# Settings' / SDK's stand-in launchers while the user is still unlocking; the real home comes after them
FALLBACK_HOME_MARK = "FallbackHome"
SDK_SETUP_MARK = "sdksetup"  # com.android.sdksetup/.DefaultActivity before HomeActivity takes over
HOME_ACTIVITY = "ai.umos.homescreen/.HomeActivity"
HOME_RESTART_SETTLE_S = 10.0
NAV_PACKAGE = "ai.umos.maps.android.navigation.app"
NAV_UID_OPS = ("FINE_LOCATION", "COARSE_LOCATION")
NAV_QUIET_OPS = ("FINE_LOCATION", "COARSE_LOCATION", "GPS", "RUN_ANY_IN_BACKGROUND")
NAV_AM_CONSTANTS = (
    "background_settle_time=7200000,"
    "no_kill_cached_processes_post_boot_completed_duration_millis=7200000"
)
# On the 2560x1600 Pleos screen, a rendered frame is 190..540 KB; when HomeActivity's ConnectTaskView
# SurfaceSyncGroup wedges during cold boot (CI run 41292587613), both slots stay flat #F7F7F7 (~55..61 KB)
BLANK_SLOT_MIN_BYTES = 50_000
BLANK_SLOT_MAX_BYTES = 70_000
# BOOT_COMPLETED of the driver's user reaches every app after the unlock (measured on CI: 55 s
# after FallbackHome, with the CPU saturated meanwhile); the app starts after that settles
BROADCAST_IDLE_WAIT_S = 300

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_TOOLS = 2
EXIT_DOWNLOAD = 3
EXIT_BOOT = 4
EXIT_CONSOLE = 5

# disk the whole flow needs (zip in the cache + extracted image); Windows has no sparse files
NEED_FREE_GB = {"Windows": 25}
NEED_FREE_GB_DEFAULT = 10

BLOCK = 1 << 20  # 1 MiB: download chunk, hash chunk and sparse-extraction block


class SetupError(Exception):
    """A failure that maps to a process exit code; the message is already presentable."""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


# --------------------------------------------------------------------------- output


def say(message: str = "", err: bool = False) -> None:
    print(message, file=sys.stderr if err else sys.stdout, flush=True)


def configure_stdio() -> None:
    """Korean/circled-digit output must not crash on a cp1252/cp949 pipe (Windows CI, tee)."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace")


def gb(num_bytes: float) -> float:
    return num_bytes / 1e9


def redact_url(url: str) -> str:
    """scheme://host:port/path only - a webhook may carry a token in userinfo or query."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    if parts.port:
        host += ":%d" % parts.port
    return urllib.parse.urlunsplit((parts.scheme, host, parts.path, "", ""))


def format_progress(label: str, done: int, total: Optional[int]) -> str:
    if not total:
        return "%s: %.2f GB" % (label, gb(done))
    return "%s: %5.1f%% (%.2f / %.2f GB)" % (
        label,
        100.0 * done / total,
        gb(done),
        gb(total),
    )


class Progress:
    """Carriage-return updates on a TTY; one line per 5% otherwise (keeps CI logs short)."""

    def __init__(
        self, label: str, total: Optional[int], stream=None, now=time.monotonic
    ):
        self.label = label
        self.total = total
        self.stream = stream if stream is not None else sys.stdout
        self.is_tty = bool(getattr(self.stream, "isatty", lambda: False)())
        self._now = now
        self._last_time = float("-inf")
        self._last_bucket = -1
        self._dirty = False

    def update(self, done: int) -> None:
        finished = bool(self.total) and done >= self.total
        if self.is_tty:
            t = self._now()
            if t - self._last_time < 0.25 and not finished:
                return
            self._last_time = t
            self.stream.write("\r" + format_progress(self.label, done, self.total))
            self._dirty = True
        else:
            bucket = int(100.0 * done / self.total // 5) if self.total else done >> 30
            if bucket == self._last_bucket:
                return
            self._last_bucket = bucket
            self.stream.write(format_progress(self.label, done, self.total) + "\n")
        self.stream.flush()

    def finish(self) -> None:
        if self._dirty:
            self.stream.write("\n")
            self.stream.flush()
            self._dirty = False


# --------------------------------------------------------------------------- platform


def exe_name(base: str, system: str) -> str:
    return base + ".exe" if system == "Windows" else base


def host_os_name(system: str) -> str:
    """The host-os value used by Android repository XMLs."""
    return {"Linux": "linux", "Darwin": "macosx", "Windows": "windows"}.get(system, "")


def normalize_abi(text: str) -> str:
    aliases = {
        "arm64-v8a": "arm64-v8a",
        "arm64": "arm64-v8a",
        "aarch64": "arm64-v8a",
        "x86_64": "x86_64",
        "x86-64": "x86_64",
        "amd64": "x86_64",
    }
    return aliases.get(text.strip().lower(), text.strip())


def select_host_abi(machine: str, translated: bool = False) -> str:
    """arm64/aarch64 -> arm64 image, otherwise x86_64.

    translated=True: an x86_64 Python running under Rosetta on an Apple-silicon Mac still
    needs the arm64 image (the emulator itself is arm64 there).
    """
    if translated or (machine or "").lower() in ("arm64", "aarch64"):
        return "arm64-v8a"
    return "x86_64"


def is_rosetta_translated(system: str) -> bool:
    if system != "Darwin":
        return False
    try:
        proc = subprocess.run(
            ["sysctl", "-in", "sysctl.proc_translated"],
            capture_output=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return False  # no sysctl: behave exactly like platform.machine()
    return proc.stdout.decode("ascii", "replace").strip() == "1"


def default_cache_dir(env, system: str, home: str) -> str:
    if system == "Windows":
        base = env.get("LOCALAPPDATA") or os.path.join(home, "AppData", "Local")
        return os.path.join(base, "autobrief-pleos")
    return os.path.join(home, ".cache", "autobrief-pleos")


def need_free_gb(system: str) -> int:
    return NEED_FREE_GB.get(system, NEED_FREE_GB_DEFAULT)


def free_gb(path: str) -> float:
    probe = os.path.abspath(path)
    while probe and not os.path.exists(probe):
        parent = os.path.dirname(probe)
        if parent == probe:
            break
        probe = parent
    return gb(shutil.disk_usage(probe).free)


# --------------------------------------------------------------------------- SDK + adb


@dataclass(frozen=True)
class SdkTools:
    sdk: str
    emulator: Optional[str]
    adb: str


def sdk_candidates(env, system: str, home: str) -> List[str]:
    found = [env[k] for k in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if env.get(k)]
    if system == "Darwin":
        found.append(os.path.join(home, "Library", "Android", "sdk"))
    elif system == "Windows":
        base = env.get("LOCALAPPDATA") or os.path.join(home, "AppData", "Local")
        found.append(os.path.join(base, "Android", "Sdk"))
    else:
        found.append(os.path.join(home, "Android", "Sdk"))
    return list(dict.fromkeys(found))


def tools_missing_message(checked: Sequence[Tuple[str, str]]) -> str:
    lines = ["Android SDK 또는 필요한 도구를 찾지 못했습니다.", "확인한 위치:"]
    lines += ["  - %s (%s)" % item for item in checked]
    lines += [
        "해결 방법:",
        "  1) Android Studio를 설치하고 한 번 실행합니다.",
        "  2) Android Studio > SDK Manager > SDK Tools 탭에서",
        "     'Android Emulator'와 'Android SDK Platform-Tools'를 체크하고 Apply 합니다.",
        "  3) SDK가 기본 위치가 아니면 환경 변수 ANDROID_HOME 에 SDK 폴더를 지정하고 다시 실행합니다.",
    ]
    return "\n".join(lines)


def find_sdk_tools(
    env=None, system: str = None, home: str = None, need_emulator: bool = True
) -> SdkTools:
    env = os.environ if env is None else env
    system = system or platform.system()
    home = home or os.path.expanduser("~")
    checked = []
    for sdk in sdk_candidates(env, system, home):
        if not os.path.isdir(sdk):
            checked.append((sdk, "폴더 없음"))
            continue
        adb = os.path.join(sdk, "platform-tools", exe_name("adb", system))
        emu = os.path.join(sdk, "emulator", exe_name("emulator", system))
        missing = []
        if not os.path.isfile(adb):
            missing.append("platform-tools/adb")
        if need_emulator and not os.path.isfile(emu):
            missing.append("emulator")
        if not missing:
            return SdkTools(sdk, emu if os.path.isfile(emu) else None, adb)
        checked.append((sdk, "없는 도구: " + ", ".join(missing)))
    raise SetupError(EXIT_TOOLS, tools_missing_message(checked))


def avd_home_dir(env, home: str) -> str:
    if env.get("ANDROID_AVD_HOME"):
        return env["ANDROID_AVD_HOME"]
    if env.get("ANDROID_USER_HOME"):
        return os.path.join(env["ANDROID_USER_HOME"], "avd")
    return os.path.join(home, ".android", "avd")


class AdbError(Exception):
    pass


class AdbTimeout(AdbError):
    pass


def decode(data: bytes) -> str:
    return (data or b"").decode("utf-8", "replace")


def device_shell_command(args: Sequence[str]) -> str:
    """One string for `adb shell`: adb joins argv with spaces and the device sh re-parses it."""
    return " ".join(shlex.quote(a) for a in args)


def parse_adb_devices(text: str) -> List[Tuple[str, str]]:
    rows = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def parse_emu_avd_name(text: str) -> Optional[str]:
    """`adb emu avd name` prints the name, then OK (or KO: reason)."""
    for raw in text.splitlines():
        line = raw.strip()
        if line and line != "OK" and not line.startswith("KO"):
            return line
    return None


def nmea_checksum(body: str) -> str:
    """XOR of the characters between '$' and '*', as two upper-case hex digits."""
    value = 0
    for char in body:
        value ^= ord(char)
    return "%02X" % value


def nmea_degrees(value: float, degree_digits: int) -> str:
    """Decimal degrees -> NMEA ddmm.mmmm (latitude, 2 degree digits) or dddmm.mmmm (longitude)."""
    value = abs(value)
    degrees = int(value)
    minutes = round((value - degrees) * 60.0, 4)
    if minutes >= 60.0:
        degrees, minutes = degrees + 1, 0.0
    return "%0*d%07.4f" % (degree_digits, degrees, minutes)


def nmea_fix_sentences(
    lat: float,
    lon: float,
    utc: datetime.datetime,
    speed_kph: float = 0.0,
    course_deg: float = 0.0,
) -> List[str]:
    """$GPGGA + $GPRMC for one fix, laid out like the emulator's own `geo fix` sentences.

    `adb emu geo fix` ends every sentence with a constant `*47` instead of the checksum. The
    platform GPS provider ignores it, but the Pleos navigation app checks it and drops the
    sentence ("NMEA checksum mismatch: expected=47, actual=7B"), so NaviHelper never moves. These
    carry the real checksum."""
    hhmmss = utc.strftime("%H%M%S")
    lat_text, north = nmea_degrees(lat, 2), "N" if lat >= 0 else "S"
    lon_text, east = nmea_degrees(lon, 3), "E" if lon >= 0 else "W"
    gga = "GPGGA,%s,%s,%s,%s,%s,1,6,,0.0,M,0.,M,,," % (
        hhmmss,
        lat_text,
        north,
        lon_text,
        east,
    )
    rmc = "GPRMC,%s,A,%s,%s,%s,%s,%.2f,%.2f,%s,0.0,W" % (
        hhmmss,
        lat_text,
        north,
        lon_text,
        east,
        speed_kph / 1.852,  # knots
        course_deg % 360.0,
        utc.strftime("%d%m%y"),
    )
    return ["$%s*%s" % (body, nmea_checksum(body)) for body in (gga, rmc)]


def parse_current_user(text: str) -> Optional[int]:
    """`am get-current-user` prints the foreground user id alone (10 on Pleos Connect)."""
    found = re.fullmatch(r"\s*(\d+)\s*", text)
    return int(found.group(1)) if found else None


_USER_STATES = (
    "BOOTING",
    "RUNNING_LOCKED",
    "RUNNING_UNLOCKING",
    "RUNNING_UNLOCKED",
    "STOPPING",
    "SHUTDOWN",
)


def parse_user_state(text: str) -> Optional[str]:
    """`am get-started-user-state <id>` -> 'RUNNING_UNLOCKED' etc.

    The command prints `[UserState: id=10, state=RUNNING_UNLOCKED, lastState=..., ...]`, or
    `User is not started: <id>` (None)."""
    found = re.search(r"\bstate=([A-Z_]+)", text)
    if found:
        return found.group(1)
    found = re.search(r"\b(%s)\b" % "|".join(_USER_STATES), text)
    return found.group(1) if found else None


@dataclass(frozen=True)
class FrontActivity:
    """The activity in front of the screen, as `dumpsys activity activities` names it."""

    user: int
    component: str  # package/activity, e.g. app.autobrief.car/.ui.MainActivity

    @property
    def package(self) -> str:
        return self.component.split("/", 1)[0]

    def __str__(self) -> str:
        return "u%d %s" % (self.user, self.component)


_RECORD = r"ActivityRecord\{\S+\s+u(\d+)\s+([\w.$]+/[\w.$]+)"
# topResumedActivity= / mTopResumedActivity= / mResumedActivity: / ResumedActivity: / Resumed:
# (the field names differ between Android versions); mLastResumedActivity is history, not state
_RESUMED_RECORD = re.compile(
    r"\b(\w*?)resumed(?:activity)?\s*[:=]\s*" + _RECORD, re.IGNORECASE
)


def resumed_records(dumpsys: str) -> List[Tuple[bool, FrontActivity]]:
    """(is_top, activity) for every resumed activity `dumpsys activity activities` lists, in order."""
    records = []
    for prefix, user, component in _RESUMED_RECORD.findall(dumpsys):
        if "last" in prefix.lower():
            continue
        records.append(("top" in prefix.lower(), FrontActivity(int(user), component)))
    return records


def parse_front_activity(dumpsys: str) -> Optional[FrontActivity]:
    """The first top resumed activity (display 0 is listed first), else the first resumed one.

    None while nothing is resumed: the lock screen is up or a user switch is in progress."""
    records = resumed_records(dumpsys)
    tops = [activity for is_top, activity in records if is_top]
    if tops:
        return tops[0]
    return records[0][1] if records else None


def app_resumed(dumpsys: str, user: int) -> Optional[FrontActivity]:
    """Our activity when it is resumed (on screen) in `user`, top or not: the Pleos home can show
    an app in a panel next to its own, and a resumed activity there is visible all the same."""
    for _, activity in resumed_records(dumpsys):
        if activity.user == user and activity.package == APP_PACKAGE:
            return activity
    return None


def resumed_lines(dumpsys: str) -> List[str]:
    """The lines of `dumpsys activity activities` that say what is resumed (for the preflight log)."""
    return [
        line.strip()
        for line in dumpsys.splitlines()
        if _RESUMED_RECORD.search(line) or "mFocusedApp" in line
    ]


class Adb:
    """Thin adb wrapper. Output is always decoded as UTF-8 (Windows locale codecs mangle Hangul)."""

    def __init__(self, path: str, serial: Optional[str] = None, timeout: float = 60.0):
        self.path = path
        self.serial = serial
        self.timeout = timeout

    def run(
        self, *args: str, timeout: float = None, check: bool = True, device: bool = True
    ):
        cmd = [self.path]
        if device and self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        try:
            proc = subprocess.run(
                cmd,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout or self.timeout,
            )
        except subprocess.TimeoutExpired:
            raise AdbTimeout("adb %s: 시간 초과" % " ".join(args[:2]))
        except OSError as exc:
            raise AdbError("adb를 실행할 수 없습니다 (%s): %s" % (self.path, exc))
        if check and proc.returncode != 0:
            detail = decode(proc.stderr or proc.stdout).strip()[:500]
            raise AdbError(
                "adb %s 실패 (rc=%d): %s"
                % (" ".join(args[:2]), proc.returncode, detail)
            )
        return proc.returncode, proc.stdout, proc.stderr

    def out(self, *args: str, timeout: float = None) -> str:
        return decode(self.run(*args, timeout=timeout)[1])

    def shell(self, *args: str, timeout: float = None) -> str:
        return self.out("shell", device_shell_command(args), timeout=timeout)

    def devices(self) -> List[Tuple[str, str]]:
        return parse_adb_devices(decode(self.run("devices", device=False)[1]))

    def wait_for_device(self, timeout: float) -> None:
        self.run("wait-for-device", timeout=timeout)

    def getprop(self, name: str) -> str:
        return self.shell("getprop", name).strip()

    def avd_name(self) -> Optional[str]:
        return parse_emu_avd_name(self.out("emu", "avd", "name"))

    def forward(self, host_port: int, guest_port: int) -> None:
        self.run("forward", "tcp:%d" % host_port, "tcp:%d" % guest_port)

    def install(self, apk: str) -> str:
        return self.out("install", "-r", "-g", apk, timeout=300)

    def logcat_clear(self, tries: int = LOGCAT_CLEAR_TRIES, sleep: Optional[Callable[[float], None]] = None) -> None:
        """`logcat -c`. logd refuses to clear while a reader (the CI job's logcat captures, a dump in progress) still
        holds entries it has not read: "failed to clear the 'main' log" (CI run 40452018157, right after the app's
        vehicle link). The readers catch up within seconds, so that refusal is retried; any other failure, or the same
        refusal `tries` times, raises."""
        sleep = sleep or time.sleep
        for attempt in range(1, tries + 1):
            rc, out, err = self.run("logcat", "-c", check=False)
            if rc == 0:
                return
            detail = decode(err or out).strip()
            if "failed to clear" not in detail or attempt == tries:
                raise AdbError("adb logcat -c 실패 (rc=%d, %d번 시도): %s" % (rc, attempt, detail[:500]))
            sleep(LOGCAT_CLEAR_RETRY_S)

    def logcat_buffer_size(self, size: str) -> None:
        self.run("logcat", "-G", size)

    def logcat_dump(self, tag: str = LOG_TAG, level: str = "I", fmt: Optional[str] = None) -> str:
        """`fmt` = a logcat -v format (e.g. "epoch": seconds since 1970 on the device clock instead of its local time)."""
        return self.out("logcat", "-d", *(("-v", fmt) if fmt else ()), "-s", "%s:%s" % (tag, level))

    def current_user(self) -> Optional[int]:
        return parse_current_user(self.shell("am", "get-current-user"))

    def user_state(self, user: int) -> Optional[str]:
        return parse_user_state(self.shell("am", "get-started-user-state", str(user)))

    def activities(self) -> str:
        return self.shell("dumpsys", "activity", "activities")

    def geo_fix(self, lon: float, lat: float) -> None:
        reply = self.out("emu", "geo", "fix", "%.6f" % lon, "%.6f" % lat)
        if "KO" in reply:
            raise AdbError("geo fix 거부됨: " + reply.strip())

    def geo_nmea(self, sentence: str) -> None:
        reply = self.out("emu", "geo", "nmea", sentence)
        if "KO" in reply:
            raise AdbError("geo nmea 거부됨: " + reply.strip())

    def screencap_png(self) -> bytes:
        raw = self.run("exec-out", "screencap")[1]
        png = raw_screencap_to_png(raw)
        if png is not None:
            return png
        return self.run("exec-out", "screencap", "-p")[1]


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def raw_screencap_to_png(data: bytes) -> Optional[bytes]:
    """Encode a raw `screencap` RGBA_8888 buffer (12- or 16-byte header) into a PNG on the host.

    On the software-rendered CI emulator, `screencap -p` compresses 16.4 MB of RGBA pixels inside
    the guest vCPU (~7 s per screenshot, starving the app and VehicleApiDdsEmu); encoding the raw
    buffer on the host takes ~70 ms and saves ~95 s across the 15-screenshot director run.
    """
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return data
    if len(data) < 12:
        return None
    w, h, fmt = struct.unpack_from("<III", data, 0)
    if fmt != 1 or not (0 < w <= 8192 and 0 < h <= 8192):
        return None
    need = w * h * 4
    for header in (16, 12):
        if len(data) == header + need:
            stride = w * 4
            mv = memoryview(data)[header:]
            rows = bytearray(h * (stride + 1))
            for y in range(h):
                dst = y * (stride + 1)
                src = y * stride
                rows[dst + 1 : dst + 1 + stride] = mv[src : src + stride]
            ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
            return (
                b"\x89PNG\r\n\x1a\n"
                + _png_chunk(b"IHDR", ihdr)
                + _png_chunk(b"IDAT", zlib.compress(rows, 6))
                + _png_chunk(b"IEND", b"")
            )
    return None


# --------------------------------------------------------------------------- local console HTTP


class ConsoleError(Exception):
    """A console request failed. `status` is the HTTP status (None: no HTTP answer at all);
    `dropped`: the console accepted the connection and closed it without an answer."""

    def __init__(
        self,
        message: str,
        status: Optional[int] = None,
        body: str = "",
        dropped: bool = False,
    ):
        super().__init__(message)
        self.status = status
        self.body = body
        self.dropped = dropped


def connection_dropped(exc: BaseException) -> bool:
    """RemoteDisconnected / reset after connecting (seen on CI in 3 of 4 runs, during writes and
    heartbeats alike) as opposed to a console that is not there (refused, timeout)."""
    reason = getattr(exc, "reason", None)  # urllib wraps some socket errors in URLError
    for err in (exc, reason):
        if isinstance(err, (http.client.RemoteDisconnected, ConnectionResetError)):
            return True
    return False


def _local_opener():
    # the Virtual Console is on loopback: never route it through a corporate http_proxy
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def console_url() -> str:
    return "http://127.0.0.1:%d" % CONSOLE_HOST_PORT


def console_request(method: str, url: str, timeout: float = 10.0) -> str:
    """Empty-body request to the Vehicle Virtual Console; any non-2xx raises with the response text."""
    body = b"" if method in ("PUT", "POST") else None
    req = urllib.request.Request(url, data=body, method=method)
    try:
        with _local_opener().open(req, timeout=timeout) as resp:
            status, text = resp.getcode(), decode(resp.read())
    except urllib.error.HTTPError as exc:
        detail = decode(exc.read(500)).strip() if exc.fp is not None else ""
        raise ConsoleError(
            "%s %s -> HTTP %d %s" % (method, url, exc.code, detail),
            status=exc.code,
            body=detail,
        )
    except (OSError, http.client.HTTPException) as exc:
        raise ConsoleError(
            "%s %s -> %s" % (method, url, exc), dropped=connection_dropped(exc)
        )
    if not 200 <= status < 300:
        raise ConsoleError(
            "%s %s -> HTTP %d %s" % (method, url, status, text.strip()[:500]),
            status=status,
            body=text.strip()[:500],
        )
    return text


# --------------------------------------------------------------------------- image repository


@dataclass(frozen=True)
class RepoImage:
    path: str
    display_name: str
    api_level: Optional[int]
    abi: str
    tag_id: str
    vendor_id: str
    license_id: str
    url: str
    size: Optional[int]
    checksum: str
    checksum_type: str
    host_os: str


def _local(tag) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def _first(element, name: str):
    if element is None:
        return None
    for node in element.iter():
        if _local(node.tag) == name:
            return node
    return None


def _all(element, name: str):
    return [n for n in element.iter() if _local(n.tag) == name]


def _text(element) -> str:
    return (element.text or "").strip() if element is not None else ""


def checksum_algorithm(declared: str, value: str) -> str:
    """sha1/sha256 from the `type` attribute, else from the hex length (40 / 64)."""
    key = declared.strip().lower().replace("-", "")
    if key in ("sha1", "sha256"):
        return key
    return {40: "sha1", 64: "sha256"}.get(len(value.strip()), declared or "unknown")


def parse_repo_xml(xml, base_url: str) -> List[RepoImage]:
    """Namespace-agnostic parse of an Android system-image repository XML.

    Handles both `<url>` (repository2, measured) and `<sdk:url>` (prefixed) element names;
    a relative archive URL is resolved against base_url.

    stdlib ElementTree has no XXE / entity-expansion guard and defusedxml is not available
    (stdlib-only constraint), so DTD and ENTITY declarations are rejected up front; the
    measured vendor XMLs contain neither.
    """
    raw = xml if isinstance(xml, bytes) else xml.encode("utf-8")
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise SetupError(
            EXIT_DOWNLOAD, "이미지 목록(XML)에 DTD/ENTITY 선언이 있어 거부합니다."
        )
    root = ET.fromstring(xml)
    packages = [
        n for n in root.iter() if _local(n.tag) in ("remotePackage", "system-image")
    ]
    images = []
    for pkg in packages or [root]:
        details = _first(pkg, "type-details")
        if details is None:
            details = pkg
        api_text = _text(_first(details, "api-level"))
        tag_id = _text(_first(_first(details, "tag"), "id"))
        vendor_id = _text(_first(_first(details, "vendor"), "id"))
        license_ref = _first(pkg, "uses-license")
        for archive in _all(pkg, "archive"):
            complete = _first(archive, "complete")
            if complete is None:
                complete = archive
            url = _text(_first(complete, "url"))
            if not url:
                continue
            checksum_el = _first(complete, "checksum")
            checksum = _text(checksum_el)
            size_text = _text(_first(complete, "size"))
            images.append(
                RepoImage(
                    path=pkg.get("path", ""),
                    display_name=_text(_first(pkg, "display-name")),
                    api_level=int(api_text) if api_text.isdigit() else None,
                    abi=normalize_abi(_text(_first(details, "abi"))),
                    tag_id=tag_id,
                    vendor_id=vendor_id,
                    license_id=license_ref.get("ref", "")
                    if license_ref is not None
                    else "",
                    url=urllib.parse.urljoin(base_url, url),
                    size=int(size_text) if size_text.isdigit() else None,
                    checksum=checksum,
                    checksum_type=checksum_algorithm(
                        checksum_el.get("type", "") if checksum_el is not None else "",
                        checksum,
                    ),
                    host_os=_text(_first(archive, "host-os")) or archive.get("os", ""),
                )
            )
    return images


def describe_image(image: RepoImage) -> str:
    size = "%.2f GB" % gb(image.size) if image.size else "크기 모름"
    return "%s (api %s, abi %s, %s, %s=%s)" % (
        image.display_name or image.path or "이름 없음",
        image.api_level,
        image.abi,
        size,
        image.checksum_type,
        image.checksum,
    )


def pick_image(images: Sequence[RepoImage], abi: str, host_os: str = "") -> RepoImage:
    """The archive for this abi + API 34 (and this host OS when the XML restricts it)."""
    wanted = [
        i
        for i in images
        if i.abi == abi
        and i.api_level in (None, API_LEVEL)
        and (not host_os or not i.host_os or i.host_os == host_os)
    ]
    if not wanted:
        found = "\n".join("  - " + describe_image(i) for i in images) or "  (없음)"
        raise SetupError(
            EXIT_DOWNLOAD,
            "이미지 목록에서 abi=%s, API %d 항목을 찾지 못했습니다. 찾은 항목:\n%s"
            % (abi, API_LEVEL, found),
        )
    return wanted[0]


# --------------------------------------------------------------------------- download


class DownloadError(Exception):
    pass


def network_hint(exc: BaseException) -> str:
    reason = getattr(exc, "reason", exc)
    if isinstance(reason, ssl.SSLCertVerificationError):
        return (
            " (인증서 확인 실패: macOS python.org 파이썬이면 '/Applications/Python 3.x/"
            "Install Certificates.command'를 실행하거나 회사 프록시 인증서를 확인하세요)"
        )
    return ""


def fetch_bytes(
    url: str, opener=None, timeout: float = 30.0, max_bytes: int = MAX_XML_BYTES
) -> bytes:
    opener = opener or urllib.request.build_opener()
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with opener.open(req, timeout=timeout) as resp:
            data = resp.read(max_bytes + 1)
    except urllib.error.HTTPError as exc:
        raise SetupError(
            EXIT_DOWNLOAD, "%s 를 받지 못했습니다: HTTP %d" % (url, exc.code)
        )
    except (OSError, http.client.HTTPException) as exc:
        raise SetupError(
            EXIT_DOWNLOAD, "%s 를 받지 못했습니다: %s%s" % (url, exc, network_hint(exc))
        )
    if len(data) > max_bytes:
        raise SetupError(
            EXIT_DOWNLOAD, "%s 가 비정상적으로 큽니다 (>%d bytes)" % (url, max_bytes)
        )
    return data


def parse_content_range(
    value: Optional[str],
) -> Optional[Tuple[int, int, Optional[int]]]:
    """`bytes 100-999/1000` -> (100, 999, 1000); total may be None for `*`."""
    match = re.match(r"^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$", value or "")
    if not match:
        return None
    total = None if match.group(3) == "*" else int(match.group(3))
    return int(match.group(1)), int(match.group(2)), total


def download_with_resume(
    url: str,
    part_path: str,
    expected_size: Optional[int],
    opener=None,
    attempts: int = 5,
    timeout: float = 60.0,
    sleep: Optional[Callable[[float], None]] = None,
    log: Callable[[str], None] = say,
) -> int:
    """Download url into part_path, resuming with `Range: bytes=N-` after any interruption.

    Retried (up to `attempts`): connection errors, timeouts, 5xx, and a stream that ends
    short of the expected size. Not retried: 4xx (except 416 = stale partial, which is
    discarded) and certificate errors. Returns the final file size.
    """
    opener = opener or urllib.request.build_opener()
    sleep = sleep or time.sleep
    os.makedirs(os.path.dirname(os.path.abspath(part_path)), exist_ok=True)
    total = expected_size
    problem = "알 수 없음"
    for attempt in range(1, attempts + 1):
        have = os.path.getsize(part_path) if os.path.exists(part_path) else 0
        if total is not None and have > total:
            os.remove(part_path)
            have = 0
        if total is not None and have == total:
            return have
        headers = {"User-Agent": USER_AGENT}
        if have:
            headers["Range"] = "bytes=%d-" % have
        progress = None
        try:
            with opener.open(
                urllib.request.Request(url, headers=headers), timeout=timeout
            ) as resp:
                status = resp.getcode()
                mode = "ab"
                if status == 206:
                    span = parse_content_range(resp.headers.get("Content-Range"))
                    if span is None or span[0] != have:
                        raise DownloadError(
                            "서버가 요청과 다른 구간을 보냈습니다: %s"
                            % resp.headers.get("Content-Range")
                        )
                    total = total if total is not None else span[2]
                else:
                    if have:
                        log("  서버가 이어받기를 지원하지 않아 처음부터 받습니다.")
                    mode, have = "wb", 0
                    length = resp.headers.get("Content-Length")
                    if total is None and length:
                        total = int(length)
                progress = Progress("  내려받기", total)
                with open(part_path, mode) as out:
                    while True:
                        chunk = resp.read(BLOCK)
                        if not chunk:
                            break
                        out.write(chunk)
                        have += len(chunk)
                        progress.update(have)
        except urllib.error.HTTPError as exc:
            if exc.code == 416:
                log("  이어받기 구간이 맞지 않아 부분 파일을 버리고 다시 받습니다.")
                if os.path.exists(part_path):
                    os.remove(part_path)
                problem = "HTTP 416"
            elif exc.code < 500:
                raise DownloadError("HTTP %d: %s" % (exc.code, url))
            else:
                problem = "HTTP %d" % exc.code
        except (OSError, http.client.HTTPException) as exc:
            if isinstance(getattr(exc, "reason", None), ssl.SSLCertVerificationError):
                raise DownloadError(str(exc) + network_hint(exc))
            problem = str(exc)
        else:
            if total is None or os.path.getsize(part_path) == total:
                return os.path.getsize(part_path)
            problem = "받은 크기 %d / %d" % (os.path.getsize(part_path), total)
        finally:
            if progress is not None:
                progress.finish()
        if attempt < attempts:
            log(
                "  내려받기가 끊겼습니다 (%s). %d/%d번째 재시도로 이어받습니다."
                % (problem, attempt, attempts - 1)
            )
            sleep(min(2.0 * attempt, 15.0))
    raise DownloadError(
        "내려받기를 %d번 시도했지만 끝내지 못했습니다 (%s)" % (attempts, problem)
    )


def file_digest(path: str, algorithm: str, label: str = "") -> str:
    digest = hashlib.new(algorithm)
    total = os.path.getsize(path)
    progress = Progress(label, total) if label else None
    done = 0
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(BLOCK), b""):
            digest.update(chunk)
            done += len(chunk)
            if progress:
                progress.update(done)
    if progress:
        progress.finish()
    return digest.hexdigest()


def archive_filename(url: str) -> str:
    name = os.path.basename(urllib.parse.urlsplit(url).path)
    if not name:
        raise SetupError(EXIT_DOWNLOAD, "URL에서 파일 이름을 알 수 없습니다: " + url)
    return name


def ensure_archive(
    image: RepoImage,
    cache_dir: str,
    opener=None,
    sleep: Optional[Callable[[float], None]] = None,
    log: Callable[[str], None] = say,
) -> str:
    """Download (resumable) + checksum-verify the image zip; one delete-and-retry on mismatch."""
    if image.checksum_type not in ("sha1", "sha256") or not image.checksum:
        raise SetupError(
            EXIT_DOWNLOAD,
            "이미지 목록에 쓸 수 있는 체크섬(sha1/sha256)이 없어 검증할 수 없습니다: %s %s"
            % (image.checksum_type, image.checksum or "(비어 있음)"),
        )
    os.makedirs(cache_dir, exist_ok=True)
    final = os.path.join(cache_dir, archive_filename(image.url))
    part = final + ".part"
    for attempt in (1, 2):
        cached = os.path.isfile(final)
        if cached and image.size is not None and os.path.getsize(final) != image.size:
            log("  캐시 파일 크기가 달라 다시 받습니다.")
            os.remove(final)
            cached = False
        if cached:
            log("  캐시에 파일이 있습니다: %s" % final)
        else:
            try:
                download_with_resume(
                    image.url, part, image.size, opener=opener, sleep=sleep, log=log
                )
            except DownloadError as exc:
                raise SetupError(
                    EXIT_DOWNLOAD, "이미지를 내려받지 못했습니다: %s" % exc
                )
            os.replace(part, final)
        log("  체크섬(%s) 확인 중..." % image.checksum_type)
        actual = file_digest(final, image.checksum_type, "  체크섬")
        if actual.lower() == image.checksum.lower():
            return final
        log("  체크섬이 맞지 않습니다 (기대 %s, 실제 %s)." % (image.checksum, actual))
        os.remove(final)
        if attempt == 1:
            log("  파일을 지우고 한 번 더 받습니다.")
    raise SetupError(
        EXIT_DOWNLOAD,
        "내려받은 이미지의 체크섬이 두 번 연속 맞지 않습니다. 네트워크(프록시)를 확인하고 다시 실행하세요.",
    )


# --------------------------------------------------------------------------- extraction


def parse_properties(text: str) -> Dict[str, str]:
    props = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def read_properties(path: str) -> Dict[str, str]:
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8", errors="replace") as handle:
        return parse_properties(handle.read())


def image_defaults_from_properties(props: Dict[str, str]) -> Dict[str, str]:
    """`avd_config_ini.hw.foo=x` in source.properties -> {"hw.foo": "x"} (what avdmanager copies)."""
    prefix = "avd_config_ini."
    return {k[len(prefix) :]: v for k, v in props.items() if k.startswith(prefix)}


def is_ignored_member(name: str) -> bool:
    return name.split("/", 1)[0] == "__MACOSX" or name.endswith(".DS_Store")


def top_level_folder(names: Sequence[str]) -> Optional[str]:
    """The single top-level folder every entry lives under, or None (nothing to strip)."""
    names = [n for n in names if n and not is_ignored_member(n)]
    if not names:
        return None
    first = names[0].split("/", 1)[0]
    return first if all(n.startswith(first + "/") for n in names) else None


def safe_join(root: str, relative: str) -> str:
    root_norm = os.path.normpath(root)
    target = os.path.normpath(os.path.join(root_norm, *relative.split("/")))
    try:
        inside = os.path.commonpath([root_norm, target]) == root_norm
    except ValueError:  # different drives on Windows
        inside = False
    if not inside:
        raise SetupError(
            EXIT_DOWNLOAD, "압축 파일 안의 경로가 올바르지 않습니다: " + relative
        )
    return target


def is_zero_block(block: bytes, zeros: bytes) -> bool:
    return block == (zeros if len(block) == len(zeros) else bytes(len(block)))


def copy_sparse(src, dst, progress: Optional[Callable[[int], None]] = None) -> int:
    """Copy src to dst in 1 MiB blocks; all-zero blocks become holes (seek, no write).

    Returns the bytes actually written. The final truncate() fixes the length when the file
    ends in a hole. Where the filesystem has no sparse files (NTFS default) the result is the
    full size - slower and larger, but identical content.
    """
    zeros = bytes(BLOCK)
    position = written = 0
    while True:
        block = src.read(BLOCK)
        if not block:
            break
        if is_zero_block(block, zeros):
            dst.seek(len(block), os.SEEK_CUR)
        else:
            dst.write(block)
            written += len(block)
        position += len(block)
        if progress:
            progress(position)
    dst.truncate(position)
    return written


@dataclass
class ExtractStats:
    files: int = 0
    total_bytes: int = 0
    written_bytes: int = 0


def extract_zip_sparse(
    zip_path: str, dest_dir: str, log: Callable[[str], None] = say
) -> ExtractStats:
    """Extract zip_path into dest_dir, stripping the single top-level folder, sparse-aware."""
    stats = ExtractStats()
    with zipfile.ZipFile(zip_path) as archive:
        members = [m for m in archive.infolist() if not is_ignored_member(m.filename)]
        top = top_level_folder([m.filename for m in members])
        prefix = top + "/" if top else ""
        stats.total_bytes = sum(m.file_size for m in members if not m.is_dir())
        progress = Progress("  압축 풀기", stats.total_bytes)
        done_before = 0
        os.makedirs(dest_dir, exist_ok=True)
        for member in members:
            relative = member.filename[len(prefix) :]
            if not relative:
                continue
            target = safe_join(dest_dir, relative)
            if member.is_dir():
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            base = done_before
            with archive.open(member) as src, open(target, "wb") as dst:
                stats.written_bytes += copy_sparse(
                    src, dst, lambda pos, base=base: progress.update(base + pos)
                )
            done_before += member.file_size
            stats.files += 1
        progress.update(stats.total_bytes)
        progress.finish()
    return stats


def zip_properties(zip_path: str) -> Tuple[Dict[str, str], Dict[str, int]]:
    """(source.properties of the archive, {relative path: size} of every file in it)."""
    with zipfile.ZipFile(zip_path) as archive:
        members = [m for m in archive.infolist() if not is_ignored_member(m.filename)]
        top = top_level_folder([m.filename for m in members])
        prefix = top + "/" if top else ""
        sizes = {
            m.filename[len(prefix) :]: m.file_size for m in members if not m.is_dir()
        }
        props = {}
        if "source.properties" in sizes:
            props = parse_properties(decode(archive.read(prefix + "source.properties")))
    return props, sizes


def image_install_dir(sdk: str, abi: str) -> str:
    return os.path.join(sdk, *IMAGE_SUBDIRS, abi)


def image_sysdir(abi: str, subdirs=IMAGE_SUBDIRS) -> str:
    """image.sysdir.1 value: relative to the SDK root, forward slashes, trailing slash."""
    return "/".join(tuple(subdirs) + (abi,)) + "/"


def official_image_dir(sdk: str, abi: str) -> str:
    return os.path.join(sdk, *OFFICIAL_IMAGE_SUBDIRS, abi)


def image_is_current(install_dir: str, zip_path: str) -> bool:
    """Already extracted from this archive: source.properties equal and every file size equal."""
    props, sizes = zip_properties(zip_path)
    installed = read_properties(os.path.join(install_dir, "source.properties"))
    if not props or installed != props:
        return False
    for relative, size in sizes.items():
        path = os.path.join(install_dir, *relative.split("/"))
        if not os.path.isfile(path) or os.path.getsize(path) != size:
            return False
    return True


def install_image(
    zip_path: str, install_dir: str, abi: str, log: Callable[[str], None] = say
) -> bool:
    """Extract into <install_dir>.extracting, then rename: install_dir is never half-written.

    Returns False (and does nothing) when the installed copy is already current.
    """
    if image_is_current(install_dir, zip_path):
        log(
            "  이미 설치돼 있습니다 (source.properties와 파일 크기가 같음): %s"
            % install_dir
        )
        return False
    temp = install_dir + ".extracting"
    os.makedirs(os.path.dirname(install_dir), exist_ok=True)
    if os.path.isdir(temp):
        shutil.rmtree(temp)
    started = time.monotonic()
    try:
        stats = extract_zip_sparse(zip_path, temp, log)
        props = read_properties(os.path.join(temp, "source.properties"))
        if props.get("SystemImage.Abi") != abi or props.get(
            "AndroidVersion.ApiLevel"
        ) != str(API_LEVEL):
            raise SetupError(
                EXIT_DOWNLOAD,
                "압축을 푼 이미지가 기대와 다릅니다 (abi=%s, api=%s). 기대: abi=%s, api=%d"
                % (
                    props.get("SystemImage.Abi"),
                    props.get("AndroidVersion.ApiLevel"),
                    abi,
                    API_LEVEL,
                ),
            )
        if os.path.isdir(install_dir):
            shutil.rmtree(install_dir)
        os.rename(temp, install_dir)
    except PermissionError as exc:
        shutil.rmtree(temp, ignore_errors=True)
        raise SetupError(
            EXIT_DOWNLOAD,
            "SDK 폴더에 쓸 수 없습니다 (%s). 쓰기 권한을 확인하세요." % exc,
        )
    except BaseException:
        shutil.rmtree(
            temp, ignore_errors=True
        )  # never leave 10+ GB of half-extracted files
        raise
    log(
        "  파일 %d개, 실제 기록 %.2f GB / 이미지 크기 %.2f GB, %.0f초"
        % (
            stats.files,
            gb(stats.written_bytes),
            gb(stats.total_bytes),
            time.monotonic() - started,
        )
    )
    return True


def installed_image_problem(install_dir: str, abi: str) -> Optional[str]:
    props = read_properties(os.path.join(install_dir, "source.properties"))
    if not props:
        return "source.properties가 없습니다"
    if props.get("SystemImage.Abi") != abi or props.get(
        "AndroidVersion.ApiLevel"
    ) != str(API_LEVEL):
        return "abi/API가 다릅니다 (abi=%s, api=%s)" % (
            props.get("SystemImage.Abi"),
            props.get("AndroidVersion.ApiLevel"),
        )
    if not os.path.isfile(os.path.join(install_dir, "system.img")):
        return "system.img가 없습니다"
    return None


# --------------------------------------------------------------------------- AVD


def build_avd_config(
    abi: str,
    headless: bool,
    image_defaults: Optional[Dict[str, str]] = None,
    sysdir: Optional[str] = None,
) -> str:
    """Text of <avd>/config.ini.

    image_defaults are the image's own avd_config_ini.* recommendations; the settings below
    always win (RAM 4 GB per the official guide, cold boot, GPU mode, display).
    """
    width, height, density = AVD_LCD
    fixed = [
        ("AvdId", AVD_NAME),
        ("avd.ini.displayname", AVD_DISPLAY_NAME),
        ("avd.ini.encoding", "UTF-8"),
        ("PlayStore.enabled", "false"),
        ("abi.type", abi),
        ("hw.cpu.arch", "arm64" if abi == "arm64-v8a" else "x86_64"),
        ("hw.cpu.ncore", "4"),
        ("hw.ramSize", str(AVD_RAM_MB)),
        ("hw.lcd.width", str(width)),
        ("hw.lcd.height", str(height)),
        ("hw.lcd.density", str(density)),
        ("hw.gpu.enabled", "yes"),
        ("hw.gpu.mode", "swiftshader_indirect" if headless else "host"),
        ("hw.keyboard", "yes"),
        ("hw.audioInput", "yes"),
        ("image.sysdir.1", sysdir or image_sysdir(abi)),
        ("tag.id", "android-automotive"),
        ("tag.display", "Automotive"),
        ("disk.dataPartition.size", "6G"),
        ("fastboot.forceColdBoot", "yes"),
        ("fastboot.forceFastBoot", "no"),
    ]
    taken = {key for key, _ in fixed}
    extra = [
        (k, v) for k, v in sorted((image_defaults or {}).items()) if k not in taken
    ]
    return "".join("%s=%s\n" % item for item in fixed + extra)


def build_avd_ini(avd_dir: str) -> str:
    return "avd.ini.encoding=UTF-8\npath=%s\ntarget=android-%d\n" % (avd_dir, API_LEVEL)


def write_avd(
    avd_home: str,
    abi: str,
    headless: bool,
    image_defaults: Optional[Dict[str, str]],
    sysdir: Optional[str] = None,
) -> str:
    avd_dir = os.path.join(avd_home, AVD_NAME + ".avd")
    os.makedirs(avd_dir, exist_ok=True)
    ini_path = os.path.join(avd_home, AVD_NAME + ".ini")
    with open(ini_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(build_avd_ini(avd_dir))
    with open(
        os.path.join(avd_dir, "config.ini"), "w", encoding="utf-8", newline="\n"
    ) as handle:
        handle.write(build_avd_config(abi, headless, image_defaults, sysdir))
    return avd_dir


# --------------------------------------------------------------------------- emulator


def emulator_command(emulator: str, headless: bool) -> List[str]:
    cmd = [
        emulator,
        "-avd",
        AVD_NAME,
        "-no-snapshot",
        "-no-boot-anim",
        "-memory",
        str(AVD_RAM_MB),
    ]
    if headless:
        cmd += ["-no-window", "-no-audio", "-gpu", "swiftshader_indirect"]
    return cmd


def launch_emulator(
    cmd: Sequence[str], log_path: str, sdk: str, avd_home: str
) -> "subprocess.Popen":
    """Start the emulator detached (it must outlive this script); stdout+stderr -> log_path."""
    env = dict(os.environ)
    env["ANDROID_AVD_HOME"] = avd_home  # the emulator must read the AVD we just wrote
    env["ANDROID_SDK_ROOT"] = (
        sdk  # ...and resolve image.sysdir.1 inside the SDK we installed to
    )
    kwargs = {}
    if os.name == "nt":
        kwargs["creationflags"] = getattr(
            subprocess, "DETACHED_PROCESS", 0x8
        ) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x200)
    else:
        kwargs["start_new_session"] = True
    with open(log_path, "ab") as log_file:
        return subprocess.Popen(
            list(cmd),
            stdin=subprocess.DEVNULL,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            env=env,
            close_fds=True,
            **kwargs,
        )


def tail_text(path: str, lines: int = 15) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return "".join(handle.readlines()[-lines:])
    except OSError:
        return ""


def hint_for_emulator_log(text: str) -> Optional[str]:
    low = text.lower()
    if any(
        k in low
        for k in (
            "hardware acceleration",
            "/dev/kvm",
            "haxm",
            "whpx",
            "aehd",
            "hypervisor",
        )
    ):
        return (
            "하드웨어 가속(가상화)을 쓸 수 없는 것 같습니다. Windows: 'Windows 하이퍼바이저 플랫폼'을 켜고 "
            "BIOS의 가상화(VT-x/SVM)를 켜세요. Linux: /dev/kvm 권한을 확인하세요."
        )
    if any(k in low for k in ("no space left", "not enough space", "disk full")):
        return "디스크 공간이 부족합니다. 여유 공간을 확보한 뒤 다시 실행하세요."
    if any(k in low for k in ("emulated framebuffer", "emuglconfig", "backend egl")):
        return "그래픽 초기화에 실패했습니다. 그래픽 드라이버를 최신으로 올리거나 --headless 로 시도해 보세요."
    if "unknown avd name" in low or "no avd" in low:
        return "AVD를 찾지 못했습니다. ANDROID_AVD_HOME / ANDROID_USER_HOME 설정을 확인하세요."
    return None


def wait_for_boot(
    adb: Adb,
    timeout: float,
    proc=None,
    log_path: str = "",
    poll: float = 3.0,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
    log: Callable[[str], None] = say,
) -> None:
    """adb wait-for-device (in short slices, so a dead emulator is noticed), then sys.boot_completed==1."""
    now = now or time.monotonic
    sleep = sleep or time.sleep
    started = now()
    deadline = started + timeout

    def fail(reason: str) -> SetupError:
        tail = tail_text(log_path) if log_path else ""
        hint = hint_for_emulator_log(tail)
        text = reason
        if tail:
            text += "\n--- 에뮬레이터 로그 끝부분 (%s) ---\n%s" % (
                log_path,
                tail.rstrip(),
            )
        if hint:
            text += "\n힌트: " + hint
        return SetupError(EXIT_BOOT, text)

    def check_alive() -> None:
        if proc is not None and proc.poll() is not None:
            raise fail(
                "에뮬레이터가 부팅 전에 종료됐습니다 (종료 코드 %s)." % proc.returncode
            )

    online = False
    last_note = started
    while now() < deadline:
        check_alive()
        if not online:
            try:
                adb.wait_for_device(timeout=min(10.0, max(1.0, deadline - now())))
                online = True
            except AdbTimeout:
                pass
            except AdbError:
                sleep(poll)  # adb server still starting or device not registered yet
        if online:
            try:
                if adb.getprop("sys.boot_completed") == "1":
                    log("  부팅 완료 (%.0f초)" % (now() - started))
                    return
            except AdbError:
                online = False  # went offline while booting; wait for it again
            sleep(poll)
        if now() - last_note >= 30:
            last_note = now()
            log(
                "  부팅 대기 중... %.0f초 경과 (최대 %.0f초)"
                % (now() - started, timeout)
            )
    check_alive()
    raise fail("%.0f초 안에 부팅이 끝나지 않았습니다." % timeout)


def enlarge_logcat(adb: Adb, size: str = LOGCAT_BUFFER_SIZE) -> None:
    """A bigger logcat ring, so the setup and the first scenario are still there at the end."""
    try:
        adb.logcat_buffer_size(size)
    except AdbError as exc:
        # logd may refuse the size on a locked-down image; the run works with the default ring
        say("  (경고) logcat 버퍼를 %s로 늘리지 못했습니다: %s" % (size, exc))
    else:
        say("  logcat 버퍼: %s" % size)


def wait_for_console(
    timeout: float,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> None:
    now = now or time.monotonic
    sleep = sleep or time.sleep
    deadline = now() + timeout
    last_error = ""
    while True:
        try:
            console_request("GET", console_url() + "/vehicle/state", timeout=5.0)
            return
        except ConsoleError as exc:
            last_error = str(exc)
        if now() >= deadline:
            raise SetupError(
                EXIT_CONSOLE,
                "가상 콘솔(%s)이 %.0f초 안에 응답하지 않았습니다.\n마지막 오류: %s"
                % (console_url(), timeout, last_error),
            )
        sleep(3.0)


# --------------------------------------------------------------------------- app


def demo_broadcast_args(
    command: str, webhook: Optional[str] = None, user: Optional[int] = None
) -> List[str]:
    """`am broadcast` for the debug DemoReceiver. Without --user, am delivers to every started user
    whose copy of the app is not stopped: on a headless-system-user image that can be user 0's copy,
    whose data the driver's screen never shows."""
    args = ["am", "broadcast"]
    if user is not None:
        args += ["--user", str(user)]
    args += [
        "-a",
        DEMO_ACTION,
        "-p",
        APP_PACKAGE,
        "--es",
        "cmd",
        command,
    ]
    if webhook:
        args += ["--es", "webhook", webhook]
    return args


def find_demo_ready(text: str) -> Optional[int]:
    """trips=<n> of the last `DEMO_READY trips=<n>` line, or None."""
    found = re.findall(r"\bDEMO_READY\s+trips=(\d+)", text)
    return int(found[-1]) if found else None


def install_apk(adb: Adb, apk: str) -> None:
    try:
        reply = adb.install(apk)
    except AdbError as exc:
        hint = ""
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in str(exc):
            hint = (
                "\n서명이 다른 앱(릴리스/디버그)이 이미 있습니다. 'adb uninstall %s' 후 다시 실행하세요."
                % APP_PACKAGE
            )
        raise SetupError(EXIT_FAIL, "APK 설치 실패: %s%s" % (exc, hint))
    if "Success" not in reply:
        raise SetupError(
            EXIT_FAIL, "APK 설치 결과를 확인하지 못했습니다: " + reply.strip()
        )


def start_app_args(user: int, restart: bool = False) -> List[str]:
    """`am start -W` (returns once the launch is drawn) in the given user; -S first stops a copy
    that is already running, so the fresh process logs VEHICLE_CONNECTED again."""
    args = ["am", "start", "-W"]
    if restart:
        args.append("-S")
    return args + ["--user", str(user), "-n", APP_ACTIVITY]


def start_app(adb: Adb, user: int, restart: bool = False) -> None:
    reply = adb.shell(*start_app_args(user, restart), timeout=90)
    if re.search(r"\bError\b|Exception", reply):
        raise SetupError(EXIT_FAIL, "앱을 시작하지 못했습니다: " + reply.strip())


@dataclass(frozen=True)
class UserSnapshot:
    headless_system_user: bool
    user: Optional[int]
    state: Optional[str]
    front: Optional[FrontActivity]


def user_snapshot(adb: Adb, headless_system_user: bool) -> UserSnapshot:
    user = adb.current_user()
    switching = user is None or (headless_system_user and user == 0)
    state = None if switching else adb.user_state(user)
    return UserSnapshot(
        headless_system_user, user, state, parse_front_activity(adb.activities())
    )


def user_not_ready(snap: UserSnapshot) -> Optional[str]:
    """Why the driver's screen is not usable yet, or None when it is."""
    if snap.user is None:
        return "현재 사용자를 알 수 없음 (am get-current-user)"
    if snap.headless_system_user and snap.user == 0:
        return "운전자 사용자로 전환 중 (지금 사용자 0)"
    if snap.state != USER_UNLOCKED:
        return "사용자 %d 상태 %s (잠금 해제 전)" % (snap.user, snap.state)
    if snap.front is None:
        return "앞 화면 없음 (잠금 화면 또는 전환 중)"
    if snap.front.user != snap.user:
        return "앞 화면이 다른 사용자의 것 (%s)" % snap.front
    if SETUP_WIZARD_MARK in snap.front.package:
        return "설정 마법사가 앞에 있음 (%s)" % snap.front
    if FALLBACK_HOME_MARK in snap.front.component or SDK_SETUP_MARK in snap.front.package:
        return "임시 홈(%s)만 떠 있음, 실제 홈 화면 대기" % snap.front
    return None


def skip_setup_wizard(adb: Adb, user: int) -> None:
    """The AOSP way to finish first-run setup on a test device, then back to the home screen."""
    adb.shell(
        "settings", "put", "--user", str(user), "secure", "user_setup_complete", "1"
    )
    adb.shell("settings", "put", "global", "device_provisioned", "1")
    adb.shell(
        "am",
        "start",
        "--user",
        str(user),
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.HOME",
    )


def wait_for_user_ready(
    adb: Adb,
    timeout: Optional[float] = None,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
    log: Callable[[str], None] = say,
) -> int:
    """After sys.boot_completed: the driver's user is unlocked and one of its activities (the home)
    is in front. Returns that user id.

    On Pleos Connect (Android Automotive with a headless system user) sys.boot_completed comes from
    user 0 while CarService is still bringing up user 10 behind the lock screen; an app started
    then runs in the wrong user or behind the keyguard, and never sees the console's drive."""
    timeout = USER_READY_WAIT_S if timeout is None else timeout
    now = now or time.monotonic
    sleep = sleep or time.sleep
    started = now()
    deadline = started + timeout
    hsum = adb.getprop(HSUM_PROP) == "true"
    skipped = False
    noted = started
    while True:
        snap = user_snapshot(adb, hsum)
        reason = user_not_ready(snap)
        if reason is None:
            log(
                "  운전자 화면 준비됨: 사용자 %d, 앞 화면 %s (%.0f초)"
                % (snap.user, snap.front.component, now() - started)
            )
            return snap.user
        if (
            snap.front is not None
            and SETUP_WIZARD_MARK in snap.front.package
            and not skipped
        ):
            log("  설정 마법사를 건너뜁니다 (%s)" % snap.front)
            skip_setup_wizard(adb, snap.user if snap.user is not None else 0)
            skipped = True
        if now() >= deadline:
            raise SetupError(
                EXIT_BOOT,
                "부팅은 끝났지만 %.0f초 안에 운전자 화면이 준비되지 않았습니다: %s"
                % (timeout, reason),
            )
        if now() - noted >= 30:
            noted = now()
            log("  운전자 화면 대기 중: %s" % reason)
        sleep(2.0)


def wait_for_broadcast_idle(
    adb: Adb, timeout: Optional[float] = None, now: Optional[Callable[[], float]] = None
) -> float:
    """`am wait-for-broadcast-idle`: returns once every queued broadcast (the driver's
    BOOT_COMPLETED to each app, which starts them all) has been delivered. Until then a 2-core
    emulator has no CPU to spare: the demo sample load took over 60 s instead of 20 s."""
    timeout = BROADCAST_IDLE_WAIT_S if timeout is None else timeout
    now = now or time.monotonic
    started = now()
    try:
        adb.shell("am", "wait-for-broadcast-idle", timeout=timeout)
    except AdbTimeout:
        raise SetupError(
            EXIT_BOOT,
            "%.0f초 안에 부팅 직후 방송(BOOT_COMPLETED 등)이 끝나지 않았습니다." % timeout,
        )
    return now() - started


def quiet_emulator_navigation(adb: Adb, user: int) -> None:
    """Prevent the bundled navigation app from deadlocking android.hardware.gnss-service.ranchu.

    On Pleos Connect v2.1.0 (user build without Google Play Services), HomeActivity launches
    ai.umos.maps.android.navigation.app during boot, which registers a GnssStatusCallback
    (IGnss::startSvStatus) and schedules a Google Datatransport JobInfoSchedulerService that
    ANRs 90 s later. When ActivityManager kills the navigation app, GnssStatusProvider.onReportSvStatus
    removes its dead binder registration and synchronously calls BpGnss::stopSvStatus back into
    android.hardware.gnss-service.ranchu while Gnss::onGnssSvStatusCb holds its non-recursive
    mutex, deadlocking system_server's android.fg thread until Watchdog kills system_server
    (CI run 41318807339). Blocking its UID-level location appops and RUN_ANY_IN_BACKGROUND via
    shell's MANAGE_APPOPS grant prevents both IGnss::startSvStatus and the Datatransport job ANR,
    while extending ActivityManager's cached-idle kill window to 2 hours prevents OomAdjuster
    from killing the background-restricted process after 10 minutes (CI run 41387159188), leaving
    AutoBrief's own PlatformLocationSource (app.autobrief.car) untouched.
    """
    for op in NAV_UID_OPS:
        try:
            adb.shell("appops", "set", "--user", str(user), "--uid", NAV_PACKAGE, op, "ignore")
        except AdbError:
            pass
    for op in NAV_QUIET_OPS:
        try:
            adb.shell("appops", "set", "--user", str(user), NAV_PACKAGE, op, "ignore")
        except AdbError:
            pass
    try:
        adb.shell("cmd", "jobscheduler", "cancel", "--user", str(user), NAV_PACKAGE)
    except AdbError:
        pass
    try:
        adb.shell("settings", "put", "global", "activity_manager_constants", NAV_AM_CONSTANTS)
    except AdbError:
        pass
    try:
        adb.shell("cmd", "car_service", "watchdog-control-process-health-check", "disable")
    except AdbError:
        pass



def wait_for_app_in_front(
    adb: Adb,
    user: int,
    timeout: Optional[float] = None,
    stable: Optional[float] = None,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
    log: Callable[[str], None] = say,
) -> FrontActivity:
    """Our activity is in front of `user` and stays there for `stable` seconds in a row.

    Anything else taking the front (the home opens its navigation panel right after unlock) gets
    the app started again, at most every APP_RELAUNCH_EVERY_S."""
    timeout = APP_FRONT_WAIT_S if timeout is None else timeout
    stable = APP_FRONT_STABLE_S if stable is None else stable
    now = now or time.monotonic
    sleep = sleep or time.sleep
    deadline = now() + timeout
    last_start = now()
    since = None
    front = None
    while True:
        dumpsys = adb.activities()
        front = parse_front_activity(dumpsys)
        ours = app_resumed(dumpsys, user)
        if ours is not None:
            if since is None:
                since = now()
            if now() - since >= stable:
                return ours
        else:
            since = None
            if now() - last_start >= APP_RELAUNCH_EVERY_S:
                log("  앞 화면이 %s 입니다. 앱을 다시 앞으로 가져옵니다." % front)
                start_app(adb, user)
                last_start = now()
        if now() >= deadline:
            raise SetupError(
                EXIT_FAIL,
                "%.0f초 안에 앱이 화면 앞에 자리잡지 못했습니다 (마지막 앞 화면: %s)"
                % (timeout, front),
            )
        sleep(1.0)


def wait_for_vehicle_connected(
    adb: Adb,
    timeout: Optional[float] = None,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> float:
    """The app logs VEHICLE_CONNECTED once the Pleos Vehicle SDK answers its odometer probe.
    Returns the seconds it took."""
    timeout = VEHICLE_CONNECTED_WAIT_S if timeout is None else timeout
    now = now or time.monotonic
    sleep = sleep or time.sleep
    started = now()
    deadline = started + timeout
    while True:
        if re.search(r"\bVEHICLE_CONNECTED\b", adb.logcat_dump()):
            return now() - started
        if now() >= deadline:
            detail = adb.out(
                "logcat", "-d", "-s", "AutoBriefVehicle:V", "AutoBriefNavi:V"
            ).strip()
            raise SetupError(
                EXIT_FAIL,
                "%.0f초 안에 앱이 차량 SDK에 연결되지 않았습니다 (VEHICLE_CONNECTED 없음).\n"
                "--- AutoBriefVehicle / AutoBriefNavi 로그 ---\n%s"
                % (timeout, detail[-2000:] or "(없음)"),
            )
        sleep(2.0)


def closed_for_not_responding(adb: Adb) -> bool:
    """True when Android closed (or is closing) the app for not responding. Android Automotive shows no ANR
    dialog (car UI mode): an app that does not take an input or focus event within 5 s is logged by
    WindowManager at once, while ActivityManager dumps stack traces (~23 s on API 34) before logging its
    own ANR line and killing the process."""
    pattern = r"\bANR in (?:Window\{[^\n]*\s)?%s\b" % re.escape(APP_PACKAGE)
    return bool(
        re.search(pattern, adb.logcat_dump("ActivityManager", "E"))
        or re.search(pattern, adb.logcat_dump("WindowManager", "I"))
    )


def wait_for_anr_settled(
    adb: Adb,
    timeout: float = 35.0,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> None:
    """When WindowManager declares an input/focus ANR, ActivityManager spends ~20-25 s dumping stack
    traces before logging `E ActivityManager: ANR in <pkg>` and killing the old process. Wait until that
    kill phase completes so a retry does not launch into the middle of the stack dump."""
    now = now or time.monotonic
    sleep = sleep or time.sleep
    deadline = now() + timeout
    pattern = r"\bANR in %s\b" % re.escape(APP_PACKAGE)
    while not re.search(pattern, adb.logcat_dump("ActivityManager", "E")):
        if now() >= deadline:
            break
        sleep(2.0)
    sleep(5.0)


def slot_surface_blank(png: bytes) -> bool:
    """True when a 2560x1600 Pleos screenshot compressed to the ~60 KB flat-#F7F7F7 size of a wedged
    ConnectTaskView surface (CI run 41292587613). Tiny test stubs (<1 KB) and rendered frames (>=100 KB)
    return False."""
    return (
        png.startswith(b"\x89PNG\r\n\x1a\n")
        and BLANK_SLOT_MIN_BYTES <= len(png) < BLANK_SLOT_MAX_BYTES
    )


def recover_blank_slot_surface(
    adb: Adb,
    user: int,
    say: Callable[[str], None],
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> bool:
    """If HomeActivity's ConnectTaskView surfaces wedged during cold boot (both slots flat #F7F7F7),
    restart HomeActivity after boot broadcasts have settled and re-open our app in the fresh slot."""
    sleep = sleep or time.sleep
    try:
        png = adb.screencap_png()
        if slot_surface_blank(png):
            sleep(APP_FRONT_STABLE_S)
            png = adb.screencap_png()
    except AdbError:
        return False
    if not slot_surface_blank(png):
        return False
    say(
        "  (경고) 홈 화면 슬롯 표면이 비어 있습니다 (%d bytes). 홈 화면을 다시 시작해 복구합니다."
        % len(png)
    )
    adb.shell(
        "am", "start", "-W", "-S", "--user", str(user), "-n", HOME_ACTIVITY, timeout=90
    )
    wait_for_user_ready(adb, now=now, sleep=sleep, log=say)
    sleep(HOME_RESTART_SETTLE_S)
    adb.logcat_clear(sleep=sleep)
    start_app(adb, user, restart=True)
    front = wait_for_app_in_front(adb, user, now=now, sleep=sleep, log=say)
    say("  앱이 화면 앞에 있습니다: %s" % front)
    say("  VEHICLE_CONNECTED (%.0f초)" % wait_for_vehicle_connected(adb, now=now, sleep=sleep))
    return True


def start_app_and_load(adb: Adb, user: int, args: argparse.Namespace, say: Callable[[str], None]) -> None:
    """Start the app fresh, wait for its vehicle link and, with --demo-sample, load the sample."""
    adb.logcat_clear()  # VEHICLE_CONNECTED must come from the process started now
    start_app(adb, user, restart=True)
    front = wait_for_app_in_front(adb, user)
    say("  앱이 화면 앞에 있습니다: %s" % front)
    say("  차량 SDK 연결 확인 중 (최대 %d초)..." % VEHICLE_CONNECTED_WAIT_S)
    say("  VEHICLE_CONNECTED (%.0f초)" % wait_for_vehicle_connected(adb))
    if closed_for_not_responding(adb):
        raise SetupError(
            EXIT_FAIL,
            "차량 SDK 연결 직후 앱 프로세스가 종료됐습니다.",
        )
    recover_blank_slot_surface(adb, user, say)
    if args.demo_sample:
        say(
            "  데모 샘플 전송%s"
            % (" (webhook %s)" % redact_url(args.webhook) if args.webhook else "")
        )
        say("  DEMO_READY trips=%d" % load_demo_sample(adb, args.webhook, user))
        wait_for_app_in_front(adb, user)
        if closed_for_not_responding(adb):
            raise SetupError(
                EXIT_FAIL,
                "데모 샘플을 불러온 뒤 앱이 응답 없음(ANR)으로 닫혔습니다.",
            )


def load_demo_sample(
    adb: Adb,
    webhook: Optional[str],
    user: Optional[int] = None,
    timeout: Optional[float] = None,
    now: Optional[Callable[[], float]] = None,
    sleep: Optional[Callable[[float], None]] = None,
) -> int:
    timeout = DEMO_READY_WAIT_S if timeout is None else timeout
    now = now or time.monotonic
    sleep = sleep or time.sleep
    adb.logcat_clear()  # a DEMO_READY left over from an earlier run must not satisfy the wait
    broadcast_timed_out = False
    try:
        # am broadcast returns when the receiver has finished loading (a background broadcast
        # that runs past 60 s is an ANR on the device as well)
        adb.shell(*demo_broadcast_args("load_sample", webhook, user))
    except AdbTimeout:
        broadcast_timed_out = True
    deadline = now() + timeout
    while True:
        trips = find_demo_ready(adb.logcat_dump())
        if trips is not None:
            return trips
        if now() >= deadline or closed_for_not_responding(adb):
            if broadcast_timed_out:
                raise SetupError(
                    EXIT_FAIL,
                    "앱이 데모 샘플을 60초 안에 다 불러오지 못했습니다 (DemoReceiver). 에뮬레이터가 바쁜지 "
                    "확인하세요 (adb shell cat /proc/loadavg).",
                )
            raise SetupError(
                EXIT_FAIL,
                "%.0f초 안에 DEMO_READY 로그를 보지 못했습니다. 디버그 APK(assembleDebug)를 설치했는지 확인하세요 "
                "(릴리스 APK에는 데모 훅이 없습니다)." % timeout,
            )
        sleep(2.0)


# --------------------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="setup_emulator.py",
        description=(
            "Pleos Connect v2.1.0 에뮬레이터를 설치하고 켠 뒤 앱을 설치합니다. "
            "(Pleos Connect v2.1.0 emulator + AutoBrief APK, one command.)"
        ),
        epilog=(
            "종료 코드: 0 성공, 1 기타 실패, 2 SDK/도구 없음 또는 사용법 오류, 3 내려받기/검증 실패, "
            "4 부팅 시간 초과, 5 가상 콘솔 연결 실패"
        ),
    )
    parser.add_argument(
        "--apk", metavar="PATH", help="설치할 APK (없으면 에뮬레이터만 준비)"
    )
    parser.add_argument(
        "--headless",
        action="store_true",
        help="창 없이 소프트웨어 렌더링으로 실행 (CI용, 부팅 최대 %d초)"
        % BOOT_TIMEOUT_HEADLESS_S,
    )
    parser.add_argument(
        "--no-browser", action="store_true", help="가상 콘솔을 브라우저로 열지 않음"
    )
    parser.add_argument(
        "--cache",
        metavar="DIR",
        help="내려받은 이미지 보관 폴더 (기본: ~/.cache/autobrief-pleos, Windows는 %%LOCALAPPDATA%%\\autobrief-pleos)",
    )
    parser.add_argument(
        "--out", metavar="DIR", help="에뮬레이터 로그를 둘 폴더 (기본: <cache>/out)"
    )
    parser.add_argument(
        "--skip-image",
        action="store_true",
        help="이미지 내려받기/설치를 건너뜀 (이미 설치돼 있어야 함, 오프라인 시연용)",
    )
    parser.add_argument(
        "--demo-sample",
        action="store_true",
        help="디버그 APK의 데모 훅으로 가상 1년치 샘플을 불러옴 (DEMO_READY 확인)",
    )
    parser.add_argument(
        "--webhook",
        metavar="URL",
        help="--demo-sample 과 함께: 샘플이 알림을 보낼 주소",
    )
    return parser


def stage(number: int, total: int, title: str) -> None:
    say("[%d/%d] %s" % (number, total, title))


def warn_disk(system: str, cache_dir: str, sdk: str) -> None:
    need = need_free_gb(system)
    note = (
        " (Windows는 희소 파일을 못 써서 더 필요합니다)" if system == "Windows" else ""
    )
    say("  디스크 여유 공간이 약 %d GB 필요합니다%s." % (need, note))
    for label, path in (("캐시", cache_dir), ("SDK", sdk)):
        free = free_gb(path)
        flag = "  <- 부족할 수 있음" if free < need else ""
        say("  %s 위치 여유 공간: %.1f GB%s" % (label, free, flag))


def run(args: argparse.Namespace) -> int:
    system = platform.system()
    home = os.path.expanduser("~")
    total = 9
    cache_dir = os.path.abspath(
        args.cache or default_cache_dir(os.environ, system, home)
    )
    out_dir = os.path.abspath(args.out or os.path.join(cache_dir, "out"))
    os.makedirs(out_dir, exist_ok=True)

    stage(1, total, "Android SDK와 도구 찾기")
    tools = find_sdk_tools(os.environ, system, home)
    say("  SDK: %s" % tools.sdk)
    abi = select_host_abi(platform.machine(), is_rosetta_translated(system))
    install_dir = image_install_dir(tools.sdk, abi)
    sysdir = image_sysdir(abi)
    say("  이 컴퓨터: %s -> 이미지 abi %s" % (platform.machine() or "알 수 없음", abi))
    official = official_image_dir(tools.sdk, abi)
    if not args.skip_image and installed_image_problem(official, abi) is None:
        # the presenter already installed the image through Android Studio's SDK Manager: use it as is
        say(
            "  Android Studio SDK Manager로 설치한 Pleos 이미지를 씁니다 (내려받기 생략): %s"
            % official
        )
        install_dir, sysdir = official, image_sysdir(abi, OFFICIAL_IMAGE_SUBDIRS)
        args.skip_image = True

    if args.skip_image:
        for number, title in (
            (2, "이미지 정보 읽기"),
            (3, "이미지 내려받기"),
            (4, "이미지 설치"),
        ):
            stage(number, total, title + " - 건너뜀 (--skip-image)")
        problem = installed_image_problem(install_dir, abi)
        if problem:
            raise SetupError(
                EXIT_DOWNLOAD,
                "설치된 이미지를 쓸 수 없습니다 (%s): %s" % (install_dir, problem),
            )
    else:
        warn_disk(system, cache_dir, tools.sdk)
        stage(2, total, "이미지 정보(XML) 읽기")
        xml_url = REPO_XML_URLS[abi]
        try:
            images = parse_repo_xml(fetch_bytes(xml_url), xml_url)
        except ET.ParseError as exc:
            raise SetupError(
                EXIT_DOWNLOAD, "이미지 목록(XML)을 해석하지 못했습니다: %s" % exc
            )
        for found in images:
            say("  찾음: " + describe_image(found))
        image = pick_image(images, abi, host_os_name(system))
        say("  선택: %s" % image.url)
        say(
            "  라이선스 안내: 이 이미지는 42dot Pleos Connect SDK 라이선스(%s) 대상이며 설치·사용 시 약관에 동의한 것으로 "
            "봅니다. 약관 전문은 %s 의 license 항목에 있습니다."
            % (image.license_id or "id 없음", xml_url)
        )
        stage(3, total, "이미지 내려받기 + 체크섬 확인 (이어받기 지원)")
        archive = ensure_archive(image, cache_dir)
        stage(4, total, "이미지 설치 -> %s" % install_dir)
        install_image(archive, install_dir, abi)

    stage(5, total, "AVD 만들기 (%s, 콜드 부팅, RAM %d MB)" % (AVD_NAME, AVD_RAM_MB))
    avd_home = avd_home_dir(os.environ, home)
    defaults = image_defaults_from_properties(
        read_properties(os.path.join(install_dir, "source.properties"))
    )
    avd_dir = write_avd(avd_home, abi, args.headless, defaults, sysdir)
    say("  AVD 폴더: %s" % avd_dir)

    adb = Adb(tools.adb, serial=DEFAULT_SERIAL)
    stage(
        6,
        total,
        "에뮬레이터 실행 (%s)"
        % ("창 없음, 소프트웨어 렌더링" if args.headless else "그래픽 하드웨어 사용"),
    )
    proc = None
    log_path = os.path.join(out_dir, "emulator.log")
    if DEFAULT_SERIAL in dict(adb.devices()):
        running = None
        try:
            running = adb.avd_name()
        except AdbError:
            pass
        if running != AVD_NAME:
            raise SetupError(
                EXIT_FAIL,
                "%s 포트를 다른 기기/에뮬레이터(%s)가 쓰고 있습니다. 끈 뒤 다시 실행하세요 "
                "(adb -s %s emu kill)."
                % (DEFAULT_SERIAL, running or "AVD 이름 확인 불가", DEFAULT_SERIAL),
            )
        say("  %s 가 이미 실행 중입니다. 새로 켜지 않고 그대로 씁니다." % AVD_NAME)
    else:
        proc = launch_emulator(
            emulator_command(tools.emulator, args.headless),
            log_path,
            tools.sdk,
            avd_home,
        )
        with open(os.path.join(out_dir, "emulator.pid"), "w") as handle:
            handle.write("%d\n" % proc.pid)
        say("  실행함 (pid %d), 로그: %s" % (proc.pid, log_path))

    timeout = BOOT_TIMEOUT_HEADLESS_S if args.headless else BOOT_TIMEOUT_GUI_S
    stage(7, total, "부팅 기다리기 (최대 %d초)" % timeout)
    wait_for_boot(adb, timeout, proc=proc, log_path=log_path)
    enlarge_logcat(adb)
    quiet_emulator_navigation(adb, 10)
    user = wait_for_user_ready(adb)
    quiet_emulator_navigation(adb, user)
    say("  부팅 직후 방송 처리 대기 중...")
    say("  방송 처리 끝 (%.0f초)" % wait_for_broadcast_idle(adb))

    stage(8, total, "앱 설치, 가상 콘솔 연결")
    if args.apk:
        say("  APK 설치: %s" % args.apk)
        install_apk(adb, args.apk)
    else:
        say("  --apk 없음: 앱 설치는 건너뜁니다.")
    adb.forward(CONSOLE_HOST_PORT, CONSOLE_GUEST_PORT)
    say("  adb forward tcp:%d -> tcp:%d" % (CONSOLE_HOST_PORT, CONSOLE_GUEST_PORT))
    wait_for_console(CONSOLE_WAIT_S)
    say("  가상 콘솔 응답 확인: %s/vehicle/state" % console_url())

    stage(9, total, "앱 실행%s" % (", 데모 샘플 불러오기" if args.demo_sample else ""))
    try:
        start_app_and_load(adb, user, args, say)
    except SetupError:
        # only an app Android closed for not responding is started once more; any other failure stands
        if not closed_for_not_responding(adb):
            raise
        wait_for_anr_settled(adb)
        say("  (경고) 에뮬레이터가 바빠 앱이 응답 없음(ANR)으로 닫혔습니다. 앱을 새로 띄워 한 번 더 합니다.")
        start_app_and_load(adb, user, args, say)
    if not (args.no_browser or args.headless):
        webbrowser.open(console_url() + "/")
        say("  브라우저로 가상 콘솔을 열었습니다: %s/" % console_url())

    say("")
    say("준비가 끝났습니다. 다음 단계:")
    say("  1) 시나리오 재생:   python3 demo/director.py --scenario all --mode step")
    say(
        "  2) 지사 관제 화면:  tools/fleet-console (샘플 알림 주소: http://10.0.2.2:8787/hook)"
    )
    say("  3) 에뮬레이터 끄기: adb -s %s emu kill" % DEFAULT_SERIAL)
    return EXIT_OK


def main(argv: Optional[Sequence[str]] = None) -> int:
    configure_stdio()
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.webhook and not args.demo_sample:
        parser.error("--webhook 은 --demo-sample 과 함께 써야 합니다")
    if args.apk and not os.path.isfile(args.apk):
        parser.error("APK 파일이 없습니다: %s" % args.apk)
    try:
        return run(args)
    except SetupError as exc:
        say("")
        say("오류: %s" % exc, err=True)
        return exc.code
    except AdbError as exc:
        say("")
        say("오류: %s" % exc, err=True)
        return EXIT_FAIL
    except KeyboardInterrupt:
        say(
            "\n중단했습니다. (에뮬레이터는 켜져 있을 수 있습니다: adb -s %s emu kill)"
            % DEFAULT_SERIAL
        )
        return 130


if __name__ == "__main__":
    sys.exit(main())
