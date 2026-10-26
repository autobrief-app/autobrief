"""Device-free tests for the demo kit (stdlib unittest, Python 3.9+).

Run from the repository root:  python3 -m unittest discover -s demo -v

Nothing here needs an emulator, /dev/kvm or the internet: HTTP is served from localhost,
the zip is synthetic, and the "device" is a fake SDK (adb + emulator scripts) plus a fake
Vehicle Virtual Console. The two end-to-end rehearsals at the bottom need POSIX scripts.
"""

import contextlib
import datetime
import hashlib
import http.client
import io
import json
import os
import platform
import re
import signal
import socket
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock
from urllib.parse import parse_qsl, urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import director  # noqa: E402
import setup_emulator as se  # noqa: E402

MIB = 1 << 20

# --------------------------------------------------------------------------- fixtures

# The measured structure of the Pleos repository XML (license text shortened).
REPO_XML = """<sys-img:sdk-sys-img xmlns:sys-img="http://schemas.android.com/sdk/android/repo/sys-img2/01" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
\t<license id="pleos-connect-sdk-license" type="text">Software Development Kit License Agreement (trimmed)</license>
\t<channel id="channel-0">stable</channel>
\t<remotePackage path="system-images;android-34;pleos_car_emulator;{abi}">
\t<type-details xsi:type="sys-img:sysImgDetailsType">
\t\t<api-level>34</api-level>
\t\t<tag>
\t\t\t<id>android-automotive</id>
\t\t\t<display>Pleos Connect v2.1.0 APIs</display>
\t\t</tag>
\t\t<vendor>
\t\t\t<id>pleos</id>
\t\t\t<display>Pleos Connect v2.1.0</display>
\t\t</vendor>
\t\t<abi>{abi}</abi>
\t</type-details>
\t<revision>
\t\t<major>2</major>
\t</revision>
\t<display-name>{display}</display-name>
\t<uses-license ref="pleos-connect-sdk-license"/>
\t<channelRef ref="channel-0"/>
\t<archives>
\t\t<archive>
\t\t\t<complete>
\t\t\t\t<size>{size}</size>
\t\t\t\t<checksum>{checksum}</checksum>
\t\t\t\t<url>{url}</url>
\t\t\t</complete>
\t\t</archive>
\t</archives>
\t</remotePackage>
</sys-img:sdk-sys-img>
"""

X86_XML = REPO_XML.format(
    abi="x86_64",
    display="Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image",
    size=4087751964,
    checksum="b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa",
    url="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip",
)
ARM64_XML = REPO_XML.format(
    abi="arm64-v8a",
    display="Pleos Connect v2.1.0 APIs arm64 System Image",
    size=4669762816,
    checksum="24d28eb2f18eba5e87c861a7eb2bdccebb049419",
    url="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_arm64_emulator.zip",
)

# the spec describes prefixed element names (<sdk:url> ...) and a relative archive url
PREFIXED_XML = """<sdk:sdk-sys-img xmlns:sdk="http://schemas.android.com/sdk/android/sys-img/3">
  <sdk:license id="pleos-connect-sdk-license" type="text">trimmed</sdk:license>
  <sdk:system-image>
    <sdk:api-level>34</sdk:api-level>
    <sdk:tag><sdk:id>android-automotive</sdk:id><sdk:display>Automotive</sdk:display></sdk:tag>
    <sdk:vendor><sdk:id>42dot</sdk:id><sdk:display>42dot</sdk:display></sdk:vendor>
    <sdk:abi>x86_64</sdk:abi>
    <sdk:uses-license ref="pleos-connect-sdk-license"/>
    <sdk:archives>
      <sdk:archive>
        <sdk:complete>
          <sdk:size>4087751964</sdk:size>
          <sdk:checksum type="sha1">b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa</sdk:checksum>
          <sdk:url>crp_car_emulator.zip</sdk:url>
        </sdk:complete>
      </sdk:archive>
    </sdk:archives>
  </sdk:system-image>
</sdk:sdk-sys-img>
"""

SOURCE_PROPERTIES = """Pkg.Desc=System Image {abi}
Pkg.Revision=1
Pkg.Dependencies=emulator
AndroidVersion.ApiLevel=34
SystemImage.Abi={abi}
SystemImage.TagId=android-automotive
SystemImage.TagDisplay=Automotive
SystemImage.GpuSupport=true
Addon.VendorId=42dot
Addon.VendorDisplay=42dot Inc.
avd_config_ini.hw.keyboard=yes
avd_config_ini.hw.ramSize=8192
avd_config_ini.hw.gpu.enabled=yes
avd_config_ini.hw.gpu.mode=host
avd_config_ini.hw.multi_display_window=no
avd_config_ini.hw.hotplug_multi_display=no
"""

LOGCAT_OK = (
    "10-25 12:00:00.100  1000  1000 I AutoBriefLog: VEHICLE_CONNECTED\n"
    "10-25 12:00:01.000  1000  1000 I AutoBriefLog: TRIP_OPENED odo=45200.0\n"
    "10-25 12:00:20.000  1000  1000 I AutoBriefLog: TRIP_CLOSED id=TRIP-1761390000 "
    "start=45200.0 end=45218.0 km=18.0 purpose=COMMUTE by=자동 reason=집→회사\n"
    "10-25 12:00:20.100  1000  1000 I AutoBriefLog: LEDGER seq=12 kind=TRIP hash=abcd1234\n"
)

PNG = director.PNG_SIGNATURE + b"fake-image-bytes"


def payload_bytes(size):
    return bytes((i * 31 + 7) % 256 for i in range(size))


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def kv(text):
    return dict(line.split("=", 1) for line in text.splitlines() if line)


def local_opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class QuietTestCase(unittest.TestCase):
    """Progress bars and say() write to stdout; keep the unittest output readable."""

    def setUp(self):
        super().setUp()
        redirect = contextlib.redirect_stdout(io.StringIO())
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)


def host_abi():
    return se.select_host_abi(
        platform.machine(), se.is_rosetta_translated(platform.system())
    )


class RangeServer:
    """localhost HTTP server for static payloads with (optionally broken) Range support.

    routes: path -> bytes, or a list of bytes served per request (the last one repeats).
    mode: "range" honours Range:, "ignore" always answers 200 with the full body,
    "wrong-range" answers 206 but for the wrong offset (a buggy server).
    cut: "first" / "always" = send half of the body, then drop the connection.
    """

    def __init__(self, routes, mode="range", cut=None):
        self.routes, self.mode, self.cut = routes, mode, cut
        self.requests = []
        self.lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                with outer.lock:
                    outer.requests.append((self.path, self.headers.get("Range")))
                    count = sum(1 for path, _ in outer.requests if path == self.path)
                if self.path not in outer.routes:
                    self.send_error(404)
                    return
                payload = outer.routes[self.path]
                if isinstance(payload, list):
                    payload = payload[min(count, len(payload)) - 1]
                wanted = self.headers.get("Range")
                start = 0
                if wanted and outer.mode == "wrong-range":
                    self.send_response(206)
                    self.send_header(
                        "Content-Range",
                        "bytes 0-%d/%d" % (len(payload) - 1, len(payload)),
                    )
                elif wanted and outer.mode == "range":
                    start = int(re.match(r"bytes=(\d+)-$", wanted).group(1))
                    if start >= len(payload):
                        self.send_response(416)
                        self.send_header("Content-Range", "bytes */%d" % len(payload))
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    self.send_response(206)
                    self.send_header(
                        "Content-Range",
                        "bytes %d-%d/%d" % (start, len(payload) - 1, len(payload)),
                    )
                else:
                    self.send_response(200)
                body = payload[start:]
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if outer.cut == "always" or (outer.cut == "first" and count == 1):
                    body = body[: len(body) // 2]
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            kwargs={"poll_interval": 0.05},
            daemon=True,
        )
        self.thread.start()

    def url(self, path):
        return "http://127.0.0.1:%d%s" % (self.port, path)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def build_image_zip(path, abi, folder=None, with_junk=False):
    """A small stand-in for the Pleos image zip: one top-level folder, a sparse-friendly system.img."""
    folder = folder or "crp_avd_out_" + abi
    block = (bytes(range(1, 256)) * 9000)[: 2 * MIB]  # no zero byte anywhere
    system_img = (
        block + bytes(6 * MIB) + block[:MIB] + bytes(4 * MIB)
    )  # 13 MiB, 3 MiB real
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(folder + "/", b"")
        archive.writestr(
            folder + "/source.properties", SOURCE_PROPERTIES.format(abi=abi)
        )
        archive.writestr(folder + "/system.img", system_img)
        archive.writestr(folder + "/data/", b"")
        archive.writestr(folder + "/data/misc/config.xml", "<config/>")
        if with_junk:
            archive.writestr("__MACOSX/._source.properties", b"junk")
    return system_img


def filesystem_has_holes(directory):
    probe = os.path.join(directory, "hole_probe")
    with open(probe, "wb") as handle:
        handle.seek(8 * MIB)
        handle.write(b"x")
    info = os.stat(probe)
    os.remove(probe)
    return hasattr(info, "st_blocks") and info.st_blocks * 512 < MIB


# --------------------------------------------------------------------------- repository XML


class RepoXmlTests(unittest.TestCase):
    def test_measured_x86_structure(self):
        images = se.parse_repo_xml(X86_XML, se.REPO_XML_URLS["x86_64"])
        self.assertEqual(len(images), 1)
        image = images[0]
        self.assertEqual(image.api_level, 34)
        self.assertEqual(image.abi, "x86_64")
        self.assertEqual(image.size, 4087751964)
        self.assertEqual(image.checksum, "b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa")
        self.assertEqual(image.checksum_type, "sha1")
        self.assertEqual(
            image.url,
            "https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip",
        )
        self.assertEqual(
            image.display_name,
            "Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image",
        )
        self.assertEqual(image.license_id, "pleos-connect-sdk-license")
        self.assertEqual(image.tag_id, "android-automotive")

    def test_arm64_xml_parses_the_same_way(self):
        images = se.parse_repo_xml(ARM64_XML, se.REPO_XML_URLS["arm64-v8a"])
        self.assertEqual([i.abi for i in images], ["arm64-v8a"])
        self.assertEqual(images[0].size, 4669762816)
        self.assertTrue(images[0].url.endswith("/crp_car_arm64_emulator.zip"))
        self.assertIn("arm64", se.describe_image(images[0]))

    def test_bytes_input_and_prefixed_names_with_relative_url(self):
        base = "https://example.test/repo/sys-img.xml"
        images = se.parse_repo_xml(PREFIXED_XML.encode("utf-8"), base)
        self.assertEqual(len(images), 1)
        self.assertEqual(
            images[0].url, "https://example.test/repo/crp_car_emulator.zip"
        )
        self.assertEqual(images[0].size, 4087751964)
        self.assertEqual(images[0].checksum_type, "sha1")
        self.assertEqual(images[0].vendor_id, "42dot")
        self.assertEqual(images[0].display_name, "")
        self.assertIn("api 34", se.describe_image(images[0]))

    def test_pick_image_selects_by_abi(self):
        mixed = se.parse_repo_xml(X86_XML, "https://h/x.xml") + se.parse_repo_xml(
            ARM64_XML, "https://h/y.xml"
        )
        self.assertEqual(se.pick_image(mixed, "arm64-v8a").abi, "arm64-v8a")
        self.assertEqual(se.pick_image(mixed, "x86_64").abi, "x86_64")

    def test_pick_image_failure_lists_what_was_found(self):
        images = se.parse_repo_xml(X86_XML, "https://h/x.xml")
        with self.assertRaises(se.SetupError) as caught:
            se.pick_image(images, "arm64-v8a")
        self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
        self.assertIn("x86_64", str(caught.exception))
        self.assertIn("arm64-v8a", str(caught.exception))

    def test_pick_image_honours_host_os_restriction(self):
        xml = """<repo><remotePackage path="p"><type-details><api-level>34</api-level>
        <abi>x86_64</abi></type-details><archives>
        <archive><host-os>linux</host-os><complete><size>1</size><checksum>{a}</checksum>
        <url>linux.zip</url></complete></archive>
        <archive><host-os>windows</host-os><complete><size>2</size><checksum>{a}</checksum>
        <url>win.zip</url></complete></archive>
        </archives></remotePackage></repo>""".format(a="a" * 40)
        images = se.parse_repo_xml(xml, "https://h/x.xml")
        self.assertEqual(
            se.pick_image(images, "x86_64", "windows").url, "https://h/win.zip"
        )
        self.assertEqual(
            se.pick_image(images, "x86_64", "linux").url, "https://h/linux.zip"
        )

    def test_dtd_and_entity_declarations_are_rejected(self):
        bomb = '<?xml version="1.0"?><!DOCTYPE r [<!ENTITY a "b">]><r>&a;</r>'
        for text in (
            bomb,
            bomb.encode("utf-8"),
            bomb.lower().replace("doctype", "DocType"),
        ):
            with self.assertRaises(se.SetupError) as caught:
                se.parse_repo_xml(text, "https://h/x.xml")
            self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)

    def test_checksum_algorithm_from_type_or_length(self):
        self.assertEqual(se.checksum_algorithm("sha1", "x"), "sha1")
        self.assertEqual(se.checksum_algorithm("SHA-256", "x"), "sha256")
        self.assertEqual(se.checksum_algorithm("", "a" * 40), "sha1")
        self.assertEqual(se.checksum_algorithm("", "a" * 64), "sha256")
        self.assertEqual(se.checksum_algorithm("", "abc"), "unknown")

    def test_host_abi_selection(self):
        for machine in ("arm64", "aarch64", "ARM64"):
            self.assertEqual(se.select_host_abi(machine), "arm64-v8a")
        for machine in ("x86_64", "AMD64", "i386", ""):
            self.assertEqual(se.select_host_abi(machine), "x86_64")
        # x86_64 Python under Rosetta on an Apple-silicon Mac still needs the arm64 image
        self.assertEqual(se.select_host_abi("x86_64", translated=True), "arm64-v8a")
        self.assertEqual(se.normalize_abi("aarch64"), "arm64-v8a")


# --------------------------------------------------------------------------- AVD text


class AvdTests(unittest.TestCase):
    def test_config_x86_64(self):
        config = kv(se.build_avd_config("x86_64", headless=False))
        expected = {
            "AvdId": "Pleos_Connect_v2.1.0",
            "avd.ini.displayname": "Pleos Connect v2.1.0",
            "abi.type": "x86_64",
            "hw.cpu.arch": "x86_64",
            "hw.ramSize": "4096",
            "hw.lcd.width": "2560",
            "hw.lcd.height": "1440",
            "hw.lcd.density": "160",
            "hw.gpu.enabled": "yes",
            "hw.gpu.mode": "host",
            "hw.keyboard": "yes",
            "hw.audioInput": "yes",
            "image.sysdir.1": "system-images/android-34/pleos-connect/x86_64/",
            "tag.id": "android-automotive",
            "tag.display": "Automotive",
            "disk.dataPartition.size": "6G",
            "fastboot.forceColdBoot": "yes",
            "fastboot.forceFastBoot": "no",
        }
        for key, value in expected.items():
            self.assertEqual(config.get(key), value, key)

    def test_config_arm64(self):
        config = kv(se.build_avd_config("arm64-v8a", headless=False))
        self.assertEqual(config["abi.type"], "arm64-v8a")
        self.assertEqual(config["hw.cpu.arch"], "arm64")
        self.assertEqual(
            config["image.sysdir.1"],
            "system-images/android-34/pleos-connect/arm64-v8a/",
        )
        self.assertEqual(config["hw.gpu.mode"], "host")
        self.assertEqual(config["hw.ramSize"], "4096")

    def test_headless_switches_to_software_rendering(self):
        for abi in ("x86_64", "arm64-v8a"):
            config = kv(se.build_avd_config(abi, headless=True))
            self.assertEqual(config["hw.gpu.mode"], "swiftshader_indirect")
            self.assertEqual(config["hw.gpu.enabled"], "yes")

    def test_image_defaults_are_merged_but_fixed_settings_win(self):
        props = se.parse_properties(SOURCE_PROPERTIES.format(abi="x86_64"))
        defaults = se.image_defaults_from_properties(props)
        self.assertEqual(defaults["hw.ramSize"], "8192")
        text = se.build_avd_config("x86_64", headless=True, image_defaults=defaults)
        config = kv(text)
        self.assertEqual(
            config["hw.ramSize"], "4096"
        )  # official guide: 4 GB, not the image's 8 GB
        self.assertEqual(config["hw.gpu.mode"], "swiftshader_indirect")
        self.assertEqual(
            config["hw.multi_display_window"], "no"
        )  # image default passes through
        self.assertEqual(config["hw.hotplug_multi_display"], "no")
        keys = [line.split("=", 1)[0] for line in text.splitlines()]
        self.assertEqual(len(keys), len(set(keys)), "no duplicate keys")

    def test_ini_text(self):
        self.assertEqual(
            se.build_avd_ini(
                "/home/u/.android/avd/Pleos_Connect_v2.1.0.avd"
            ).splitlines(),
            [
                "avd.ini.encoding=UTF-8",
                "path=/home/u/.android/avd/Pleos_Connect_v2.1.0.avd",
                "target=android-34",
            ],
        )

    def test_write_avd_creates_both_files_and_rewrites_on_mode_change(self):
        with tempfile.TemporaryDirectory() as home:
            avd_dir = se.write_avd(home, "x86_64", False, {})
            self.assertEqual(avd_dir, os.path.join(home, "Pleos_Connect_v2.1.0.avd"))
            with open(
                os.path.join(home, "Pleos_Connect_v2.1.0.ini"), encoding="utf-8"
            ) as f:
                ini = kv(f.read())
            self.assertEqual(ini["path"], avd_dir)
            self.assertEqual(ini["target"], "android-34")
            userdata = os.path.join(avd_dir, "userdata-qemu.img")
            with open(userdata, "wb") as f:
                f.write(b"keep me")
            se.write_avd(home, "x86_64", True, {})
            with open(os.path.join(avd_dir, "config.ini"), encoding="utf-8") as f:
                self.assertEqual(kv(f.read())["hw.gpu.mode"], "swiftshader_indirect")
            self.assertTrue(os.path.exists(userdata), "existing AVD data is not wiped")

    def test_avd_home_precedence(self):
        self.assertEqual(
            se.avd_home_dir(
                {"ANDROID_AVD_HOME": "/a", "ANDROID_USER_HOME": "/u"}, "/h"
            ),
            "/a",
        )
        self.assertEqual(
            se.avd_home_dir({"ANDROID_USER_HOME": "/u"}, "/h"),
            os.path.join("/u", "avd"),
        )
        self.assertEqual(
            se.avd_home_dir({}, "/h"), os.path.join("/h", ".android", "avd")
        )

    def test_emulator_command_gui_and_headless(self):
        gui = se.emulator_command("/sdk/emulator/emulator", headless=False)
        self.assertEqual(
            gui,
            [
                "/sdk/emulator/emulator",
                "-avd",
                "Pleos_Connect_v2.1.0",
                "-no-snapshot",
                "-no-boot-anim",
                "-memory",
                "4096",
            ],
        )
        headless = se.emulator_command("/sdk/emulator/emulator", headless=True)
        self.assertEqual(
            headless[len(gui) :],
            ["-no-window", "-no-audio", "-gpu", "swiftshader_indirect"],
        )


# --------------------------------------------------------------------------- SDK discovery


class SdkTests(unittest.TestCase):
    def make_sdk(self, root, system="Linux", emulator=True, adb=True):
        suffix = ".exe" if system == "Windows" else ""
        for name, wanted in (("emulator", emulator), ("platform-tools", adb)):
            if wanted:
                folder = os.path.join(root, name)
                os.makedirs(folder, exist_ok=True)
                binary = "emulator" if name == "emulator" else "adb"
                with open(os.path.join(folder, binary + suffix), "w") as handle:
                    handle.write("")
        os.makedirs(root, exist_ok=True)
        return root

    def test_candidates_follow_env_then_platform_default(self):
        env = {"ANDROID_HOME": "/a", "ANDROID_SDK_ROOT": "/b"}
        self.assertEqual(
            se.sdk_candidates(env, "Darwin", "/Users/u"),
            ["/a", "/b", "/Users/u/Library/Android/sdk"],
        )
        self.assertEqual(
            se.sdk_candidates({}, "Linux", "/home/u"), ["/home/u/Android/Sdk"]
        )
        windows = se.sdk_candidates({"LOCALAPPDATA": "/L"}, "Windows", "/home/u")
        self.assertEqual(windows, [os.path.join("/L", "Android", "Sdk")])
        self.assertEqual(
            se.sdk_candidates(
                {"ANDROID_HOME": "/a", "ANDROID_SDK_ROOT": "/a"}, "Linux", "/h"
            )[0],
            "/a",
        )

    def test_env_sdk_wins_and_default_location_is_the_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = os.path.join(tmp, "home")
            default = self.make_sdk(os.path.join(home, "Android", "Sdk"))
            custom = self.make_sdk(os.path.join(tmp, "custom"))
            self.assertEqual(
                se.find_sdk_tools({"ANDROID_HOME": custom}, "Linux", home).sdk, custom
            )
            self.assertEqual(se.find_sdk_tools({}, "Linux", home).sdk, default)
            tools = se.find_sdk_tools({}, "Linux", home)
            self.assertEqual(tools.adb, os.path.join(default, "platform-tools", "adb"))
            self.assertEqual(
                tools.emulator, os.path.join(default, "emulator", "emulator")
            )

    def test_incomplete_first_candidate_falls_through(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = os.path.join(tmp, "home")
            good = self.make_sdk(os.path.join(home, "Android", "Sdk"))
            broken = self.make_sdk(os.path.join(tmp, "broken"), emulator=False)
            self.assertEqual(
                se.find_sdk_tools({"ANDROID_HOME": broken}, "Linux", home).sdk, good
            )

    def test_windows_binaries_have_exe_suffix(self):
        with tempfile.TemporaryDirectory() as tmp:
            sdk = self.make_sdk(
                os.path.join(tmp, "L", "Android", "Sdk"), system="Windows"
            )
            tools = se.find_sdk_tools(
                {"LOCALAPPDATA": os.path.join(tmp, "L")}, "Windows", tmp
            )
            self.assertTrue(tools.adb.endswith("adb.exe"))
            self.assertTrue(tools.emulator.endswith("emulator.exe"))
            self.assertEqual(tools.sdk, sdk)

    def test_missing_tools_exit_2_with_korean_instructions(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.make_sdk(os.path.join(tmp, "Android", "Sdk"), emulator=False)
            with self.assertRaises(se.SetupError) as caught:
                se.find_sdk_tools({}, "Linux", tmp)
            self.assertEqual(caught.exception.code, se.EXIT_TOOLS)
            message = str(caught.exception)
            for needle in (
                "SDK Manager",
                "Android Emulator",
                "Platform-Tools",
                "ANDROID_HOME",
                "emulator",
            ):
                self.assertIn(needle, message)
            with self.assertRaises(se.SetupError) as nowhere:
                se.find_sdk_tools({}, "Linux", os.path.join(tmp, "empty"))
            self.assertIn("폴더 없음", str(nowhere.exception))

    def test_director_only_needs_adb(self):
        with tempfile.TemporaryDirectory() as tmp:
            sdk = self.make_sdk(os.path.join(tmp, "Android", "Sdk"), emulator=False)
            tools = se.find_sdk_tools({}, "Linux", tmp, need_emulator=False)
            self.assertEqual(tools.sdk, sdk)
            self.assertIsNone(tools.emulator)


# --------------------------------------------------------------------------- small helpers


class HelperTests(unittest.TestCase):
    def test_device_shell_command_quotes_for_the_device_shell(self):
        self.assertEqual(
            se.device_shell_command(
                ["am", "start", "-n", "app.autobrief.car/.ui.MainActivity"]
            ),
            "am start -n app.autobrief.car/.ui.MainActivity",
        )
        quoted = se.device_shell_command(["echo", "a b", "x&y", "it's"])
        self.assertEqual(quoted, "echo 'a b' 'x&y' 'it'\"'\"'s'")

    def test_demo_broadcast_args(self):
        self.assertEqual(
            se.demo_broadcast_args("load_sample", "http://10.0.2.2:8787/hook"),
            [
                "am",
                "broadcast",
                "-a",
                "app.autobrief.car.debug.DEMO",
                "-p",
                "app.autobrief.car",
                "--es",
                "cmd",
                "load_sample",
                "--es",
                "webhook",
                "http://10.0.2.2:8787/hook",
            ],
        )
        self.assertEqual(
            se.demo_broadcast_args("reset"),
            [
                "am",
                "broadcast",
                "-a",
                "app.autobrief.car.debug.DEMO",
                "-p",
                "app.autobrief.car",
                "--es",
                "cmd",
                "reset",
            ],
        )
        # a webhook with a query string must reach the device shell as ONE argument
        command = se.device_shell_command(
            se.demo_broadcast_args("load_sample", "http://h/p?a=1&b=2")
        )
        self.assertTrue(command.endswith("--es webhook 'http://h/p?a=1&b=2'"))

    def test_parse_adb_devices(self):
        text = "* daemon not running; starting now at tcp:5037\nList of devices attached\nemulator-5554\tdevice\nR58M123\toffline\n\n"
        self.assertEqual(
            se.parse_adb_devices(text),
            [("emulator-5554", "device"), ("R58M123", "offline")],
        )
        self.assertEqual(se.parse_adb_devices("List of devices attached\n\n"), [])

    def test_parse_emu_avd_name(self):
        self.assertEqual(
            se.parse_emu_avd_name("Pleos_Connect_v2.1.0\nOK\n"), "Pleos_Connect_v2.1.0"
        )
        self.assertIsNone(se.parse_emu_avd_name("KO: unknown command\n"))
        self.assertIsNone(se.parse_emu_avd_name(""))

    def test_parse_content_range(self):
        self.assertEqual(se.parse_content_range("bytes 100-999/1000"), (100, 999, 1000))
        self.assertEqual(se.parse_content_range("bytes 0-9/*"), (0, 9, None))
        self.assertIsNone(se.parse_content_range("bytes */1000"))
        self.assertIsNone(se.parse_content_range(None))

    def test_find_demo_ready(self):
        text = "x DEMO_READY trips=12\ny AutoBriefLog: DEMO_READY trips=487\n"
        self.assertEqual(se.find_demo_ready(text), 487)
        self.assertIsNone(se.find_demo_ready("DEMO_READY_SOON trips=1\nno ready here"))

    def test_redact_url_hides_query_and_userinfo(self):
        self.assertEqual(
            se.redact_url("https://user:pw@example.test:8443/hook?token=abc"),
            "https://example.test:8443/hook",
        )
        self.assertEqual(
            se.redact_url("http://10.0.2.2:8787/hook"), "http://10.0.2.2:8787/hook"
        )

    def test_disk_requirements_and_cache_dir(self):
        self.assertEqual(se.need_free_gb("Windows"), 25)
        self.assertEqual(se.need_free_gb("Darwin"), 10)
        self.assertEqual(se.need_free_gb("Linux"), 10)
        self.assertEqual(
            se.default_cache_dir({}, "Linux", "/home/u"),
            "/home/u/.cache/autobrief-pleos",
        )
        self.assertEqual(
            se.default_cache_dir({"LOCALAPPDATA": "/L"}, "Windows", "/home/u"),
            os.path.join("/L", "autobrief-pleos"),
        )
        self.assertGreater(se.free_gb(tempfile.gettempdir()), 0)
        self.assertGreater(
            se.free_gb(os.path.join(tempfile.gettempdir(), "does", "not", "exist")), 0
        )

    def test_properties_parsing(self):
        props = se.parse_properties("# c\nA=1\n\nB = two = 2 \nbad line\n")
        self.assertEqual(props, {"A": "1", "B": "two = 2"})

    def test_emulator_log_hints(self):
        self.assertIn(
            "가상화",
            se.hint_for_emulator_log(
                "ERROR: x86 emulation currently requires hardware acceleration!"
            ),
        )
        self.assertIn(
            "디스크", se.hint_for_emulator_log("write failed: No space left on device")
        )
        self.assertIn(
            "그래픽",
            se.hint_for_emulator_log(
                "ERROR | Could not initialize emulated framebuffer"
            ),
        )
        self.assertIn("AVD", se.hint_for_emulator_log("PANIC: Unknown AVD name [x]"))
        self.assertIsNone(
            se.hint_for_emulator_log("emulator: Android emulator version 35")
        )

    def test_progress_formatting_and_throttling(self):
        self.assertEqual(se.format_progress("x", 5, 10), "x:  50.0% (0.00 / 0.00 GB)")
        self.assertEqual(se.format_progress("x", 2_000_000_000, None), "x: 2.00 GB")
        stream = io.StringIO()
        progress = se.Progress("dl", 1000, stream=stream)
        for done in range(0, 1001, 10):
            progress.update(done)
        progress.finish()
        lines = stream.getvalue().splitlines()
        self.assertLessEqual(len(lines), 21, "one line per 5% on a non-tty")
        self.assertIn("100.0%", lines[-1])

        class Tty(io.StringIO):
            def isatty(self):
                return True

        clock = [0.0]
        tty = Tty()
        live = se.Progress("dl", 100, stream=tty, now=lambda: clock[0])
        live.update(10)
        live.update(11)  # inside the 0.25 s throttle window
        clock[0] = 1.0
        live.update(50)
        live.update(100)  # completion is never throttled
        live.finish()
        self.assertEqual(tty.getvalue().count("\r"), 3)
        self.assertTrue(tty.getvalue().endswith("\n"))


# --------------------------------------------------------------------------- download


class DownloadTests(QuietTestCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.payload = payload_bytes(300_000)
        self.part = os.path.join(self.tmp.name, "img.zip.part")
        self.sleeps = []
        self.logs = []

    def serve(self, **kwargs):
        server = RangeServer({"/img.zip": self.payload}, **kwargs)
        self.addCleanup(server.close)
        return server

    def download(self, server, size=None, **kwargs):
        return se.download_with_resume(
            server.url("/img.zip"),
            self.part,
            len(self.payload) if size is None else size,
            opener=local_opener(),
            sleep=self.sleeps.append,
            log=self.logs.append,
            **kwargs,
        )

    def read_part(self):
        with open(self.part, "rb") as handle:
            return handle.read()

    def test_fresh_download(self):
        server = self.serve()
        self.assertEqual(self.download(server), len(self.payload))
        self.assertEqual(self.read_part(), self.payload)
        self.assertEqual(server.requests, [("/img.zip", None)])

    def test_resumes_from_a_partial_file_with_a_range_request(self):
        with open(self.part, "wb") as handle:
            handle.write(self.payload[:100_000])
        server = self.serve()
        self.download(server)
        self.assertEqual(server.requests, [("/img.zip", "bytes=100000-")])
        self.assertEqual(self.read_part(), self.payload)

    def test_complete_partial_file_needs_no_request(self):
        with open(self.part, "wb") as handle:
            handle.write(self.payload)
        server = self.serve()
        self.download(server)
        self.assertEqual(server.requests, [])

    def test_oversized_partial_is_discarded(self):
        with open(self.part, "wb") as handle:
            handle.write(self.payload + b"garbage")
        server = self.serve()
        self.download(server)
        self.assertEqual(self.read_part(), self.payload)
        self.assertEqual(server.requests, [("/img.zip", None)])

    def test_server_that_ignores_range_restarts_from_zero(self):
        with open(self.part, "wb") as handle:
            handle.write(b"stale" * 1000)
        server = self.serve(mode="ignore")
        self.download(server)
        self.assertEqual(self.read_part(), self.payload)
        self.assertTrue(any("처음부터" in line for line in self.logs))

    def test_stream_cut_short_is_resumed(self):
        server = self.serve(cut="first")
        self.download(server)
        self.assertEqual(self.read_part(), self.payload)
        self.assertEqual(len(server.requests), 2)
        self.assertIsNone(server.requests[0][1])
        self.assertEqual(server.requests[1][1], "bytes=%d-" % (len(self.payload) // 2))
        self.assertEqual(len(self.sleeps), 1, "one backoff between the two attempts")

    def test_unknown_size_uses_the_response_headers(self):
        server = self.serve()
        size = se.download_with_resume(
            server.url("/img.zip"),
            self.part,
            None,
            opener=local_opener(),
            sleep=self.sleeps.append,
            log=self.logs.append,
        )
        self.assertEqual(size, len(self.payload))

    def test_416_for_a_partial_that_is_the_whole_file(self):
        with open(self.part, "wb") as handle:
            handle.write(self.payload)
        server = self.serve()
        se.download_with_resume(
            server.url("/img.zip"),
            self.part,
            None,
            opener=local_opener(),
            sleep=self.sleeps.append,
            log=self.logs.append,
        )
        self.assertEqual(
            server.requests[0], ("/img.zip", "bytes=%d-" % len(self.payload))
        )
        self.assertEqual(server.requests[1], ("/img.zip", None))
        self.assertEqual(self.read_part(), self.payload)

    def test_range_answer_for_the_wrong_offset_is_fatal(self):
        with open(self.part, "wb") as handle:
            handle.write(self.payload[:1000])
        server = self.serve(mode="wrong-range")
        with self.assertRaises(se.DownloadError):
            self.download(server)
        self.assertEqual(len(server.requests), 1, "not retried: the server is broken")

    def test_4xx_is_not_retried(self):
        server = RangeServer({})
        self.addCleanup(server.close)
        with self.assertRaises(se.DownloadError):
            se.download_with_resume(
                server.url("/missing.zip"),
                self.part,
                10,
                opener=local_opener(),
                sleep=self.sleeps.append,
                log=self.logs.append,
            )
        self.assertEqual(len(server.requests), 1)

    def test_attempts_are_bounded(self):
        server = self.serve(cut="always")
        with self.assertRaises(se.DownloadError) as caught:
            self.download(server, attempts=3)
        self.assertEqual(len(server.requests), 3)
        self.assertIn("3번", str(caught.exception))

    def test_connection_refused_is_retried_then_reported(self):
        with self.assertRaises(se.DownloadError):
            se.download_with_resume(
                "http://127.0.0.1:%d/img.zip" % free_port(),
                self.part,
                10,
                opener=local_opener(),
                attempts=2,
                sleep=self.sleeps.append,
                log=self.logs.append,
            )
        self.assertEqual(len(self.sleeps), 1)


class FetchTests(QuietTestCase):
    def test_fetch_bytes_returns_the_body(self):
        server = RangeServer({"/x.xml": b"<a/>"})
        self.addCleanup(server.close)
        self.assertEqual(
            se.fetch_bytes(server.url("/x.xml"), opener=local_opener()), b"<a/>"
        )

    def test_oversized_body_is_refused(self):
        server = RangeServer({"/x.xml": b"x" * 1000})
        self.addCleanup(server.close)
        with self.assertRaises(se.SetupError) as caught:
            se.fetch_bytes(server.url("/x.xml"), opener=local_opener(), max_bytes=100)
        self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
        self.assertIn("비정상적으로 큽니다", str(caught.exception))

    def test_http_errors_and_refused_connections_exit_3(self):
        server = RangeServer({})
        self.addCleanup(server.close)
        for url, needle in (
            (server.url("/missing.xml"), "HTTP 404"),
            ("http://127.0.0.1:%d/x.xml" % free_port(), "받지 못했습니다"),
        ):
            with self.assertRaises(se.SetupError) as caught:
                se.fetch_bytes(url, opener=local_opener())
            self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
            self.assertIn(needle, str(caught.exception))


class EnsureArchiveTests(QuietTestCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cache = os.path.join(self.tmp.name, "cache")
        self.good = payload_bytes(200_000)
        self.bad = bytes(reversed(self.good))  # same size, different content
        self.logs = []

    def image(self, server, payload, **overrides):
        fields = dict(
            path="p",
            display_name="d",
            api_level=34,
            abi="x86_64",
            tag_id="t",
            vendor_id="v",
            license_id="l",
            url=server.url("/img.zip"),
            size=len(payload),
            checksum=sha1(payload),
            checksum_type="sha1",
            host_os="",
        )
        fields.update(overrides)
        return se.RepoImage(**fields)

    def ensure(self, image):
        return se.ensure_archive(
            image,
            self.cache,
            opener=local_opener(),
            sleep=lambda s: None,
            log=self.logs.append,
        )

    def test_download_verify_and_leave_no_part_file(self):
        server = RangeServer({"/img.zip": self.good})
        self.addCleanup(server.close)
        path = self.ensure(self.image(server, self.good))
        self.assertEqual(os.path.basename(path), "img.zip")
        with open(path, "rb") as handle:
            self.assertEqual(handle.read(), self.good)
        self.assertFalse(os.path.exists(path + ".part"))

    def test_cache_hit_skips_the_download(self):
        server = RangeServer({"/img.zip": self.good})
        self.addCleanup(server.close)
        os.makedirs(self.cache)
        with open(os.path.join(self.cache, "img.zip"), "wb") as handle:
            handle.write(self.good)
        self.ensure(self.image(server, self.good))
        self.assertEqual(server.requests, [])

    def test_checksum_mismatch_deletes_and_retries_once(self):
        server = RangeServer({"/img.zip": [self.bad, self.good]})
        self.addCleanup(server.close)
        path = self.ensure(self.image(server, self.good))
        self.assertEqual(len(server.requests), 2)
        with open(path, "rb") as handle:
            self.assertEqual(handle.read(), self.good)
        self.assertTrue(any("체크섬이 맞지 않습니다" in line for line in self.logs))

    def test_second_mismatch_exits_3(self):
        server = RangeServer({"/img.zip": self.bad})
        self.addCleanup(server.close)
        with self.assertRaises(se.SetupError) as caught:
            self.ensure(self.image(server, self.good))
        self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
        self.assertEqual(len(server.requests), 2, "exactly one retry")
        self.assertFalse(os.path.exists(os.path.join(self.cache, "img.zip")))

    def test_unverifiable_checksum_fails_before_downloading(self):
        server = RangeServer({"/img.zip": self.good})
        self.addCleanup(server.close)
        for overrides in ({"checksum_type": "md5"}, {"checksum": ""}):
            with self.assertRaises(se.SetupError) as caught:
                self.ensure(self.image(server, self.good, **overrides))
            self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
        self.assertEqual(server.requests, [])

    def test_cached_file_with_wrong_size_is_replaced(self):
        server = RangeServer({"/img.zip": self.good})
        self.addCleanup(server.close)
        os.makedirs(self.cache)
        with open(os.path.join(self.cache, "img.zip"), "wb") as handle:
            handle.write(b"short")
        path = self.ensure(self.image(server, self.good))
        with open(path, "rb") as handle:
            self.assertEqual(handle.read(), self.good)
        self.assertTrue(any("크기가 달라" in line for line in self.logs))
        self.assertFalse(
            any("체크섬이 맞지 않습니다" in line for line in self.logs),
            "a wrong-size cache file must not spend the one checksum retry",
        )
        self.assertEqual(len(server.requests), 1)


# --------------------------------------------------------------------------- extraction


class ExtractionTests(QuietTestCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.zip_path = os.path.join(self.tmp.name, "image.zip")

    def sparse_copy(self, data):
        source = os.path.join(self.tmp.name, "source.bin")
        target = os.path.join(self.tmp.name, "target.bin")
        with open(source, "wb") as handle:
            handle.write(data)
        with open(source, "rb") as src, open(target, "wb") as dst:
            written = se.copy_sparse(src, dst)
        with open(target, "rb") as handle:
            return handle.read(), written

    def test_copy_sparse_seeks_over_zero_blocks(self):
        data = (
            b"A" * se.BLOCK
            + bytes(3 * se.BLOCK)
            + b"B" * 100
            + bytes(2 * se.BLOCK + 17)
        )
        copied, written = self.sparse_copy(data)
        self.assertEqual(copied, data, "content identical, trailing hole included")
        self.assertEqual(
            written,
            2 * se.BLOCK,
            "a block with any non-zero byte is written whole: the A block and the B block",
        )

    def test_copy_sparse_edge_cases(self):
        self.assertEqual(self.sparse_copy(b""), (b"", 0))
        copied, written = self.sparse_copy(bytes(5 * se.BLOCK))
        self.assertEqual((len(copied), written), (5 * se.BLOCK, 0))
        self.assertEqual(copied, bytes(5 * se.BLOCK))
        copied, written = self.sparse_copy(bytes(2 * se.BLOCK) + b"tail")
        self.assertEqual(copied, bytes(2 * se.BLOCK) + b"tail")
        self.assertEqual(written, 4)

    def test_top_level_folder_detection(self):
        self.assertEqual(se.top_level_folder(["crp/", "crp/a", "crp/b/c"]), "crp")
        self.assertEqual(se.top_level_folder(["crp/a", "__MACOSX/._a", "crp/b"]), "crp")
        self.assertIsNone(se.top_level_folder(["a.txt"]))
        self.assertIsNone(se.top_level_folder(["a/", "b/x"]))
        self.assertIsNone(se.top_level_folder([]))

    def test_safe_join_rejects_escapes(self):
        root = os.path.join(self.tmp.name, "root")
        self.assertEqual(
            se.safe_join(root, "a/b.txt"), os.path.join(root, "a", "b.txt")
        )
        for bad in ("../evil", "a/../../evil", "a/../.."):
            with self.assertRaises(se.SetupError):
                se.safe_join(root, bad)

    def test_extract_strips_the_top_folder_and_keeps_zero_blocks_as_holes(self):
        system_img = build_image_zip(self.zip_path, "x86_64", with_junk=True)
        dest = os.path.join(self.tmp.name, "out")
        stats = se.extract_zip_sparse(self.zip_path, dest, log=lambda m: None)
        self.assertEqual(
            sorted(os.listdir(dest)), ["data", "source.properties", "system.img"]
        )
        self.assertFalse(os.path.exists(os.path.join(dest, "__MACOSX")))
        self.assertEqual(stats.files, 3)
        target = os.path.join(dest, "system.img")
        self.assertEqual(
            os.path.getsize(target),
            len(system_img),
            "trailing hole is truncated to size",
        )
        with open(target, "rb") as handle:
            self.assertEqual(handle.read(), system_img)
        with open(
            os.path.join(dest, "data", "misc", "config.xml"), encoding="utf-8"
        ) as handle:
            self.assertEqual(handle.read(), "<config/>")
        self.assertEqual(
            stats.written_bytes,
            3 * MIB + len("<config/>") + len(SOURCE_PROPERTIES.format(abi="x86_64")),
        )
        if not filesystem_has_holes(self.tmp.name):
            self.skipTest("this filesystem has no sparse-file support")
        allocated = os.stat(target).st_blocks * 512
        self.assertLess(
            allocated, 5 * MIB, "10 of 13 MiB are zero blocks and must stay holes"
        )

    def test_extract_rejects_zip_slip(self):
        with zipfile.ZipFile(self.zip_path, "w") as archive:
            archive.writestr("top/", b"")
            archive.writestr("top/../evil.txt", b"x")
        dest = os.path.join(self.tmp.name, "out")
        with self.assertRaises(se.SetupError):
            se.extract_zip_sparse(self.zip_path, dest, log=lambda m: None)
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "evil.txt")))

    def test_install_image_is_atomic_and_idempotent(self):
        build_image_zip(self.zip_path, "x86_64")
        install_dir = os.path.join(self.tmp.name, "sdk", *se.IMAGE_SUBDIRS, "x86_64")
        self.assertTrue(
            se.install_image(self.zip_path, install_dir, "x86_64", log=lambda m: None)
        )
        self.assertFalse(os.path.exists(install_dir + ".extracting"))
        self.assertTrue(se.image_is_current(install_dir, self.zip_path))
        self.assertFalse(
            se.install_image(self.zip_path, install_dir, "x86_64", log=lambda m: None),
            "same source.properties and file sizes: extraction is skipped",
        )
        self.assertEqual(se.installed_image_problem(install_dir, "x86_64"), None)
        self.assertIn("abi", se.installed_image_problem(install_dir, "arm64-v8a"))

    def test_changed_properties_or_truncated_file_trigger_reextraction(self):
        build_image_zip(self.zip_path, "x86_64")
        install_dir = os.path.join(self.tmp.name, "img")
        se.install_image(self.zip_path, install_dir, "x86_64", log=lambda m: None)
        props = os.path.join(install_dir, "source.properties")
        with open(props, "a", encoding="utf-8") as handle:
            handle.write("Pkg.Revision=2\n")
        self.assertFalse(se.image_is_current(install_dir, self.zip_path))
        self.assertTrue(
            se.install_image(self.zip_path, install_dir, "x86_64", log=lambda m: None)
        )
        with open(os.path.join(install_dir, "system.img"), "r+b") as handle:
            handle.truncate(1000)
        self.assertFalse(se.image_is_current(install_dir, self.zip_path))

    def test_wrong_abi_inside_the_zip_is_refused_and_cleaned_up(self):
        build_image_zip(self.zip_path, "arm64-v8a")
        install_dir = os.path.join(self.tmp.name, "img")
        with self.assertRaises(se.SetupError) as caught:
            se.install_image(self.zip_path, install_dir, "x86_64", log=lambda m: None)
        self.assertEqual(caught.exception.code, se.EXIT_DOWNLOAD)
        self.assertFalse(os.path.exists(install_dir))
        self.assertFalse(os.path.exists(install_dir + ".extracting"))

    def test_zip_without_a_top_folder_extracts_flat(self):
        with zipfile.ZipFile(self.zip_path, "w") as archive:
            archive.writestr(
                "source.properties", SOURCE_PROPERTIES.format(abi="x86_64")
            )
            archive.writestr("system.img", b"abc")
        dest = os.path.join(self.tmp.name, "flat")
        se.extract_zip_sparse(self.zip_path, dest, log=lambda m: None)
        self.assertEqual(sorted(os.listdir(dest)), ["source.properties", "system.img"])


# --------------------------------------------------------------------------- console client


class ConsoleServer:
    """Minimal Vehicle Virtual Console double: records requests, can be told to reject."""

    def __init__(self):
        self.requests = []
        self.reject = None  # (status, body)
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, status, body):
                data = body.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def handle_any(self):
                outer.requests.append((self.command, self.path))
                if outer.reject:
                    self.respond(*outer.reject)
                elif self.path == "/vehicle/state":
                    self.respond(
                        200, json.dumps({"gear": {"actual": {"value": "Park"}}})
                    )
                else:
                    self.respond(200, "ok")

            do_GET = do_PUT = handle_any

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            kwargs={"poll_interval": 0.05},
            daemon=True,
        )
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class ConsoleClientTests(unittest.TestCase):
    def setUp(self):
        self.server = ConsoleServer()
        self.addCleanup(self.server.close)
        self.console = director.Console(self.server.url)

    def test_every_write_is_a_put_with_the_documented_endpoint_and_query(self):
        self.console.set_gear("Drive")
        self.console.set_odometer(45200)
        self.console.set_controls(70, accelerator=45, brake=0, steering=0)
        self.console.set_controls(0, accelerator=0, brake=0)
        self.console.set_seat_occupied("driver", True)
        self.console.set_seat_occupied("rear_left", False)
        self.assertEqual(
            self.server.requests,
            [
                ("PUT", "/vehicle/gear?gear=Drive"),
                ("PUT", "/vehicle/odometer?traveled_distance_km=45200"),
                ("PUT", "/vehicle/controls?speed=70&accelerator=45&brake=0&steering=0"),
                ("PUT", "/vehicle/controls?speed=0&accelerator=0&brake=0"),
                ("PUT", "/vehicle/seat?kind=occupied&value=true&area=driver"),
                ("PUT", "/vehicle/seat?kind=occupied&value=false&area=rear_left"),
            ],
        )

    def test_state_is_parsed_json(self):
        state = self.console.state()
        self.assertEqual(director.gear_of(state), "Park")
        self.assertEqual(self.server.requests, [("GET", "/vehicle/state")])
        self.assertIsNone(director.gear_of({}))
        self.assertIsNone(director.gear_of({"gear": {"actual": None}}))

    def test_non_2xx_raises_with_the_response_text(self):
        self.server.reject = (400, "gear Sport is not supported")
        with self.assertRaises(se.ConsoleError) as caught:
            self.console.set_gear("Drive")
        self.assertIn("HTTP 400", str(caught.exception))
        self.assertIn("gear Sport is not supported", str(caught.exception))
        self.server.reject = (500, "boom")
        with self.assertRaises(se.ConsoleError):
            self.console.state()

    def test_non_json_state_is_an_error(self):
        self.server.reject = (200, "<html>not json</html>")
        with self.assertRaises(se.ConsoleError):
            self.console.state()

    def test_unreachable_console_raises_console_error(self):
        dead = director.Console("http://127.0.0.1:%d" % free_port())
        with self.assertRaises(se.ConsoleError):
            dead.state()

    def test_arguments_are_validated_before_any_request(self):
        for call in (
            lambda: self.console.set_gear("Sport"),
            lambda: self.console.set_odometer(-1),
            lambda: self.console.set_odometer(1000001),
            lambda: self.console.set_odometer(45200.5),
            lambda: self.console.set_controls(50, accelerator=101),
            lambda: self.console.set_controls(50, brake=-1),
            lambda: self.console.set_seat_occupied("middle", True),
        ):
            with self.assertRaises(ValueError):
                call()
        self.assertEqual(self.server.requests, [])

    def test_wait_for_console_returns_on_200_and_times_out_with_exit_5(self):
        with mock.patch.object(
            se, "CONSOLE_HOST_PORT", int(self.server.url.rsplit(":", 1)[1])
        ):
            se.wait_for_console(5.0, sleep=lambda s: None)
        clock = iter(range(0, 1000, 100))
        with mock.patch.object(se, "CONSOLE_HOST_PORT", free_port()):
            with self.assertRaises(se.SetupError) as caught:
                se.wait_for_console(150, now=lambda: next(clock), sleep=lambda s: None)
        self.assertEqual(caught.exception.code, se.EXIT_CONSOLE)


# --------------------------------------------------------------------------- scenario plans


def steps_of(beats, kind=None):
    flat = [step for beat in beats for step in beat.steps]
    return [s for s in flat if isinstance(s, kind)] if kind else flat


class ScenarioPlanTests(unittest.TestCase):
    EXPECTED = {
        "commute": (
            45200,
            45218,
            "COMMUTE",
            "집",
            "회사",
            (37.5444, 127.0374),
            (37.3947, 127.1112),
        ),
        "client": (
            45218,
            45229,
            "BUSINESS",
            "회사",
            "가상상사",
            (37.3947, 127.1112),
            (37.5126, 127.0590),
        ),
        "personal": (
            45229,
            45236,
            "UNCLASSIFIED",
            "가상상사",
            "미등록 장소",
            (37.5126, 127.0590),
            (37.5665, 126.9780),
        ),
    }

    def test_scenarios_match_the_specification(self):
        self.assertEqual(
            [s.key for s in director.SCENARIOS], ["commute", "client", "personal"]
        )
        for scenario in director.SCENARIOS:
            start, end, purpose, origin, dest, origin_xy, dest_xy = self.EXPECTED[
                scenario.key
            ]
            with self.subTest(scenario.key):
                self.assertEqual(scenario.odometer_start, start)
                self.assertEqual(scenario.odometer_end, end)
                self.assertEqual(scenario.expected_purpose, purpose)
                self.assertEqual(
                    (scenario.origin.name, scenario.destination.name), (origin, dest)
                )
                self.assertEqual((scenario.origin.lat, scenario.origin.lon), origin_xy)
                self.assertEqual(
                    (scenario.destination.lat, scenario.destination.lon), dest_xy
                )

    def test_odometers_continue_from_one_scenario_to_the_next(self):
        scenarios = director.SCENARIOS
        for before, after in zip(scenarios, scenarios[1:]):
            self.assertEqual(before.odometer_end, after.odometer_start)
            self.assertEqual(before.destination, after.origin)

    def test_all_selects_every_scenario_in_order(self):
        self.assertEqual(
            [s.key for s in director.select_scenarios("all")],
            ["commute", "client", "personal"],
        )
        self.assertEqual(
            [s.key for s in director.select_scenarios("client")], ["client"]
        )
        with self.assertRaises(ValueError):
            director.select_scenarios("nope")

    def test_odometer_runs_from_start_to_end_in_one_km_steps(self):
        for scenario in director.SCENARIOS:
            with self.subTest(scenario.key):
                odometers = [
                    s.km
                    for s in steps_of(
                        director.plan_beats(scenario), director.SetOdometer
                    )
                ]
                self.assertEqual(
                    odometers,
                    list(range(scenario.odometer_start, scenario.odometer_end + 1)),
                )

    def test_gear_and_odometer_order_gives_the_expected_trip(self):
        for scenario in director.SCENARIOS:
            with self.subTest(scenario.key):
                steps = steps_of(director.plan_beats(scenario))
                self.assertEqual(
                    [s.gear for s in steps if isinstance(s, director.SetGear)],
                    ["Drive"],
                )
                self.assertEqual(
                    sum(isinstance(s, director.ParkVehicle) for s in steps), 2
                )
                start_at = steps.index(director.SetOdometer(scenario.odometer_start))
                drive_at = steps.index(director.SetGear("Drive"))
                end_at = steps.index(director.SetOdometer(scenario.odometer_end))
                last_park = max(
                    i
                    for i, s in enumerate(steps)
                    if isinstance(s, director.ParkVehicle)
                )
                self.assertLess(
                    start_at, drive_at, "odometer is set while parked, before leaving P"
                )
                self.assertLess(drive_at, end_at)
                self.assertLess(
                    end_at, last_park, "the trip closes only after the last km"
                )
                self.assertTrue(
                    any(
                        s == director.SetSeat("driver", True) for s in steps[:start_at]
                    ),
                    "driver seat occupied before the drive",
                )

    def test_geo_fixes_walk_from_origin_to_destination(self):
        for scenario in director.SCENARIOS:
            with self.subTest(scenario.key):
                beats = director.plan_beats(scenario)
                fixes = steps_of(beats, director.GeoFix)
                self.assertEqual(
                    fixes[0], director.GeoFix(scenario.origin.lat, scenario.origin.lon)
                )
                self.assertEqual(
                    fixes[-1],
                    director.GeoFix(scenario.destination.lat, scenario.destination.lon),
                )
                ticks = steps_of(
                    [b for b in beats if b.name == "drive"], director.GeoFix
                )
                self.assertEqual(len(ticks), scenario.km)
                lats = [f.lat for f in ticks]
                direction = 1 if scenario.destination.lat > scenario.origin.lat else -1
                self.assertEqual(lats, sorted(lats, key=lambda v: direction * v))
                low, high = sorted((scenario.origin.lat, scenario.destination.lat))
                self.assertTrue(
                    all(low < lat < high for lat in lats),
                    "strictly between the two places",
                )

    def test_cruise_speed_jitter_stays_in_55_to_80(self):
        for scenario in director.SCENARIOS:
            with self.subTest(scenario.key):
                drive = [b for b in director.plan_beats(scenario) if b.name == "drive"]
                speeds = [s.speed for s in steps_of(drive, director.SetControls)]
                self.assertEqual(len(speeds), scenario.km)
                self.assertTrue(all(55 <= s <= 80 for s in speeds), speeds)
        self.assertGreater(len(set(director.jitter_speeds("commute", 18))), 1)

    def test_plans_are_deterministic(self):
        first = director.plan_beats(director.SCENARIOS[0], 1.2)
        second = director.plan_beats(director.SCENARIOS[0], 1.2)
        self.assertEqual(first, second)

    def test_speed_ramps(self):
        scenario = director.SCENARIOS[0]
        beats = {b.name: b for b in director.plan_beats(scenario)}
        up = [s.speed for s in steps_of([beats["depart"]], director.SetControls)]
        self.assertEqual(up, sorted(up))
        self.assertEqual(up[-1], 70)
        self.assertEqual(len(up), 8)
        up_time = sum(p.seconds for p in steps_of([beats["depart"]], director.Pause))
        self.assertAlmostEqual(up_time, 4.0)
        down = [s.speed for s in steps_of([beats["arrive"]], director.SetControls)]
        self.assertEqual(down, sorted(down, reverse=True))
        self.assertEqual(down[-1], 0)
        self.assertEqual(director.ramp_speeds(0, 70, 8)[0], 9)

    def test_fast_mode_keeps_the_shape_and_scales_the_time(self):
        scenario = director.SCENARIOS[1]
        slow = steps_of(director.plan_beats(scenario, 1.2))
        fast = steps_of(director.plan_beats(scenario, director.FAST_KM_INTERVAL))
        self.assertEqual(len(slow), len(fast))
        for a, b in zip(slow, fast):
            if isinstance(a, director.Pause):
                self.assertAlmostEqual(b.seconds, a.seconds * 0.25)
            else:
                self.assertEqual(a, b)
        ticks = [
            s.seconds
            for s in steps_of(director.plan_beats(scenario, 0.3), director.Pause)
        ]
        self.assertIn(0.3, ticks)

    def test_beats_and_narration(self):
        beats = director.plan_beats(director.SCENARIOS[0])
        self.assertEqual(
            [b.name for b in beats], ["ready", "depart", "drive", "arrive", "park"]
        )
        self.assertEqual([b.prompt for b in beats], [False, True, True, True, True])
        self.assertEqual(beats[1].narration, "① 집에서 출발합니다: 기어 D")
        self.assertIn("45,200", beats[0].narration)
        self.assertIn("45,218", beats[2].narration)
        self.assertIn("출퇴근", beats[4].narration)
        for scenario in director.SCENARIOS:
            for beat in director.plan_beats(scenario):
                self.assertNotIn("{", beat.narration)
                self.assertEqual(beat.narration.count("\n"), 0, "one line per beat")

    def test_screenshot_names(self):
        self.assertEqual(
            director.screenshot_name(3, "commute", "drive"), "03_commute_drive.png"
        )
        self.assertEqual(
            director.screenshot_name(12, "personal", "park"), "12_personal_park.png"
        )

    def test_forward_ports_only_for_loopback_consoles(self):
        self.assertEqual(director.forward_ports("http://127.0.0.1:8088"), (8088, 8088))
        self.assertEqual(director.forward_ports("http://localhost:9000/"), (9000, 8088))
        self.assertIsNone(director.forward_ports("http://192.168.0.5:8088"))


# --------------------------------------------------------------------------- logcat matching


class LogcatTests(unittest.TestCase):
    def scenario(self, key="commute"):
        return director.select_scenarios(key)[0]

    def test_parse_trip_closed_line(self):
        events = director.trip_closed_events(LOGCAT_OK)
        self.assertEqual(len(events), 1)
        trip = events[0]
        self.assertEqual(
            (trip.start, trip.end, trip.km, trip.purpose),
            (45200.0, 45218.0, 18.0, "COMMUTE"),
        )
        self.assertTrue(trip.trip_id.startswith("TRIP-"))

    def test_positive_match(self):
        events = director.trip_closed_events(LOGCAT_OK)
        self.assertIsNotNone(
            director.find_expected_trip(events, self.scenario("commute"))
        )

    def test_negative_matches(self):
        commute = self.scenario("commute")
        base = "AutoBriefLog: TRIP_CLOSED id=TRIP-1 start=%s end=%s km=18.0 purpose=%s by=자동 reason=x\n"
        cases = {
            "wrong end": base % ("45200.0", "45219.0", "COMMUTE"),
            "wrong start": base % ("45201.0", "45218.0", "COMMUTE"),
            "wrong purpose": base % ("45200.0", "45218.0", "BUSINESS"),
            "unclassified instead": base % ("45200.0", "45218.0", "UNCLASSIFIED"),
        }
        for label, text in cases.items():
            with self.subTest(label):
                events = director.trip_closed_events(text)
                self.assertEqual(len(events), 1)
                self.assertIsNone(director.find_expected_trip(events, commute))

    def test_lines_that_are_not_trip_closed_never_match(self):
        text = (
            "AutoBriefLog: TRIP_OPENED odo=45200.0\n"
            "AutoBriefLog: TRIP_CLOSED_REJECTED start=45200.0 end=45218.0 purpose=COMMUTE\n"
            "AutoBriefLog: TRIP_CLOSED id=x end=45218.0 purpose=COMMUTE\n"  # no start
            "AutoBriefLog: TRIP_CLOSED id=x start=abc end=45218.0 purpose=COMMUTE\n"
            "AutoBriefLog: LEDGER seq=12 kind=TRIP hash=abcd1234\n"
            "\n"
        )
        self.assertEqual(director.trip_closed_events(text), [])
        self.assertIsNone(director.find_expected_trip([], self.scenario()))

    def test_picks_the_matching_line_among_several(self):
        text = LOGCAT_OK + (
            "AutoBriefLog: TRIP_CLOSED id=TRIP-2 start=45218.0 end=45229.0 km=11.0 purpose=BUSINESS by=자동 reason=y\n"
        )
        events = director.trip_closed_events(text)
        self.assertEqual(len(events), 2)
        for key, purpose in (("commute", "COMMUTE"), ("client", "BUSINESS")):
            hit = director.find_expected_trip(events, self.scenario(key))
            self.assertEqual(hit.purpose, purpose)
        self.assertIsNone(
            director.find_expected_trip(events, self.scenario("personal"))
        )

    def test_tolerance_is_tight(self):
        trip = director.parse_trip_closed(
            "TRIP_CLOSED start=45200.04 end=45218.0 purpose=COMMUTE"
        )
        self.assertTrue(director.matches_expected(trip, self.scenario()))
        trip = director.parse_trip_closed(
            "TRIP_CLOSED start=45200.2 end=45218.0 purpose=COMMUTE"
        )
        self.assertFalse(director.matches_expected(trip, self.scenario()))

    def test_expectation_text(self):
        self.assertEqual(
            director.expectation_text(self.scenario("client")),
            "start=45218.0 end=45229.0 purpose=BUSINESS",
        )


class FakeLogAdb:
    """Only the logcat side of adb, for verify_scenario."""

    def __init__(self, dumps):
        self.dumps = list(dumps)
        self.calls = 0

    def logcat_dump(self, *args, **kwargs):
        self.calls += 1
        return self.dumps[min(self.calls, len(self.dumps)) - 1]


class VerifyTests(unittest.TestCase):
    def test_pass_on_first_poll(self):
        adb = FakeLogAdb([LOGCAT_OK])
        verdict = director.verify_scenario(
            adb, director.SCENARIOS[0], sleep=lambda s: None
        )
        self.assertTrue(verdict.ok)
        self.assertEqual(adb.calls, 1)

    def test_waits_for_the_line_to_appear(self):
        adb = FakeLogAdb(["", "AutoBriefLog: TRIP_OPENED odo=45200.0\n", LOGCAT_OK])
        sleeps = []
        verdict = director.verify_scenario(
            adb, director.SCENARIOS[0], sleep=sleeps.append
        )
        self.assertTrue(verdict.ok)
        self.assertEqual(len(sleeps), 2)

    def test_fails_after_the_timeout_and_reports_what_it_saw(self):
        adb = FakeLogAdb([LOGCAT_OK])
        clock = [0.0]

        def sleep(seconds):
            clock[0] += seconds

        verdict = director.verify_scenario(
            adb, director.SCENARIOS[1], timeout=20, now=lambda: clock[0], sleep=sleep
        )
        self.assertFalse(verdict.ok)
        self.assertIsNone(verdict.trip)
        self.assertEqual(len(verdict.seen), 1)
        self.assertGreaterEqual(clock[0], 20)
        self.assertLessEqual(adb.calls, 22)


# --------------------------------------------------------------------------- executor with doubles


class FakeConsole:
    def __init__(self):
        self.calls = []
        self.gear = "Park"
        self.stuck = False

    def set_gear(self, gear):
        self.calls.append(("gear", gear))
        if not self.stuck:
            self.gear = gear

    def set_odometer(self, km):
        self.calls.append(("odometer", km))

    def set_controls(self, speed, accelerator=None, brake=None, steering=None):
        self.calls.append(("controls", speed, accelerator, brake, steering))

    def set_seat_occupied(self, area, occupied):
        self.calls.append(("seat", area, occupied))

    def state(self):
        return {"gear": {"actual": {"value": self.gear}}}


def place_label_at(lat, lon):
    """What the app logs for a position: the registered demo place within 300 m, else '-'."""
    for place in (director.HOME, director.OFFICE, director.CLIENT):
        # 0.0025 degrees is about 280 m north-south here; enough for fixes placed on the pins
        if abs(place.lat - lat) < 0.0025 and abs(place.lon - lon) < 0.0025:
            return place.name
    return "-"


class FakeAdb:
    """The emulator side of Director: geo fixes, NMEA, screenshots, the screen (which app is in
    front) and the app's POSITION log. Like Pleos Connect, the navigation engine follows the NMEA
    only while the map is in front, and the app reports the engine's position (unless
    `app_silent`)."""

    APP = "app.autobrief.car/.ui.MainActivity"
    NAV = "ai.umos.maps.android.navigation.app/.MainActivity"

    def __init__(self, png=PNG):
        self.geo = []
        self.nmea = []
        self.png = png
        self.geo_error = None
        self.nmea_error = None
        self.shot_error = None
        self.app_silent = False
        self.position = None  # (lat, lon) the navigation engine holds
        self.front = self.APP
        self.nav_follows_gps = True  # False: the map never takes the GPS (an image without it)
        self.platform_gps = False  # True: the app takes the emulator's GPS itself (granted location)
        self.shells = []
        self.dumps = 0

    def shell(self, *args, timeout=None):
        line = " ".join(args)
        self.shells.append(line)
        if line == "am get-current-user":
            return "10\n"
        if line.startswith("am start") and "-p ai.umos.maps.android.navigation.app" in line:
            self.front = self.NAV
        elif line.startswith("am start") and "-n app.autobrief.car/.ui.MainActivity" in line:
            self.front = self.APP
        return "Status: ok\n"

    def activities(self):
        return "  topResumedActivity=ActivityRecord{1 u10 %s t1}\n" % self.front

    def logcat_dump(self, *args, **kwargs):
        self.dumps += 1
        if self.app_silent:
            return ""
        if self.platform_gps and self.geo:
            lon, lat = self.geo[-1]  # the platform GNSS follows every injected fix, map or not
            return "I AutoBriefLog: POSITION place=%s source=gps\n" % place_label_at(lat, lon)
        if self.position is None:
            return ""
        return "I AutoBriefLog: POSITION place=%s\n" % place_label_at(*self.position)

    def geo_fix(self, lon, lat):
        if self.geo_error:
            raise self.geo_error
        self.geo.append((lon, lat))

    def geo_nmea(self, sentence):
        if self.nmea_error:
            raise self.nmea_error
        self.nmea.append(sentence)
        fields = sentence.split(",")
        on_screen = self.front == self.NAV and self.nav_follows_gps
        if fields[0] == "$GPGGA" and on_screen:  # the map reads the NMEA only while it is in front
            self.position = (
                int(fields[2][:2]) + float(fields[2][2:]) / 60,
                int(fields[4][:3]) + float(fields[4][3:]) / 60,
            )

    def screencap_png(self):
        if self.shot_error:
            raise self.shot_error
        return self.png


class DirectorExecutorTests(unittest.TestCase):
    def make(self, mode="auto", out_dir=None, **kwargs):
        self.console, self.adb = FakeConsole(), kwargs.pop("adb", None) or FakeAdb()
        self.said, self.sleeps, self.asked = [], [], []
        return director.Director(
            self.console,
            self.adb,
            out_dir=out_dir,
            mode=mode,
            km_interval=0.3,
            sleep=self.sleeps.append,
            clock=lambda: sum(self.sleeps),
            ask=lambda prompt: self.asked.append(prompt) or "",
            say_fn=self.said.append,
        )

    def test_every_position_also_goes_out_as_valid_nmea(self):
        runner = self.make()
        runner.utc = lambda: FIX_TIME
        runner.run_scenario(director.SCENARIOS[0])
        self.assertEqual(len(self.adb.nmea), 2 * len(self.adb.geo))
        first_gga, first_rmc = self.adb.nmea[:2]
        self.assertTrue(first_gga.startswith("$GPGGA,052019,3732.6640,N,12702.2440,E,"))
        self.assertTrue(first_rmc.startswith("$GPRMC,052019,A,3732.6640,N,12702.2440,E,"))
        last_rmc = self.adb.nmea[-1].split("*")[0].split(",")
        self.assertEqual(last_rmc[3:7], ["3723.6820", "N", "12706.6720", "E"])
        course = float(last_rmc[8])
        self.assertTrue(90.0 < course < 180.0, "home -> office heads south-east: %s" % course)

    def test_departure_and_parking_wait_until_the_app_has_seen_the_place(self):
        steps = director.plan_beats(director.SCENARIOS[1], 0.3)
        ready, drive, arrive, park = (steps[i].steps for i in (0, 2, 3, 4))
        self.assertIsInstance(ready[0], director.GeoFix)
        self.assertIsInstance(ready[1], director.ParkVehicle)
        self.assertEqual(
            ready[-2:],
            (
                director.AwaitPosition(director.OFFICE, director.POSITION_WAIT_PARKED_S),
                director.ShowApp(),
            ),
            "the app has seen the origin, then its screen is shown for the departure",
        )
        self.assertEqual(drive[0], director.ShowNavigation(), "the map follows the drive")
        self.assertEqual(
            arrive[-1], director.AwaitPosition(director.CLIENT, director.POSITION_WAIT_DRIVING_S)
        )
        self.assertEqual(park[:2], (director.ParkVehicle(), director.ShowApp()))
        self.assertEqual(director.UNREGISTERED.app_label, "-")
        self.assertEqual(director.CLIENT.app_label, "가상상사")

    def test_the_map_learns_the_position_and_the_app_shows_the_trip(self):
        runner = self.make()
        screens = []
        real_shell = self.adb.shell

        def shell(*args, **kwargs):
            reply = real_shell(*args, **kwargs)
            screens.append(self.adb.front)
            return reply

        self.adb.shell = shell
        runner.run_scenario(director.SCENARIOS[0])
        starts = [s for s in self.adb.shells if s.startswith("am start")]
        nav = "-p ai.umos.maps.android.navigation.app"
        # ready: the app has not seen 집 -> map; then the app; drive: map; park: the app
        self.assertEqual(
            ["nav" if nav in s else "app" for s in starts], ["nav", "app", "nav", "app"]
        )
        self.assertEqual(self.adb.front, FakeAdb.APP, "the closed trip is on screen")
        self.assertIn("  - 앱이 위치를 확인했습니다: 집", self.said)
        self.assertIn("  - 앱이 위치를 확인했습니다: 회사", self.said)
        self.assertFalse(any("확인하지 못했습니다" in m for m in self.said))

    def test_a_map_that_never_takes_the_gps_costs_one_wait_not_the_demo(self):
        runner = self.make()
        self.adb.nav_follows_gps = False
        runner.run_scenario(director.SCENARIOS[1])
        runner.run_scenario(director.SCENARIOS[2])
        self.assertEqual(sum("확인하지 못했습니다" in m for m in self.said), 1)
        self.assertEqual(sum("GPS를 따라가지 않습니다" in m for m in self.said), 1)
        # one poll per second of the single wait, not four waits' worth (plus one look per drive beat
        # at whether the app has its own GPS)
        self.assertLessEqual(self.adb.dumps, director.POSITION_WAIT_PARKED_S + 2 + 2)
        self.assertEqual(self.console.calls[-1], ("gear", "Park"))

    def test_on_its_own_gps_the_app_stays_in_front_and_the_map_never_starts(self):
        # the Pleos Connect emulator: the map drops the injected GPS, the app's platform fix follows it
        runner = self.make()
        self.adb.nav_follows_gps, self.adb.platform_gps = False, True
        runner.run_scenario(director.SCENARIOS[0])
        runner.run_scenario(director.SCENARIOS[1])
        self.assertFalse([s for s in self.adb.shells if s.startswith("am start") and director.NAV_PACKAGE in s])
        self.assertEqual(self.adb.front, FakeAdb.APP)
        for place in ("집", "회사", "가상상사"):
            self.assertIn("  - 앱이 위치를 확인했습니다: %s" % place, self.said)
        self.assertEqual(sum("지도 화면 없이 진행합니다" in m for m in self.said), 1)
        self.assertFalse(any("확인하지 못했습니다" in m for m in self.said))
        self.assertEqual(self.console.calls[-1], ("gear", "Park"))

    def test_the_map_comes_only_once_the_grace_period_is_over(self):
        runner = self.make()
        self.adb.app_silent = True
        started = []
        shell = self.adb.shell

        def recording_shell(*args, **kwargs):
            if args[:2] == ("am", "start"):
                started.append(sum(self.sleeps))
            return shell(*args, **kwargs)

        self.adb.shell = recording_shell
        runner.await_position(director.AwaitPosition(director.HOME, 30.0))
        self.assertEqual(len(started), 1)
        self.assertGreaterEqual(started[0], director.MAP_GRACE_S)

    def test_app_on_own_gps_reads_the_latest_position_line(self):
        self.assertTrue(director.app_on_own_gps("I AutoBriefLog: POSITION place=집 source=gps\n"))
        self.assertFalse(director.app_on_own_gps("I AutoBriefLog: POSITION place=집 source=gps\nI AutoBriefLog: POSITION place=-\n"))
        self.assertFalse(director.app_on_own_gps("I AutoBriefLog: SPACE DEMO\n"))

    def test_a_map_that_does_not_start_is_reported_at_once(self):
        runner = self.make()
        self.adb.shell = lambda *a, **k: (
            "10\n" if a[:2] == ("am", "get-current-user")
            else "Error: Activity not started, unable to resolve Intent\n"
        )
        self.assertFalse(runner.show_navigation())
        self.assertEqual(self.sleeps, [], "no waiting for a map that was never started")
        self.assertTrue(any("unable to resolve" in m for m in self.said))

    def test_map_already_in_front_is_not_started_again(self):
        runner = self.make()
        self.adb.front = FakeAdb.NAV
        self.assertTrue(runner.show_navigation())
        self.assertFalse(any(s.startswith("am start") for s in self.adb.shells))

    def test_position_wait_resends_the_fix_until_the_app_reports_it(self):
        runner = self.make()
        self.adb.position = (37.0, 127.0)  # the app last saw the car elsewhere
        reports = iter(["", "POSITION place=-", "POSITION place=회사"])
        self.adb.logcat_dump = lambda *a, **k: next(reports)
        runner.await_position(director.AwaitPosition(director.OFFICE, 15.0))
        self.assertEqual(len(self.adb.geo), 2, "re-sent once a second while waiting")
        self.assertEqual(self.sleeps, [1.0, 1.0])
        self.assertIn("  - 앱이 위치를 확인했습니다: 회사", self.said)

    def test_position_wait_gives_up_with_a_warning(self):
        runner = self.make()
        self.adb.app_silent = True
        runner.await_position(director.AwaitPosition(director.HOME, 5.0))
        self.assertEqual(sum(self.sleeps), 5.0)
        self.assertTrue(any("확인하지 못했습니다" in m and "없음" in m for m in self.said))

    def test_last_position_label(self):
        text = "I AutoBriefLog: POSITION place=집\nI AutoBriefLog: TRIP_OPENED odo=1.0\nI AutoBriefLog: POSITION place=-\n"
        self.assertEqual(director.last_position_label(text), "-")
        self.assertIsNone(director.last_position_label("I AutoBriefLog: SPACE DEMO\n"))

    def test_nmea_refusal_warns_once_and_geo_fix_goes_on(self):
        runner = self.make()
        self.adb.nmea_error = se.AdbError("geo nmea 거부됨: KO: unknown command")
        runner.run_scenario(director.SCENARIOS[0])
        warnings = [m for m in self.said if "NMEA" in m]
        self.assertEqual(len(warnings), 1)
        self.assertEqual(self.adb.geo[-1], (127.1112, 37.3947))

    def test_commute_replay_hits_the_console_in_trip_order(self):
        runner = self.make()
        runner.run_scenario(director.SCENARIOS[0])
        gears = [c[1] for c in self.console.calls if c[0] == "gear"]
        self.assertEqual(gears, ["Park", "Drive", "Park"])
        odometers = [c[1] for c in self.console.calls if c[0] == "odometer"]
        self.assertEqual(odometers, list(range(45200, 45219)))
        self.assertEqual(
            self.console.calls[0], ("controls", 0, 0, 0, None), "starts by parking"
        )
        self.assertEqual(self.console.calls[-1], ("gear", "Park"))
        self.assertIn(("seat", "driver", True), self.console.calls)
        self.assertEqual(
            self.adb.geo[0],
            (127.0374, 37.5444),
            "adb emu geo fix takes longitude first",
        )
        self.assertEqual(self.adb.geo[-1], (127.1112, 37.3947))
        self.assertIn("① 집에서 출발합니다: 기어 D", self.said)
        self.assertGreater(sum(self.sleeps), 0)
        self.assertEqual(self.asked, [], "auto mode never waits for Enter")

    def test_step_mode_waits_before_every_beat_except_the_staging_one(self):
        runner = self.make(mode="step")
        runner.run_scenario(director.SCENARIOS[2])
        self.assertEqual(len(self.asked), 4)
        self.assertEqual(self.console.calls[-1], ("gear", "Park"))

    def test_screenshots_are_saved_per_beat(self):
        with tempfile.TemporaryDirectory() as out:
            runner = self.make(out_dir=os.path.join(out, "shots"))
            runner.run_scenario(director.SCENARIOS[0])
            runner.run_scenario(director.SCENARIOS[1])
            names = sorted(os.listdir(os.path.join(out, "shots")))
            self.assertEqual(len(names), 10)
            self.assertEqual(names[0], "01_commute_ready.png")
            self.assertIn("03_commute_drive.png", names)
            self.assertEqual(names[-1], "10_client_park.png")
            with open(os.path.join(out, "shots", names[0]), "rb") as handle:
                self.assertEqual(handle.read(), PNG)

    def test_no_screenshots_without_out(self):
        runner = self.make()
        runner.run_scenario(director.SCENARIOS[0])
        self.assertEqual(runner.beat_number, 5)

    def test_screenshot_failure_warns_once_and_the_demo_continues(self):
        adb = FakeAdb()
        adb.shot_error = se.AdbError("no display")
        with tempfile.TemporaryDirectory() as out:
            runner = self.make(out_dir=out, adb=adb)
            runner.run_scenario(director.SCENARIOS[0])
            self.assertEqual(os.listdir(out), [])
        self.assertEqual(sum("스크린샷을 건너뜁니다" in line for line in self.said), 1)
        self.assertEqual(self.console.calls[-1], ("gear", "Park"))

    def test_non_png_screencap_is_not_saved(self):
        with tempfile.TemporaryDirectory() as out:
            runner = self.make(out_dir=out, adb=FakeAdb(png=b"<html>"))
            runner.run_scenario(director.SCENARIOS[0])
            self.assertEqual(os.listdir(out), [])
        self.assertTrue(any("PNG" in line for line in self.said))

    def test_geo_failure_warns_once_and_the_demo_continues(self):
        adb = FakeAdb()
        adb.geo_error = se.AdbError("KO: no gps")
        runner = self.make(adb=adb)
        runner.run_scenario(director.SCENARIOS[0])
        self.assertEqual(sum("위치 이동을 건너뜁니다" in line for line in self.said), 1)
        self.assertEqual(
            [c[1] for c in self.console.calls if c[0] == "odometer"][-1], 45218
        )

    def test_gear_that_never_changes_is_an_error(self):
        runner = self.make()
        self.console.stuck = True
        ticks = [0.0]
        runner.clock = lambda: ticks[0]
        runner.sleep = lambda s: ticks.__setitem__(0, ticks[0] + 4)
        with self.assertRaises(se.ConsoleError) as caught:
            runner.confirm_gear("Drive")
        self.assertIn("Drive", str(caught.exception))

    def test_unknown_step_is_rejected(self):
        runner = self.make()
        with self.assertRaises(TypeError):
            runner.run_step(object())


# --------------------------------------------------------------------------- CLI


class CliTests(unittest.TestCase):
    def help_text(self, parser):
        buffer = io.StringIO()
        with (
            contextlib.redirect_stdout(buffer),
            self.assertRaises(SystemExit) as caught,
        ):
            parser.parse_args(["--help"])
        self.assertEqual(caught.exception.code, 0)
        return buffer.getvalue()

    def test_help_runs_and_lists_every_option(self):
        text = self.help_text(se.build_parser())
        for option in (
            "--apk",
            "--headless",
            "--no-browser",
            "--cache",
            "--out",
            "--skip-image",
            "--demo-sample",
            "--webhook",
        ):
            self.assertIn(option, text)
        text = self.help_text(director.build_parser())
        for option in (
            "--scenario",
            "--mode",
            "--km-interval",
            "--fast",
            "--out",
            "--verify",
            "--serial",
            "--console",
        ):
            self.assertIn(option, text)
        self.assertIn("emulator-5554", text)
        self.assertIn("http://127.0.0.1:8088", text)

    def test_director_defaults(self):
        args = director.build_parser().parse_args(["--scenario", "all"])
        self.assertEqual(
            (args.mode, args.km_interval, args.fast, args.verify),
            ("auto", 1.2, False, False),
        )
        self.assertEqual(
            (args.serial, args.console, args.out),
            ("emulator-5554", "http://127.0.0.1:8088", None),
        )

    def test_director_rejects_unknown_scenario_and_bad_interval(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                director.main(["--scenario", "bogus"])
            self.assertEqual(caught.exception.code, 2)
            with self.assertRaises(SystemExit) as caught:
                director.main(["--scenario", "all", "--km-interval", "0"])
            self.assertEqual(caught.exception.code, 2)

    def test_setup_rejects_webhook_without_demo_sample_and_missing_apk(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                se.main(["--webhook", "http://h/x"])
            self.assertEqual(caught.exception.code, 2)
            with self.assertRaises(SystemExit) as caught:
                se.main(["--apk", "/definitely/not/here.apk"])
            self.assertEqual(caught.exception.code, 2)

    def test_adb_that_cannot_be_started_is_an_adb_error(self):
        with self.assertRaises(se.AdbError):
            se.Adb("/definitely/not/adb", serial="emulator-5554").devices()


# --------------------------------------------------------------------------- session, retry, bridge

BASE = "http://console.test:8088"
SESSION_URL = BASE + "/browser/session?client=autobrief-director"
CLOSING_URL = SESSION_URL + "&closing=1"


class RecordingRequest:
    """A fake HTTP layer for Console/SessionKeeper: records (method, url), answers from a script.

    script items are returned as the response text, or raised when they are exceptions;
    once the script is used up `default` answers.
    """

    def __init__(self, script=None, default="ok"):
        self.calls = []
        self.script = list(script or [])
        self.default = default
        self.changed = threading.Condition()

    def __call__(self, method, url, timeout=10.0):
        with self.changed:
            self.calls.append((method, url))
            outcome = self.script.pop(0) if self.script else self.default
            self.changed.notify_all()
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def wait_for(self, count, seconds=5.0):
        with self.changed:
            return self.changed.wait_for(
                lambda: len(self.calls) >= count, timeout=seconds
            )

    def closings(self):
        return [url for _, url in self.calls if url.endswith("closing=1")]


def busy_503():
    return se.ConsoleError(
        "PUT -> HTTP 503", status=503, body='{"error":"emulator_inactive"}'
    )


class SessionKeeperTests(unittest.TestCase):
    def keeper(self, request, **kwargs):
        self.said = []
        keeper = director.SessionKeeper(
            BASE, interval=0.01, request=request, say_fn=self.said.append, **kwargs
        )
        self.addCleanup(keeper.stop)
        return keeper

    def test_defaults_follow_the_console_page(self):
        keeper = director.SessionKeeper(BASE)
        self.assertEqual(keeper.client, "autobrief-director")
        self.assertEqual(keeper.interval, 1.0)
        self.assertEqual(keeper.url, BASE + "/browser/session")

    def test_start_sends_the_first_beat_before_it_returns(self):
        request = RecordingRequest()
        self.keeper(request).start()
        self.assertEqual(request.calls[0], ("POST", SESSION_URL))

    def test_it_keeps_beating_until_stopped(self):
        request = RecordingRequest()
        keeper = self.keeper(request)
        keeper.start()
        self.assertTrue(request.wait_for(4), "at least three beats within 5 s")
        keeper.stop()
        beats = request.calls[:-1]
        self.assertGreaterEqual(len(beats), 3)
        self.assertTrue(all(call == ("POST", SESSION_URL) for call in beats))
        self.assertGreaterEqual(keeper.beats, 3)

    def test_stop_sends_the_closing_call_last_and_nothing_after_it(self):
        request = RecordingRequest()
        keeper = self.keeper(request)
        keeper.start()
        self.assertTrue(request.wait_for(2))
        keeper.stop()
        count = len(request.calls)
        time.sleep(0.1)
        self.assertEqual(len(request.calls), count, "no heartbeat after stop()")
        self.assertEqual(request.calls[-1], ("POST", CLOSING_URL))
        self.assertEqual(request.closings(), [CLOSING_URL])

    def test_stop_is_safe_before_start_and_idempotent(self):
        request = RecordingRequest()
        keeper = self.keeper(request)
        keeper.stop()
        self.assertEqual(request.calls, [], "nothing to close if it never started")
        keeper.start()
        keeper.stop()
        keeper.stop()
        self.assertEqual(len(request.closings()), 1)

    def test_start_twice_does_not_start_a_second_thread(self):
        request = RecordingRequest()
        keeper = self.keeper(request)
        keeper.start()
        keeper.start()
        keeper.stop()
        self.assertEqual(len(request.closings()), 1)

    def test_context_manager_closes_the_session_when_the_body_raises(self):
        request = RecordingRequest()
        with self.assertRaises(RuntimeError):
            with director.SessionKeeper(
                BASE, interval=0.01, request=request, say_fn=lambda m: None
            ):
                raise RuntimeError("boom")
        self.assertEqual(request.calls[-1], ("POST", CLOSING_URL))

    def test_a_failed_beat_does_not_stop_the_heartbeat(self):
        request = RecordingRequest(
            script=["ok", se.ConsoleError("flaky", status=500)], default="ok"
        )
        keeper = self.keeper(request)
        keeper.start()
        self.assertTrue(request.wait_for(6))
        keeper.stop()
        self.assertEqual(keeper.errors, 1)
        self.assertGreaterEqual(keeper.beats, 3, "beats continued after the failure")
        self.assertEqual(sum("세션 유지 신호가 실패" in line for line in self.said), 1)

    def test_a_failing_first_beat_raises_and_leaves_nothing_running(self):
        request = RecordingRequest(script=[se.ConsoleError("down")])
        keeper = self.keeper(request)
        with self.assertRaises(se.ConsoleError):
            keeper.start()
        keeper.stop()
        self.assertEqual(len(request.calls), 1, "no thread, and no closing call either")

    def test_a_failing_closing_call_never_raises(self):
        calls = []

        def request(method, url, timeout=10.0):
            calls.append(url)
            if url.endswith("closing=1"):
                raise se.ConsoleError("console already gone")
            return "ok"

        keeper = self.keeper(request)
        keeper.start()
        keeper.stop()
        self.assertEqual(calls[-1], CLOSING_URL)
        self.assertTrue(any("종료 신호" in line for line in self.said))


class WriteRetryTests(unittest.TestCase):
    def console(self, outcomes):
        self.sleeps, self.notes = [], []
        self.request = RecordingRequest(script=outcomes)
        return director.Console(
            BASE,
            request=self.request,
            sleep=self.sleeps.append,
            notify=self.notes.append,
        )

    def test_a_503_is_retried_after_one_second(self):
        console = self.console([busy_503(), "ok"])
        console.set_gear("Drive")
        self.assertEqual(
            self.request.calls, [("PUT", BASE + "/vehicle/gear?gear=Drive")] * 2
        )
        self.assertEqual(self.sleeps, [1.0])
        self.assertIn("emulator_inactive", self.notes[0])
        self.assertIn("1/5", self.notes[0])

    def test_up_to_five_retries_are_allowed(self):
        console = self.console([busy_503()] * 5 + ["ok"])
        console.set_odometer(45200)
        self.assertEqual(len(self.request.calls), 6)
        self.assertEqual(self.sleeps, [1.0] * 5)

    def test_the_sixth_503_is_raised(self):
        console = self.console([busy_503()] * 6)
        with self.assertRaises(se.ConsoleError) as caught:
            console.set_gear("Park")
        self.assertEqual(caught.exception.status, 503)
        self.assertEqual(len(self.request.calls), 6)
        self.assertEqual(self.sleeps, [1.0] * 5)

    def test_other_failures_are_raised_at_once(self):
        for error in (
            se.ConsoleError("bad value", status=400, body="x"),
            se.ConsoleError("server error", status=500),
            se.ConsoleError("connection refused"),
        ):
            with self.subTest(status=error.status):
                console = self.console([error])
                with self.assertRaises(se.ConsoleError):
                    console.set_gear("Park")
                self.assertEqual(len(self.request.calls), 1)
                self.assertEqual(self.sleeps, [])

    def test_a_connection_closed_without_an_answer_is_retried(self):
        # CI run 40280862531: "PUT .../vehicle/controls?speed=70... -> Remote end closed
        # connection without response" ended the demo with exit 3
        dropped = se.ConsoleError("Remote end closed connection without response", dropped=True)
        console = self.console([dropped, "ok"])
        console.set_controls(70, accelerator=45, brake=0, steering=0)
        self.assertEqual(len(self.request.calls), 2)
        self.assertEqual(self.sleeps, [1.0])
        self.assertIn("응답 없이 끊겼습니다", self.notes[0])

    def test_dropped_connections_are_classified(self):
        self.assertTrue(se.connection_dropped(http.client.RemoteDisconnected("closed")))
        self.assertTrue(se.connection_dropped(ConnectionResetError()))
        self.assertTrue(se.connection_dropped(urllib.error.URLError(ConnectionResetError())))
        self.assertFalse(se.connection_dropped(ConnectionRefusedError()))
        self.assertFalse(se.connection_dropped(urllib.error.URLError(ConnectionRefusedError())))
        self.assertFalse(se.connection_dropped(socket.timeout()))

    def test_console_request_marks_a_dropped_connection(self):
        class Opener:
            def open(self, req, timeout=None):
                raise http.client.RemoteDisconnected("Remote end closed connection without response")

        with mock.patch.object(se, "_local_opener", return_value=Opener()):
            with self.assertRaises(se.ConsoleError) as caught:
                se.console_request("PUT", BASE + "/vehicle/gear?gear=Park")
        self.assertTrue(caught.exception.dropped)
        self.assertIsNone(caught.exception.status)

    def test_reads_are_never_retried(self):
        console = self.console([busy_503(), "{}"])
        with self.assertRaises(se.ConsoleError):
            console.state()
        self.assertEqual(self.request.calls, [("GET", BASE + "/vehicle/state")])

    def test_every_write_endpoint_retries(self):
        writers = (
            lambda c: c.set_gear("Drive"),
            lambda c: c.set_odometer(45201),
            lambda c: c.set_controls(70, accelerator=20, brake=0, steering=0),
            lambda c: c.set_seat_occupied("driver", True),
        )
        for write in writers:
            console = self.console([busy_503(), "ok"])
            write(console)
            self.assertEqual(len(self.request.calls), 2)
            self.assertEqual(self.sleeps, [1.0])

    def test_error_codes_carry_status_and_body_from_the_real_http_layer(self):
        server = ConsoleServer()
        self.addCleanup(server.close)
        server.reject = (503, '{"error":"emulator_inactive"}')
        sleeps = []
        console = director.Console(server.url, sleep=sleeps.append)
        with self.assertRaises(se.ConsoleError) as caught:
            console.set_gear("Drive")
        self.assertEqual(caught.exception.status, 503)
        self.assertIn("emulator_inactive", caught.exception.body)
        self.assertEqual(len(server.requests), 6, "1 try + 5 retries over real sockets")
        self.assertEqual(sleeps, [1.0] * 5)
        server.reject = None
        dead = se.ConsoleError("x")
        self.assertIsNone(dead.status)


class StateConsole:
    def __init__(self, states):
        self.states = list(states)
        self.reads = 0

    def state(self):
        self.reads += 1
        return self.states.pop(0) if len(self.states) > 1 else self.states[0]


NOT_READY = {"emulator_active": False, "gear": {"bridge_ready": False}}
READY = {"emulator_active": True, "gear": {"bridge_ready": True}}


class BridgeWaitTests(unittest.TestCase):
    def test_bridge_ready_needs_a_literal_true(self):
        self.assertTrue(director.bridge_ready(READY))
        for state in (
            {},
            {"gear": {}},
            {"gear": None},
            {"gear": {"bridge_ready": False}},
            {"gear": {"bridge_ready": "true"}},
            {"gear": {"bridge_ready": 1}},
            {"bridge_ready": True},
        ):
            self.assertFalse(director.bridge_ready(state), state)

    def test_returns_at_once_when_the_bridge_is_up(self):
        console, sleeps, notes = StateConsole([READY]), [], []
        director.wait_for_bridge(console, sleep=sleeps.append, say_fn=notes.append)
        self.assertEqual((console.reads, sleeps, notes), (1, [], []))

    def test_polls_every_second_until_it_is_up(self):
        console, sleeps, notes = StateConsole([NOT_READY] * 3 + [READY]), [], []
        director.wait_for_bridge(console, sleep=sleeps.append, say_fn=notes.append)
        self.assertEqual(console.reads, 4)
        self.assertEqual(sleeps, [1.0] * 3)
        self.assertEqual(len(notes), 1, "announced once, not on every poll")
        self.assertIn("bridge", notes[0])

    def test_timeout_is_300_seconds_and_the_message_is_bilingual(self):
        self.assertEqual(director.BRIDGE_WAIT_S, 300.0)
        clock = [0.0]

        def sleep(seconds):
            clock[0] += seconds * 60  # fast-forward: every poll costs a minute

        with self.assertRaises(se.ConsoleError) as caught:
            director.wait_for_bridge(
                StateConsole([NOT_READY]),
                now=lambda: clock[0],
                sleep=sleep,
                say_fn=lambda m: None,
            )
        text = str(caught.exception)
        self.assertIn("300초", text)
        self.assertIn("did not become ready within 300 s", text)
        self.assertIn("bridge_ready=False", text)
        self.assertIn("emulator_active=False", text)


# --------------------------------------------------------------------------- GPS as NMEA

# Logged by the Pleos navigation app on the CI emulator (run 40272821598, after `adb emu geo fix`):
#   Invalid NMEA message: $GPGGA,052019,3723.6820,N,12706.6719,E,1,6,,0.0,M,0.,M,,,*47
#   NMEA checksum mismatch: expected=47, actual=7B   (and actual=68 for the $GPRMC beside it)
EMULATOR_GGA = "GPGGA,052019,3723.6820,N,12706.6719,E,1,6,,0.0,M,0.,M,,,"
EMULATOR_RMC = "GPRMC,052019,A,3723.6820,N,12706.6719,E,0.00,0.00,261026,0.0,W"
FIX_TIME = datetime.datetime(2026, 10, 26, 5, 20, 19, tzinfo=datetime.timezone.utc)


class NmeaTests(unittest.TestCase):
    def test_checksum_matches_what_the_navigation_app_computed(self):
        self.assertEqual(se.nmea_checksum(EMULATOR_GGA), "7B")
        self.assertEqual(se.nmea_checksum(EMULATOR_RMC), "68")

    def test_degrees_and_minutes(self):
        self.assertEqual(se.nmea_degrees(37.3947, 2), "3723.6820")
        self.assertEqual(se.nmea_degrees(127.1112, 3), "12706.6720")
        self.assertEqual(se.nmea_degrees(-0.5, 2), "0030.0000")
        self.assertEqual(se.nmea_degrees(37.9999999, 2), "3800.0000", "59.99999' rolls over")

    def test_sentences_keep_the_emulator_layout_with_a_real_checksum(self):
        gga, rmc = se.nmea_fix_sentences(37.3947, 127.1112, FIX_TIME)
        self.assertEqual(gga, "$GPGGA,052019,3723.6820,N,12706.6720,E,1,6,,0.0,M,0.,M,,,*71")
        self.assertEqual(
            rmc, "$GPRMC,052019,A,3723.6820,N,12706.6720,E,0.00,0.00,261026,0.0,W*62"
        )
        for sentence in (gga, rmc):
            body, checksum = sentence[1:].split("*")
            self.assertEqual(se.nmea_checksum(body), checksum)
            self.assertNotIn(" ", sentence, "one argument for `adb emu geo nmea`")

    def test_speed_in_knots_course_and_hemispheres(self):
        _, rmc = se.nmea_fix_sentences(-33.8688, -70.6693, FIX_TIME, 92.6, 370.0)
        fields = rmc.split("*")[0].split(",")
        self.assertEqual(fields[3:7], ["3352.1280", "S", "07040.1580", "W"])
        self.assertEqual(fields[7:9], ["50.00", "10.00"])

    def test_bearing(self):
        self.assertAlmostEqual(director.bearing_deg((37.0, 127.0), (38.0, 127.0)), 0.0)
        self.assertAlmostEqual(director.bearing_deg((37.0, 127.0), (36.0, 127.0)), 180.0)
        east = director.bearing_deg((37.0, 127.0), (37.0, 128.0))
        self.assertTrue(89.0 < east < 91.0, east)


# --------------------------------------------------------------------------- device readiness

HOME_SCREEN = "ai.umos.homescreen/.HomeActivity"
APP_SCREEN = "app.autobrief.car/.ui.MainActivity"
NAV_SCREEN = "ai.umos.maps.android.navigation.app/.MainActivity"
WIZARD_SCREEN = "ai.umos.setupwizard/.SetupWizardActivity"
UNLOCKED = "[UserState: id=10, state=RUNNING_UNLOCKED, lastState=RUNNING_UNLOCKING, switching=false]"
LOCKED = "[UserState: id=10, state=RUNNING_LOCKED, lastState=BOOTING, switching=true]"


def dumpsys_front(component, user=10):
    return (
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
        "Display #0 (activities from top to bottom):\n"
        "  * Task{5d1c9e1 #1000012 type=standard U=%d visible=true}\n"
        "  topResumedActivity=ActivityRecord{1f2e3d4 u%d %s t1000012}\n"
        "\n"
        "  ResumedActivity: ActivityRecord{1f2e3d4 u%d %s t1000012}\n"
        % (user, user, component, user, component)
    )


DUMPSYS_NOTHING_RESUMED = (
    "Display #0 (activities from top to bottom):\n  topResumedActivity=null\n"
)


class SequenceAdb:
    """The device side of the readiness waits: every poll returns the next scripted answer and
    the last answer repeats. `am start` of the app makes `after_start` the next front answers."""

    def __init__(
        self,
        users=("10",),
        states=(UNLOCKED,),
        fronts=(dumpsys_front(HOME_SCREEN),),
        logcats=("",),
        hsum="true",
        after_start=None,
    ):
        self.seq = {
            "user": list(users),
            "state": list(states),
            "front": list(fronts),
            "logcat": list(logcats),
        }
        self.pos = dict.fromkeys(self.seq, 0)
        self.hsum = hsum
        self.after_start = after_start
        self.shells = []

    def next(self, key):
        items, index = self.seq[key], self.pos[key]
        self.pos[key] += 1
        return items[min(index, len(items) - 1)]

    def getprop(self, name):
        return self.hsum if name == se.HSUM_PROP else ""

    def current_user(self):
        return se.parse_current_user(self.shell("am", "get-current-user"))

    def user_state(self, user):
        return se.parse_user_state(self.shell("am", "get-started-user-state", str(user)))

    def activities(self):
        return self.shell("dumpsys", "activity", "activities")

    def logcat_dump(self, *args, **kwargs):
        return self.next("logcat")

    def out(self, *args, **kwargs):
        return "10-26 14:20:01.000 W AutoBriefVehicle: probe: vehicle service not bound\n"

    def shell(self, *args, timeout=None):
        line = " ".join(args)
        self.shells.append(line)
        if line == "am get-current-user":
            return self.next("user") + "\n"
        if line.startswith("am get-started-user-state"):
            return self.next("state") + "\n"
        if line == "dumpsys activity activities":
            return self.next("front")
        if line == "dumpsys user":
            return "Users:\n  UserInfo{10:Driver:412} running\n    State: RUNNING_UNLOCKED\n"
        if line.startswith("am start") and "-n" in args:
            if self.after_start is not None:
                self.seq["front"], self.pos["front"] = list(self.after_start), 0
            return "Starting: Intent { cmp=%s }\nStatus: ok\n" % APP_SCREEN
        return ""


class FakeClock:
    def __init__(self):
        self.t = 0.0
        self.sleeps = []

    def now(self):
        return self.t

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.t += seconds


class ReadinessParseTests(unittest.TestCase):
    def test_current_user(self):
        self.assertEqual(se.parse_current_user("10\n"), 10)
        self.assertEqual(se.parse_current_user(" 0 "), 0)
        self.assertIsNone(se.parse_current_user(""))
        self.assertIsNone(se.parse_current_user("Error: unknown command"))

    def test_user_state_reads_state_not_last_state(self):
        self.assertEqual(se.parse_user_state(UNLOCKED), "RUNNING_UNLOCKED")
        self.assertEqual(
            se.parse_user_state(
                "[UserState: id=10, state=RUNNING_LOCKED, lastState=RUNNING_UNLOCKED]"
            ),
            "RUNNING_LOCKED",
        )
        self.assertEqual(se.parse_user_state("RUNNING_UNLOCKING\n"), "RUNNING_UNLOCKING")
        self.assertIsNone(se.parse_user_state("User is not started: 10"))

    def test_front_activity_prefers_top_resumed(self):
        text = (
            "  ResumedActivity: ActivityRecord{aa u10 %s t1}\n"
            "  topResumedActivity=ActivityRecord{bb u10 %s t2}\n" % (NAV_SCREEN, APP_SCREEN)
        )
        front = se.parse_front_activity(text)
        self.assertEqual((front.user, front.component), (10, APP_SCREEN))
        self.assertEqual(front.package, "app.autobrief.car")
        self.assertEqual(str(front), "u10 " + APP_SCREEN)

    def test_front_activity_falls_back_to_the_resumed_record(self):
        front = se.parse_front_activity(
            "    mResumedActivity: ActivityRecord{c0ffee u0 com.x/.Main$Inner t9}\n"
        )
        self.assertEqual((front.user, front.component), (0, "com.x/.Main$Inner"))

    def test_field_names_of_other_android_versions(self):
        text = (
            "  mLastResumedActivity: ActivityRecord{01 u10 %s t3}\n"
            "    mResumedActivity: ActivityRecord{02 u10 %s t4}\n"
            "  mTopResumedActivity=ActivityRecord{03 u10 %s t5}\n"
            % (NAV_SCREEN, HOME_SCREEN, APP_SCREEN)
        )
        self.assertEqual(se.parse_front_activity(text).component, APP_SCREEN)
        self.assertEqual(
            [(top, a.component) for top, a in se.resumed_records(text)],
            [(False, HOME_SCREEN), (True, APP_SCREEN)],
            "the last-resumed record is history and is skipped",
        )

    def test_app_resumed_in_a_panel_counts_in_its_user_only(self):
        text = (
            "  topResumedActivity=ActivityRecord{aa u10 %s t1}\n"
            "  ResumedActivity: ActivityRecord{bb u10 %s t2}\n" % (HOME_SCREEN, APP_SCREEN)
        )
        self.assertEqual(se.app_resumed(text, 10).component, APP_SCREEN)
        self.assertIsNone(se.app_resumed(text, 0))
        self.assertIsNone(se.app_resumed(dumpsys_front(HOME_SCREEN), 10))

    def test_nothing_resumed(self):
        self.assertIsNone(se.parse_front_activity(DUMPSYS_NOTHING_RESUMED))
        self.assertIsNone(se.parse_front_activity(""))

    def test_resumed_lines_for_the_preflight(self):
        self.assertEqual(
            se.resumed_lines(dumpsys_front(HOME_SCREEN)),
            [
                "topResumedActivity=ActivityRecord{1f2e3d4 u10 %s t1000012}" % HOME_SCREEN,
                "ResumedActivity: ActivityRecord{1f2e3d4 u10 %s t1000012}" % HOME_SCREEN,
            ],
        )

    def test_user_not_ready_reasons(self):
        home = se.FrontActivity(10, HOME_SCREEN)
        cases = [
            (se.UserSnapshot(True, None, None, home), "현재 사용자"),
            (se.UserSnapshot(True, 0, None, home), "전환 중"),
            (se.UserSnapshot(True, 10, "RUNNING_LOCKED", home), "RUNNING_LOCKED"),
            (se.UserSnapshot(True, 10, se.USER_UNLOCKED, None), "앞 화면 없음"),
            (
                se.UserSnapshot(True, 10, se.USER_UNLOCKED, se.FrontActivity(0, HOME_SCREEN)),
                "다른 사용자",
            ),
            (
                se.UserSnapshot(True, 10, se.USER_UNLOCKED, se.FrontActivity(10, WIZARD_SCREEN)),
                "설정 마법사",
            ),
            (
                se.UserSnapshot(
                    True, 10, se.USER_UNLOCKED,
                    se.FrontActivity(10, "com.android.car.settings/.FallbackHome"),
                ),
                "임시 홈",
            ),
        ]
        for snap, words in cases:
            self.assertIn(words, se.user_not_ready(snap), snap)
        self.assertIsNone(se.user_not_ready(se.UserSnapshot(True, 10, se.USER_UNLOCKED, home)))
        # without a headless system user, user 0 is the driver
        self.assertIsNone(
            se.user_not_ready(
                se.UserSnapshot(False, 0, se.USER_UNLOCKED, se.FrontActivity(0, HOME_SCREEN))
            )
        )

    def test_start_and_broadcast_name_the_user(self):
        self.assertEqual(
            se.start_app_args(10, restart=True),
            ["am", "start", "-W", "-S", "--user", "10", "-n", APP_SCREEN],
        )
        self.assertEqual(
            se.start_app_args(10), ["am", "start", "-W", "--user", "10", "-n", APP_SCREEN]
        )
        self.assertEqual(
            se.demo_broadcast_args("reset", user=10)[:4], ["am", "broadcast", "--user", "10"]
        )


class UserReadyWaitTests(unittest.TestCase):
    def test_waits_through_the_user_switch_and_the_lock_screen(self):
        adb = SequenceAdb(
            users=("0", "0", "10"),
            states=(LOCKED, UNLOCKED),
            fronts=(DUMPSYS_NOTHING_RESUMED,) * 4 + (dumpsys_front(HOME_SCREEN),),
        )
        clock, notes = FakeClock(), []
        user = se.wait_for_user_ready(adb, now=clock.now, sleep=clock.sleep, log=notes.append)
        self.assertEqual(user, 10)
        self.assertEqual(adb.shells.count("am get-current-user"), 5)
        self.assertNotIn(
            "am get-started-user-state 0", adb.shells, "user 0 is never the driver here"
        )
        self.assertIn("운전자 화면 준비됨: 사용자 10, 앞 화면 %s" % HOME_SCREEN, notes[-1])
        self.assertEqual(clock.sleeps, [2.0] * 4)

    def test_setup_wizard_is_finished_once_then_the_home_counts(self):
        adb = SequenceAdb(
            fronts=(dumpsys_front(WIZARD_SCREEN),) * 2 + (dumpsys_front(HOME_SCREEN),)
        )
        clock, notes = FakeClock(), []
        self.assertEqual(
            se.wait_for_user_ready(adb, now=clock.now, sleep=clock.sleep, log=notes.append), 10
        )
        self.assertEqual(
            adb.shells.count("settings put --user 10 secure user_setup_complete 1"), 1
        )
        self.assertIn("settings put global device_provisioned 1", adb.shells)
        self.assertIn(
            "am start --user 10 -a android.intent.action.MAIN -c android.intent.category.HOME",
            adb.shells,
        )

    def test_timeout_names_the_last_reason(self):
        adb = SequenceAdb(states=(LOCKED,))
        clock = FakeClock()
        with self.assertRaises(se.SetupError) as caught:
            se.wait_for_user_ready(
                adb, timeout=30, now=clock.now, sleep=clock.sleep, log=lambda m: None
            )
        self.assertEqual(caught.exception.code, se.EXIT_BOOT)
        self.assertIn("RUNNING_LOCKED", str(caught.exception))
        self.assertEqual(se.USER_READY_WAIT_S, 300)


class AppFrontWaitTests(unittest.TestCase):
    def test_app_that_holds_the_front_is_accepted(self):
        adb = SequenceAdb(fronts=(dumpsys_front(APP_SCREEN),))
        clock = FakeClock()
        front = se.wait_for_app_in_front(
            adb, 10, stable=3.0, now=clock.now, sleep=clock.sleep, log=lambda m: None
        )
        self.assertEqual(front.component, APP_SCREEN)
        self.assertEqual(clock.sleeps, [1.0] * 3)
        self.assertFalse(any(s.startswith("am start") for s in adb.shells))

    def test_navigation_taking_the_front_starts_the_app_again(self):
        adb = SequenceAdb(
            fronts=(dumpsys_front(APP_SCREEN), dumpsys_front(NAV_SCREEN)),
            after_start=(dumpsys_front(APP_SCREEN),),
        )
        clock, notes = FakeClock(), []
        se.wait_for_app_in_front(
            adb, 10, stable=3.0, now=clock.now, sleep=clock.sleep, log=notes.append
        )
        starts = [s for s in adb.shells if s.startswith("am start")]
        self.assertEqual(starts, ["am start -W --user 10 -n " + APP_SCREEN])
        self.assertIn("u10 " + NAV_SCREEN, notes[0])

    def test_the_app_in_another_user_does_not_count(self):
        adb = SequenceAdb(fronts=(dumpsys_front(APP_SCREEN, user=0),))
        clock = FakeClock()
        with self.assertRaises(se.SetupError) as caught:
            se.wait_for_app_in_front(
                adb, 10, timeout=12, stable=1.0, now=clock.now, sleep=clock.sleep,
                log=lambda m: None,
            )
        self.assertIn("u0 " + APP_SCREEN, str(caught.exception))
        starts = [s for s in adb.shells if s.startswith("am start")]
        self.assertEqual(len(starts), 2, "relaunched every 5 s, not on every poll")


class BroadcastIdleTests(unittest.TestCase):
    def test_waits_on_the_device_command(self):
        adb = SequenceAdb()
        clock = FakeClock()
        self.assertEqual(se.wait_for_broadcast_idle(adb, now=clock.now), 0.0)
        self.assertEqual(adb.shells, ["am wait-for-broadcast-idle"])

    def test_timeout_is_a_boot_error(self):
        class Slow(SequenceAdb):
            def shell(self, *args, timeout=None):
                raise se.AdbTimeout("adb shell: 시간 초과")

        with self.assertRaises(se.SetupError) as caught:
            se.wait_for_broadcast_idle(Slow(), timeout=5)
        self.assertEqual(caught.exception.code, se.EXIT_BOOT)


class VehicleConnectedWaitTests(unittest.TestCase):
    def test_returns_when_the_line_appears(self):
        adb = SequenceAdb(
            logcats=("", "I AutoBriefLog: SPACE LIVE\nI AutoBriefLog: VEHICLE_CONNECTED\n")
        )
        clock = FakeClock()
        self.assertEqual(
            se.wait_for_vehicle_connected(adb, now=clock.now, sleep=clock.sleep), 2.0
        )

    def test_timeout_shows_the_sdk_warnings(self):
        clock = FakeClock()
        with self.assertRaises(se.SetupError) as caught:
            se.wait_for_vehicle_connected(
                SequenceAdb(), timeout=10, now=clock.now, sleep=clock.sleep
            )
        text = str(caught.exception)
        self.assertIn("VEHICLE_CONNECTED 없음", text)
        self.assertIn("vehicle service not bound", text)


class DirectorFrontTests(unittest.TestCase):
    def test_app_in_front_is_left_alone(self):
        adb = SequenceAdb(fronts=(dumpsys_front(APP_SCREEN),))
        director.ensure_app_in_front(adb, say_fn=lambda m: None)
        self.assertFalse(any(s.startswith("am start") for s in adb.shells))

    def test_home_in_front_gets_the_app_started_in_the_current_user(self):
        adb = SequenceAdb(
            fronts=(dumpsys_front(HOME_SCREEN),), after_start=(dumpsys_front(APP_SCREEN),)
        )
        clock, notes = FakeClock(), []
        front = director.ensure_app_in_front(
            adb, stable=2.0, now=clock.now, sleep=clock.sleep, say_fn=notes.append
        )
        self.assertEqual(front.component, APP_SCREEN)
        self.assertEqual(
            [s for s in adb.shells if s.startswith("am start")],
            ["am start -W --user 10 -n " + APP_SCREEN],
        )
        self.assertIn("다시 띄웁니다", notes[0])

    def test_unknown_user_is_an_app_error(self):
        with self.assertRaises(se.SetupError) as caught:
            director.ensure_app_in_front(SequenceAdb(users=("",)), say_fn=lambda m: None)
        self.assertEqual(caught.exception.code, director.EXIT_APP)

    def test_preflight_lines_and_file(self):
        adb = SequenceAdb()
        lines = director.preflight_lines(adb, {"gear": {"bridge_ready": True}})
        self.assertEqual(lines[0], "current user: 10")
        self.assertEqual(lines[1], "user state: " + UNLOCKED)
        self.assertEqual(lines[2], "front: u10 " + HOME_SCREEN)
        self.assertIn("users: UserInfo{10:Driver:412} running", lines)
        self.assertEqual(lines[-1], 'console: {"gear": {"bridge_ready": true}}')
        with tempfile.TemporaryDirectory() as tmp:
            path = director.write_preflight(tmp, lines)
            with open(path, encoding="utf-8") as handle:
                self.assertEqual(handle.read().splitlines(), lines)
        self.assertIsNone(director.write_preflight(None, lines))


# --------------------------------------------------------------------------- end-to-end rehearsals

FAKE_ADB = r"""#!{python}
import json, os, sys, time

state = os.environ["FAKE_DEVICE_DIR"]
args = sys.argv[1:]
with open(os.path.join(state, "adb_calls.jsonl"), "a", encoding="utf-8") as handle:
    handle.write(json.dumps(args, ensure_ascii=False) + "\n")
if args[:1] == ["-s"]:
    args = args[2:]


def flag(name):
    return os.path.exists(os.path.join(state, name))


def out(text):
    sys.stdout.write(text)
    sys.stdout.flush()


running = flag("emulator.running")
logcat = os.path.join(state, "logcat.txt")
front_file = os.path.join(state, "front")
cmd, rest = (args[0] if args else ""), args[1:]
HOME = "ai.umos.homescreen/.HomeActivity"
APP = "app.autobrief.car/.ui.MainActivity"
NAV = "ai.umos.maps.android.navigation.app/.MainActivity"


def front():
    return open(front_file).read() if os.path.exists(front_file) else HOME


def set_front(component):
    with open(front_file, "w") as handle:
        handle.write(component)


def log_app(text):
    with open(logcat, "a", encoding="utf-8") as handle:
        handle.write("10-25 12:00:00.000     1     1 I AutoBriefLog: " + text + "\n")


def countdown(name):
    # True while the counter file <name> (polls left) is above zero; each call uses one
    path = os.path.join(state, name)
    if not os.path.exists(path):
        return False
    left = int(open(path).read() or "0")
    if left <= 0:
        return False
    with open(path, "w") as handle:
        handle.write(str(left - 1))
    return True

if cmd == "devices":
    out("List of devices attached\n" + ("emulator-5554\tdevice\n" if running else "") + "\n")
elif cmd == "wait-for-device":
    deadline = time.time() + float(os.environ.get("FAKE_WAIT_S", "5"))
    while not flag("emulator.running") and time.time() < deadline:
        time.sleep(0.05)
    sys.exit(0 if flag("emulator.running") else 1)
elif cmd == "shell":
    line = rest[0] if rest else ""
    if line.startswith("getprop sys.boot_completed"):
        out("1\n" if running and not flag("never_boots") else "\n")
    elif line.startswith("getprop ro.fw.mu.headless_system_user"):
        out("true\n")
    elif line == "am get-current-user":
        out("0\n" if countdown("switch_polls") else "10\n")
    elif line.startswith("am get-started-user-state"):
        state_name = "RUNNING_LOCKED" if countdown("locked_polls") else "RUNNING_UNLOCKED"
        out("[UserState: id=10, state=%s, lastState=RUNNING_LOCKED, switching=false]\n" % state_name)
    elif line == "dumpsys activity activities":
        shown = NAV if countdown("nav_polls") else front()
        out("Display #0 (activities from top to bottom):\n"
            "  topResumedActivity=ActivityRecord{1f2e3d4 u10 %s t1000012}\n"
            "  ResumedActivity: ActivityRecord{1f2e3d4 u10 %s t1000012}\n" % (shown, shown))
    elif line == "am wait-for-broadcast-idle":
        out("All broadcast queues are idle!\n")
    elif line == "dumpsys user":
        out("Users:\n  UserInfo{0:Driver:c13} running\n    State: RUNNING_UNLOCKED\n"
            "  UserInfo{10:Driver:412} running\n    State: RUNNING_UNLOCKED\n")
    elif line.startswith("am start") and "android.intent.category.HOME" in line:
        set_front(HOME)
    elif line.startswith("am start") and "-p ai.umos.maps.android.navigation.app" in line:
        set_front(NAV)
    elif line.startswith("am start") and flag("start_fails"):
        out("Error type 3\nError: Activity class {app.autobrief.car/app.autobrief.car.ui.MainActivity} does not exist.\n")
    elif line.startswith("am start"):
        out("Starting: Intent { cmp=app.autobrief.car/.ui.MainActivity }\nStatus: ok\n")
        set_front(APP)
        if not flag("no_vehicle"):
            log_app("VEHICLE_CONNECTED")
    elif line.startswith("settings put"):
        pass
    elif line.startswith("am broadcast"):
        out("Broadcast completed: result=0\n")
        anr = "load_sample" in line and (flag("anr_always") or flag("anr_once"))
        if anr:
            # Android Automotive closes an app that misses a focus event for 5 s (no ANR dialog in the car UI)
            if flag("anr_once"):
                os.remove(os.path.join(state, "anr_once"))
            with open(logcat, "a", encoding="utf-8") as handle:
                handle.write("10-25 12:00:00.000   853   900 E ActivityManager: ANR in app.autobrief.car (app.autobrief.car/.ui.MainActivity)\n")
        elif "load_sample" in line and not flag("no_demo_hook"):
            with open(logcat, "a", encoding="utf-8") as handle:
                handle.write("10-25 12:00:00.000     1     1 I AutoBriefLog: DEMO_READY trips=487\n")
elif cmd == "install":
    if flag("install_fails"):
        sys.stderr.write("adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n")
        sys.exit(1)
    out("Performing Streamed Install\nSuccess\n")
elif cmd == "forward":
    pass
elif cmd == "emu":
    if rest[:2] == ["avd", "name"]:
        out(("Some_Other_Avd" if flag("other_avd") else "Pleos_Connect_v2.1.0") + "\nOK\n")
    elif rest[:2] == ["geo", "fix"]:
        out("KO: emulator console authentication failed\n" if flag("geo_ko") else "OK\n")
    elif rest[:2] == ["geo", "nmea"]:
        out("KO: bad sentence\n" if flag("nmea_ko") else "OK\n")
        fields = rest[2].split(",") if len(rest) > 2 else []
        if not flag("nmea_ko") and fields and fields[0] == "$GPGGA" and not flag("no_position"):
            lat = int(fields[2][:2]) + float(fields[2][2:]) / 60
            lon = int(fields[4][:3]) + float(fields[4][3:]) / 60
            places = {"집": (37.5444, 127.0374), "회사": (37.3947, 127.1112), "가상상사": (37.5126, 127.0590)}
            label = next((n for n, (a, o) in places.items() if abs(a - lat) < 0.0025 and abs(o - lon) < 0.0025), "-")
            log_app("POSITION place=" + label)
    else:
        out("KO: unknown command\n")
elif cmd == "logcat":
    if "-c" in rest:
        open(logcat, "w").close()
    elif "-G" in rest:
        pass
    elif os.path.exists(logcat):
        out(open(logcat, encoding="utf-8").read())
elif cmd == "exec-out":
    sys.stdout.buffer.write(bytes.fromhex("89504e470d0a1a0a") + b"fake-screen")
    sys.stdout.buffer.flush()
else:
    sys.stderr.write("fake adb: unsupported: " + " ".join(args) + "\n")
    sys.exit(1)
"""

FAKE_EMULATOR = r"""#!{python}
import json, os, sys, time

state = os.environ["FAKE_DEVICE_DIR"]
with open(os.path.join(state, "emulator_launches.jsonl"), "a", encoding="utf-8") as handle:
    handle.write(json.dumps({
        "argv": sys.argv[1:],
        "avd_home": os.environ.get("ANDROID_AVD_HOME"),
        "sdk_root": os.environ.get("ANDROID_SDK_ROOT"),
    }) + "\n")
if os.path.exists(os.path.join(state, "emulator_dies")):
    print("ERROR: x86 emulation currently requires hardware acceleration!", flush=True)
    sys.exit(3)
with open(os.path.join(state, "emulator.pid"), "w") as handle:
    handle.write(str(os.getpid()))
open(os.path.join(state, "emulator.running"), "w").close()
print("fake emulator up", flush=True)
time.sleep(600)
"""


class FakeApp:
    """The Vehicle Virtual Console plus the part of the app that logs trips to logcat.

    Like the real v2.1.0 console it answers every write with 503 unless a browser session is
    alive (POST /browser/session, beaten at least every `session_ttl` seconds) and reports
    gear.bridge_ready=false for the first `bridge_after_polls` state reads.
    """

    PURPOSES = {45200: "COMMUTE", 45218: "BUSINESS"}

    def __init__(
        self,
        logcat_path,
        log_trips=True,
        bridge_after_polls=0,
        reject_first_puts=0,
        session_ttl=5.0,
    ):
        self.logcat_path, self.log_trips = logcat_path, log_trips
        self.bridge_after_polls, self.reject_first_puts = (
            bridge_after_polls,
            reject_first_puts,
        )
        self.session_ttl = session_ttl
        self.gear, self.odometer, self.speed, self.trip_start = "Park", 0, 0, None
        self.requests = []
        self.sessions = {}  # client -> time of its last beat
        self.sessions_log = []  # (client, closing) in arrival order
        self.state_polls = 0
        self.rejected = 0
        self.lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, body):
                data = body.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                with outer.lock:
                    outer.requests.append(("GET", self.path))
                    if self.path == "/vehicle/state":
                        outer.state_polls += 1
                        state = {
                            "emulator_active": outer.active(),
                            "gear": {
                                "actual": {"value": outer.gear},
                                "bridge_ready": outer.bridge_ready(),
                            },
                            "controls": {"speed_kph": outer.speed},
                            "odometer": {"traveled_distance_km": outer.odometer},
                        }
                if self.path == "/vehicle/state":
                    self.reply(200, json.dumps(state))
                else:
                    self.reply(404, "no such page")

            def do_POST(self):
                parts = urlsplit(self.path)
                query = dict(parse_qsl(parts.query))
                with outer.lock:
                    outer.requests.append(("POST", self.path))
                    known = parts.path == "/browser/session"
                    if known:
                        outer.beat(query["client"], "closing" in query)
                self.reply(*((200, "ok") if known else (404, "no such endpoint")))

            def do_PUT(self):
                parts = urlsplit(self.path)
                query = dict(parse_qsl(parts.query))
                with outer.lock:
                    outer.requests.append(("PUT", self.path))
                    status = outer.gate() or outer.apply(parts.path, query)
                self.reply(*status)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            kwargs={"poll_interval": 0.05},
            daemon=True,
        )
        self.thread.start()

    def beat(self, client, closing):
        self.sessions_log.append((client, closing))
        if closing:
            self.sessions.pop(client, None)
        else:
            self.sessions[client] = time.monotonic()

    def active(self):
        now = time.monotonic()
        return any(now - beat <= self.session_ttl for beat in self.sessions.values())

    def bridge_ready(self):
        return self.state_polls > self.bridge_after_polls

    def gate(self):
        """The 503s of the real console; None when the write may go through."""
        if self.reject_first_puts > 0:
            self.reject_first_puts -= 1
            self.rejected += 1
            return 503, '{"error":"emulator_inactive"}'
        if not self.active():
            self.rejected += 1
            return 503, '{"error":"emulator_inactive"}'
        if not self.bridge_ready():
            self.rejected += 1
            return 503, '{"error":"bridge_not_ready"}'
        return None

    def log(self, text):
        if self.log_trips:
            with open(self.logcat_path, "a", encoding="utf-8") as handle:
                handle.write(
                    "10-25 12:00:00.000     1     1 I AutoBriefLog: %s\n" % text
                )

    def apply(self, path, query):
        if path == "/vehicle/gear":
            wanted = query["gear"]
            if self.gear == "Park" and wanted != "Park":
                self.trip_start = self.odometer
                self.log("TRIP_OPENED odo=%.1f" % self.odometer)
            if self.gear != "Park" and wanted == "Park" and self.trip_start is not None:
                purpose = self.PURPOSES.get(self.trip_start, "UNCLASSIFIED")
                self.log(
                    "TRIP_CLOSED id=TRIP-1 start=%.1f end=%.1f km=%.1f purpose=%s by=자동 reason=시험"
                    % (
                        self.trip_start,
                        self.odometer,
                        self.odometer - self.trip_start,
                        purpose,
                    )
                )
                self.trip_start = None
            self.gear = wanted
        elif path == "/vehicle/odometer":
            self.odometer = int(query["traveled_distance_km"])
        elif path == "/vehicle/controls":
            self.speed = int(query["speed"])
        elif path != "/vehicle/seat":
            return 404, "no such endpoint"
        return 200, "ok"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class FakeDevice:
    """A fake Android SDK (adb + emulator scripts), a fake console/app and their shared state."""

    def __init__(self, root, log_trips=True, **app_options):
        self.root = root
        self.state = os.path.join(root, "state")
        self.sdk = os.path.join(root, "sdk")
        self.avd_home = os.path.join(root, "avd")
        self.home = os.path.join(root, "home")
        for folder in (self.state, self.avd_home, self.home):
            os.makedirs(folder)
        self.script(os.path.join(self.sdk, "platform-tools", "adb"), FAKE_ADB)
        self.script(os.path.join(self.sdk, "emulator", "emulator"), FAKE_EMULATOR)
        self.app = FakeApp(
            os.path.join(self.state, "logcat.txt"), log_trips, **app_options
        )

    @staticmethod
    def script(path, template):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(template.replace("{python}", sys.executable))
        os.chmod(path, 0o755)

    def env(self):
        return {
            "ANDROID_HOME": self.sdk,
            "ANDROID_SDK_ROOT": "",
            "ANDROID_AVD_HOME": self.avd_home,
            "ANDROID_USER_HOME": "",
            "HOME": self.home,
            "FAKE_DEVICE_DIR": self.state,
            "no_proxy": "127.0.0.1,localhost",
            "NO_PROXY": "127.0.0.1,localhost",
        }

    def flag(self, name):
        open(os.path.join(self.state, name), "w").close()

    def preinstall_image(self, abi):
        folder = se.image_install_dir(self.sdk, abi)
        os.makedirs(folder)
        with open(
            os.path.join(folder, "source.properties"), "w", encoding="utf-8"
        ) as handle:
            handle.write(SOURCE_PROPERTIES.format(abi=abi))
        with open(os.path.join(folder, "system.img"), "wb") as handle:
            handle.write(b"\0" * 1024)

    def adb_calls(self):
        path = os.path.join(self.state, "adb_calls.jsonl")
        if not os.path.exists(path):
            return []
        with open(path, encoding="utf-8") as handle:
            return [json.loads(line) for line in handle if line.strip()]

    def launches(self):
        path = os.path.join(self.state, "emulator_launches.jsonl")
        if not os.path.exists(path):
            return []
        with open(path, encoding="utf-8") as handle:
            return [json.loads(line) for line in handle if line.strip()]

    def close(self):
        pid_file = os.path.join(self.state, "emulator.pid")
        if os.path.exists(pid_file):
            with open(pid_file) as handle:
                with contextlib.suppress(ProcessLookupError, ValueError):
                    os.kill(int(handle.read()), signal.SIGKILL)
        self.app.close()


@unittest.skipUnless(os.name == "posix", "the fake adb/emulator are POSIX scripts")
class SetupRehearsalTests(unittest.TestCase):
    """setup_emulator.main() against a fake SDK: argv, exit codes, files and messages."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.device = FakeDevice(self.tmp.name)
        self.addCleanup(self.device.close)
        self.cache = os.path.join(self.tmp.name, "cache")
        self.out = os.path.join(self.tmp.name, "out")
        self.abi = host_abi()
        self.apk = os.path.join(self.tmp.name, "app-debug.apk")
        with open(self.apk, "wb") as handle:
            handle.write(b"PK")
        for patcher in (
            mock.patch.dict(os.environ, self.device.env()),
            mock.patch.object(se, "CONSOLE_HOST_PORT", self.device.app.port),
            mock.patch.object(se, "APP_FRONT_STABLE_S", 0.0),
            mock.patch("time.sleep"),
            mock.patch("webbrowser.open"),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_main(self, *argv):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            code = se.main(["--cache", self.cache, "--out", self.out] + list(argv))
        return code, buffer.getvalue()

    def sh(self, call):
        return call[call.index("shell") + 1] if "shell" in call else None

    def test_headless_flow_with_demo_sample(self):
        self.device.preinstall_image(self.abi)
        webhook = "http://10.0.2.2:8787/hook?token=s3cret"
        code, text = self.run_main(
            "--headless",
            "--no-browser",
            "--skip-image",
            "--apk",
            self.apk,
            "--demo-sample",
            "--webhook",
            webhook,
        )
        self.assertEqual(code, 0, text)
        launch = self.device.launches()[0]
        self.assertEqual(
            launch["argv"],
            [
                "-avd",
                "Pleos_Connect_v2.1.0",
                "-no-snapshot",
                "-no-boot-anim",
                "-memory",
                "4096",
                "-no-window",
                "-no-audio",
                "-gpu",
                "swiftshader_indirect",
            ],
        )
        self.assertEqual(launch["avd_home"], self.device.avd_home)
        self.assertEqual(launch["sdk_root"], self.device.sdk)
        with open(
            os.path.join(
                self.device.avd_home, "Pleos_Connect_v2.1.0.avd", "config.ini"
            ),
            encoding="utf-8",
        ) as f:
            config = kv(f.read())
        self.assertEqual(config["hw.gpu.mode"], "swiftshader_indirect")
        self.assertEqual(config["hw.ramSize"], "4096")
        self.assertEqual(config["hw.multi_display_window"], "no")
        calls = self.device.adb_calls()
        self.assertIn(["-s", "emulator-5554", "install", "-r", "-g", self.apk], calls)
        self.assertIn(
            [
                "-s",
                "emulator-5554",
                "forward",
                "tcp:%d" % self.device.app.port,
                "tcp:8088",
            ],
            calls,
        )
        shells = [self.sh(c) for c in calls]
        start = "am start -W -S --user 10 -n app.autobrief.car/.ui.MainActivity"
        broadcast = (
            "am broadcast --user 10 -a app.autobrief.car.debug.DEMO -p app.autobrief.car --es cmd load_sample "
            "--es webhook 'http://10.0.2.2:8787/hook?token=s3cret'"
        )
        self.assertIn(start, shells)
        self.assertIn(broadcast, shells)
        self.assertIn(["-s", "emulator-5554", "logcat", "-G", "32M"], calls)
        self.assertIn(["-s", "emulator-5554", "logcat", "-c"], calls)
        # the app starts only after the driver's user is unlocked and in front, and the sample is
        # sent only after the app is in front and connected to the vehicle SDK
        first = {s: i for i, s in reversed(list(enumerate(shells)))}
        self.assertLess(first["am get-started-user-state 10"], first[start])
        self.assertLess(first[start], first[broadcast])
        self.assertIn("운전자 화면 준비됨: 사용자 10", text)
        self.assertLess(
            shells.index("am wait-for-broadcast-idle"), first[start],
            "the app starts after the boot broadcasts are delivered",
        )
        self.assertIn("앱이 화면 앞에 있습니다: u10 app.autobrief.car/.ui.MainActivity", text)
        self.assertIn("VEHICLE_CONNECTED", text)
        self.assertIn("DEMO_READY trips=487", text)
        self.assertNotIn("s3cret", text, "webhook query must not be echoed")
        self.assertIn("director.py", text)
        self.assertTrue(os.path.exists(os.path.join(self.out, "emulator.log")))
        with open(os.path.join(self.out, "emulator.log"), encoding="utf-8") as handle:
            self.assertIn("fake emulator up", handle.read())

    def test_gui_flow_opens_the_browser_and_uses_hardware_gpu(self):
        self.device.preinstall_image(self.abi)
        with mock.patch("webbrowser.open") as opened:
            code, text = self.run_main("--skip-image")
        self.assertEqual(code, 0, text)
        opened.assert_called_once_with("http://127.0.0.1:%d/" % self.device.app.port)
        self.assertNotIn("-no-window", self.device.launches()[0]["argv"])
        with open(
            os.path.join(
                self.device.avd_home, "Pleos_Connect_v2.1.0.avd", "config.ini"
            ),
            encoding="utf-8",
        ) as f:
            self.assertEqual(kv(f.read())["hw.gpu.mode"], "host")
        self.assertFalse(
            any(c[2:3] == ["install"] for c in self.device.adb_calls()),
            "no --apk, no install",
        )

    def test_rerun_reuses_the_running_emulator(self):
        self.device.preinstall_image(self.abi)
        self.assertEqual(self.run_main("--skip-image", "--no-browser")[0], 0)
        code, text = self.run_main("--skip-image", "--no-browser", "--apk", self.apk)
        self.assertEqual(code, 0, text)
        self.assertEqual(len(self.device.launches()), 1)
        self.assertIn("이미 실행 중", text)

    def test_another_avd_on_the_serial_is_refused(self):
        self.device.preinstall_image(self.abi)
        self.assertEqual(self.run_main("--skip-image", "--no-browser")[0], 0)
        self.device.flag("other_avd")
        code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 1)
        self.assertIn("Some_Other_Avd", text)

    def test_emulator_that_dies_during_boot_is_reported_at_once_with_a_hint(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("emulator_dies")
        with mock.patch.dict(os.environ, {"FAKE_WAIT_S": "0.3"}):
            code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 4)
        self.assertIn("종료됐습니다", text)
        self.assertIn("requires hardware acceleration", text, "log tail is shown")
        self.assertIn("가상화", text, "and the matching hint")

    def test_waits_for_the_driver_user_to_unlock_before_starting_the_app(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("emulator.running")
        for name, polls in (("switch_polls", "3"), ("locked_polls", "2")):
            with open(os.path.join(self.device.state, name), "w") as handle:
                handle.write(polls)
        code, text = self.run_main(
            "--headless", "--no-browser", "--skip-image", "--apk", self.apk
        )
        self.assertEqual(code, 0, text)
        shells = [self.sh(c) for c in self.device.adb_calls()]
        start = shells.index(
            "am start -W -S --user 10 -n app.autobrief.car/.ui.MainActivity"
        )
        self.assertGreaterEqual(
            shells[:start].count("am get-current-user"),
            6,
            "3 polls in user 0, 2 polls locked, then ready",
        )

    def test_app_covered_by_the_navigation_panel_is_started_again(self):
        self.device.preinstall_image(self.abi)
        with open(os.path.join(self.device.state, "nav_polls"), "w") as handle:
            handle.write("3")
        with mock.patch.object(se, "APP_RELAUNCH_EVERY_S", 0.0):
            code, text = self.run_main(
                "--headless", "--no-browser", "--skip-image", "--apk", self.apk
            )
        self.assertEqual(code, 0, text)
        self.assertIn("앱을 다시 앞으로 가져옵니다", text)
        self.assertIn("앱이 화면 앞에 있습니다: u10 app.autobrief.car", text)

    def test_app_that_never_reaches_the_vehicle_sdk_fails_with_its_log(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("no_vehicle")
        with mock.patch.object(se, "VEHICLE_CONNECTED_WAIT_S", 0.2):
            code, text = self.run_main(
                "--headless",
                "--no-browser",
                "--skip-image",
                "--apk",
                self.apk,
                "--demo-sample",
            )
        self.assertEqual(code, 1)
        self.assertIn("VEHICLE_CONNECTED 없음", text)
        self.assertFalse(
            any("load_sample" in (self.sh(c) or "") for c in self.device.adb_calls()),
            "no sample is sent to an app that is not connected",
        )

    def test_app_start_failure_is_reported(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("start_fails")
        code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 1)
        self.assertIn("앱을 시작하지 못했습니다", text)

    def test_missing_sdk_exits_2(self):
        env = dict(
            self.device.env(), ANDROID_HOME=os.path.join(self.tmp.name, "nowhere")
        )
        with mock.patch.dict(os.environ, env):
            code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 2)
        self.assertIn("SDK Manager", text)

    def test_skip_image_without_an_installed_image_exits_3(self):
        code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 3)
        self.assertIn("source.properties", text)

    def test_boot_timeout_exits_4_with_the_log_tail(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("never_boots")
        with mock.patch.object(se, "BOOT_TIMEOUT_GUI_S", 1):
            code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 4)
        self.assertIn("부팅", text)

    def test_console_unreachable_exits_5(self):
        self.device.preinstall_image(self.abi)
        with (
            mock.patch.object(se, "CONSOLE_HOST_PORT", free_port()),
            mock.patch.object(se, "CONSOLE_WAIT_S", 0.3),
        ):
            code, text = self.run_main("--skip-image", "--no-browser")
        self.assertEqual(code, 5)
        self.assertIn("가상 콘솔", text)

    def test_apk_signature_conflict_gets_an_uninstall_hint(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("install_fails")
        code, text = self.run_main("--skip-image", "--no-browser", "--apk", self.apk)
        self.assertEqual(code, 1)
        self.assertIn("adb uninstall app.autobrief.car", text)

    def test_missing_demo_hook_is_reported(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("no_demo_hook")
        with mock.patch.object(se, "DEMO_READY_WAIT_S", 0.3):
            code, text = self.run_main("--skip-image", "--no-browser", "--demo-sample")
        self.assertEqual(code, 1)
        self.assertIn("디버그 APK", text)

    def test_an_app_closed_for_not_responding_is_started_once_more(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("anr_once")
        with mock.patch.object(se, "DEMO_READY_WAIT_S", 0.3):
            code, text = self.run_main("--skip-image", "--no-browser", "--demo-sample")
        self.assertEqual(code, 0, text)
        self.assertIn("응답 없음(ANR)", text)
        self.assertIn("DEMO_READY trips=487", text)
        self.assertEqual(text.count("VEHICLE_CONNECTED ("), 2, "the app was started fresh for the second try")

    def test_a_second_close_for_not_responding_fails_the_setup(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("anr_always")
        with mock.patch.object(se, "DEMO_READY_WAIT_S", 0.3):
            code, text = self.run_main("--skip-image", "--no-browser", "--demo-sample")
        self.assertEqual(code, 1)
        self.assertEqual(text.count("응답 없음(ANR)"), 1, "one more try, not a loop")

    def test_other_setup_failures_are_not_retried(self):
        self.device.preinstall_image(self.abi)
        self.device.flag("no_demo_hook")
        with mock.patch.object(se, "DEMO_READY_WAIT_S", 0.3):
            code, text = self.run_main("--skip-image", "--no-browser", "--demo-sample")
        self.assertEqual(code, 1)
        self.assertNotIn("응답 없음(ANR)", text)
        self.assertEqual(text.count("VEHICLE_CONNECTED ("), 1)

    def test_full_flow_downloads_verifies_installs_then_reuses_cache_and_install(self):
        zip_path = os.path.join(self.tmp.name, "served.zip")
        build_image_zip(zip_path, self.abi)
        with open(zip_path, "rb") as handle:
            blob = handle.read()
        server = RangeServer({"/img.zip": blob, "/img.xml": b""}, mode="range")
        self.addCleanup(server.close)
        xml = REPO_XML.format(
            abi=self.abi,
            display="Synthetic image",
            size=len(blob),
            checksum=sha1(blob),
            url=server.url("/img.zip"),
        )
        server.routes["/img.xml"] = xml.encode("utf-8")
        urls = {self.abi: server.url("/img.xml")}
        with mock.patch.object(se, "REPO_XML_URLS", urls):
            code, text = self.run_main("--headless", "--no-browser")
            self.assertEqual(code, 0, text)
            self.assertIn("찾음: Synthetic image", text)
            self.assertIn("라이선스 안내", text)
            install_dir = se.image_install_dir(self.device.sdk, self.abi)
            self.assertTrue(os.path.isfile(os.path.join(install_dir, "system.img")))
            self.assertTrue(os.path.isfile(os.path.join(self.cache, "img.zip")))
            self.assertEqual(
                self.device.launches()[0]["argv"][:2], ["-avd", "Pleos_Connect_v2.1.0"]
            )
            zip_requests = [r for r in server.requests if r[0] == "/img.zip"]
            self.assertEqual(len(zip_requests), 1)
            code, text = self.run_main("--headless", "--no-browser")
            self.assertEqual(code, 0, text)
            self.assertIn("캐시에 파일이 있습니다", text)
            self.assertIn("이미 설치돼 있습니다", text)
            self.assertEqual(
                len([r for r in server.requests if r[0] == "/img.zip"]),
                1,
                "no second download",
            )

    def test_xml_that_lacks_this_abi_exits_3(self):
        xml = REPO_XML.format(
            abi="mips",
            display="x",
            size=1,
            checksum="a" * 40,
            url="http://127.0.0.1/x.zip",
        )
        server = RangeServer({"/img.xml": xml.encode("utf-8")})
        self.addCleanup(server.close)
        with mock.patch.object(se, "REPO_XML_URLS", {self.abi: server.url("/img.xml")}):
            code, text = self.run_main("--no-browser")
        self.assertEqual(code, 3)
        self.assertIn("mips", text)


@unittest.skipUnless(os.name == "posix", "the fake adb is a POSIX script")
class DirectorRehearsalTests(unittest.TestCase):
    """director.main() through the real Adb wrapper, a fake adb and a fake console + app."""

    def start(self, log_trips=True, **app_options):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = tmp.name
        self.device = FakeDevice(tmp.name, log_trips=log_trips, **app_options)
        self.addCleanup(self.device.close)
        for patcher in (
            mock.patch.dict(os.environ, self.device.env()),
            mock.patch("time.sleep"),
            mock.patch.object(director, "SESSION_INTERVAL_S", 0.05),
            mock.patch.object(director, "FRONT_STABLE_S", 0.0),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.device.flag("emulator.running")
        self.console_url = "http://127.0.0.1:%d" % self.device.app.port

    def run_main(self, *argv):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            args = list(argv)
            if "--console" not in args:
                args += ["--console", self.console_url]
            code = director.main(args)
        return code, buffer.getvalue()

    def test_all_scenarios_verify_pass_and_leave_screenshots(self):
        self.start()
        shots = os.path.join(self.tmp, "shots")
        code, text = self.run_main(
            "--scenario", "all", "--fast", "--verify", "--out", shots
        )
        self.assertEqual(code, 0, text)
        for key in ("commute", "client", "personal"):
            self.assertIn("[PASS] %s:" % key, text)
        self.assertIn("검증 결과: 3/3 PASS", text)
        self.assertEqual(len([n for n in os.listdir(shots) if n.endswith(".png")]), 15)
        with open(os.path.join(shots, "preflight.txt"), encoding="utf-8") as handle:
            preflight = handle.read()
        self.assertIn("current user: 10", preflight)
        self.assertIn("state=RUNNING_UNLOCKED", preflight)
        self.assertIn("front: u10 ai.umos.homescreen/.HomeActivity", preflight)
        self.assertIn('"bridge_ready": true', preflight)
        # per scenario: the map for the drive, the app again when the car parks
        calls = self.device.adb_calls()
        starts = [
            c[c.index("shell") + 1]
            for c in calls
            if "shell" in c and c[c.index("shell") + 1].startswith("am start")
        ]
        app = "am start -W --user 10 -n app.autobrief.car/.ui.MainActivity"
        nav = (
            "am start -W --user 10 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
            "-p ai.umos.maps.android.navigation.app"
        )
        self.assertEqual(starts.count(nav), 3)
        self.assertEqual(starts[0], app, "the home was in front: the app comes first")
        self.assertEqual(starts.count(app), 4)
        self.assertEqual(text.count("지도 화면으로 바꿉니다"), 3)
        self.assertEqual(
            sum(c[2:4] == ["logcat", "-c"] for c in calls),
            1,
            "logcat cleared once, before the first scenario",
        )
        self.assertIn(
            [
                "-s",
                "emulator-5554",
                "forward",
                "tcp:%d" % self.device.app.port,
                "tcp:8088",
            ],
            calls,
        )
        self.assertIn(
            ["-s", "emulator-5554", "emu", "geo", "fix", "127.037400", "37.544400"],
            calls,
        )
        requests = self.device.app.requests
        self.assertEqual(requests[0], ("GET", "/vehicle/state"))
        odometer_puts = [
            p for m, p in requests if m == "PUT" and p.startswith("/vehicle/odometer")
        ]
        self.assertEqual(
            odometer_puts[0], "/vehicle/odometer?traveled_distance_km=45200"
        )
        self.assertEqual(
            odometer_puts[-1], "/vehicle/odometer?traveled_distance_km=45236"
        )
        self.assertEqual(self.device.app.gear, "Park")
        app = self.device.app
        self.assertEqual(
            app.rejected, 0, "session and bridge were ready before the first write"
        )
        self.assertEqual(app.sessions_log[0], ("autobrief-director", False))
        self.assertEqual(app.sessions_log[-1], ("autobrief-director", True))
        self.assertFalse(app.active(), "the session is closed when the run ends")
        first_post = next(i for i, r in enumerate(requests) if r[0] == "POST")
        first_put = next(i for i, r in enumerate(requests) if r[0] == "PUT")
        self.assertLess(
            first_post, first_put, "the session starts before the first write"
        )

    def test_single_scenario_without_verify_never_clears_logcat(self):
        self.start()
        code, text = self.run_main("--scenario", "personal", "--fast")
        self.assertEqual(code, 0, text)
        self.assertNotIn("[PASS]", text)
        calls = self.device.adb_calls()
        self.assertFalse(any(c[2:4] == ["logcat", "-c"] for c in calls))
        # it only reads the app's log, for the position it reports
        self.assertTrue(any(c[2:4] == ["logcat", "-d"] for c in calls))
        self.assertIn("④ 주차합니다", text)

    def test_missing_trip_line_fails_verification_with_exit_1(self):
        self.start(log_trips=False)
        with mock.patch.object(director, "VERIFY_TIMEOUT_S", 0.3):
            code, text = self.run_main("--scenario", "commute", "--fast", "--verify")
        self.assertEqual(code, 1)
        self.assertIn("[FAIL] commute", text)
        self.assertIn("TRIP_CLOSED 줄이 하나도 없습니다", text)
        self.assertIn("검증 결과: 0/1 PASS", text)

    def test_unreachable_console_exits_3_with_a_bilingual_message(self):
        self.start()
        code, text = self.run_main(
            "--scenario", "commute", "--console", "http://127.0.0.1:%d" % free_port()
        )
        self.assertEqual(code, 3)
        self.assertIn("Cannot reach the Vehicle Virtual Console", text)
        self.assertIn("연결할 수 없습니다", text)

    def test_console_rejection_mid_demo_exits_3(self):
        self.start()
        original = self.device.app.apply

        def reject_odometer(path, query):
            if path == "/vehicle/odometer" and query["traveled_distance_km"] == "45205":
                return 400, "odometer is read-only while driving"
            return original(path, query)

        self.device.app.apply = reject_odometer
        code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 3)
        self.assertIn("odometer is read-only while driving", text)
        self.assertEqual(
            self.device.app.sessions_log[-1],
            ("autobrief-director", True),
            "the session is closed on the error path too",
        )

    def test_geo_fix_refusal_warns_once_and_the_demo_still_passes(self):
        self.start()
        self.device.flag("geo_ko")
        code, text = self.run_main("--scenario", "commute", "--fast", "--verify")
        self.assertEqual(code, 0, text)
        self.assertEqual(text.count("위치 이동을 건너뜁니다"), 1)
        self.assertIn("[PASS] commute", text)
        geo_calls = [
            c for c in self.device.adb_calls() if c[2:5] == ["emu", "geo", "fix"]
        ]
        self.assertEqual(
            len(geo_calls), 1, "after the first refusal the director stops asking"
        )

    def test_step_mode_needs_a_terminal(self):
        self.start()
        stdin = mock.Mock()
        stdin.isatty.return_value = False
        with mock.patch("sys.stdin", stdin), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                director.main(["--scenario", "all", "--mode", "step"])
        self.assertEqual(caught.exception.code, 2)

    def test_waits_for_the_vehicle_bridge_before_the_first_write(self):
        self.start(bridge_after_polls=4)
        code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 0, text)
        self.assertEqual(self.device.app.rejected, 0)
        self.assertIn("차량 데이터 연결(bridge)을 기다립니다", text)
        requests = self.device.app.requests
        first_put = next(i for i, r in enumerate(requests) if r[0] == "PUT")
        polls = sum(1 for r in requests[:first_put] if r == ("GET", "/vehicle/state"))
        self.assertGreaterEqual(polls, 5, "polled until gear.bridge_ready turned true")

    def test_a_bridge_that_never_comes_up_exits_3_without_writing(self):
        self.start(bridge_after_polls=10**9)
        with mock.patch.object(director, "BRIDGE_WAIT_S", 0.3):
            code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 3)
        self.assertIn("did not become ready", text)
        self.assertIn("차량 데이터 연결", text)
        self.assertFalse(any(r[0] == "PUT" for r in self.device.app.requests))
        self.assertEqual(self.device.app.sessions_log[-1], ("autobrief-director", True))

    def test_503_writes_are_retried_over_the_real_http_layer(self):
        self.start(reject_first_puts=2)
        code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 0, text)
        self.assertEqual(self.device.app.rejected, 2)
        self.assertEqual(text.count("(503 "), 2)
        self.assertIn("재시도 1/5", text)

    def test_a_console_that_stays_busy_fails_after_five_retries(self):
        self.start(reject_first_puts=100)
        code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 3)
        self.assertIn("HTTP 503", text)
        self.assertEqual(self.device.app.rejected, 6)
        self.assertEqual(self.device.app.sessions_log[-1], ("autobrief-director", True))

    def test_ctrl_c_closes_the_session(self):
        self.start()
        with mock.patch.object(
            director.Director, "run_scenario", side_effect=KeyboardInterrupt
        ):
            code, text = self.run_main("--scenario", "commute", "--fast")
        self.assertEqual(code, 130)
        self.assertEqual(self.device.app.sessions_log[-1], ("autobrief-director", True))
        self.assertFalse(self.device.app.active())

    def test_missing_adb_exits_2(self):
        self.start()
        with mock.patch.dict(
            os.environ, {"ANDROID_HOME": os.path.join(self.tmp, "nowhere")}
        ):
            code, text = self.run_main("--scenario", "commute")
        self.assertEqual(code, 2)
        self.assertIn("SDK Manager", text)


if __name__ == "__main__":
    unittest.main()



class OfficialImageReuseTests(unittest.TestCase):
    """An image installed by Android Studio's SDK Manager is used in place (no 4 GB download)."""

    def test_official_dir_and_sysdir(self):
        sdk = os.path.join("x", "sdk")
        self.assertEqual(
            se.official_image_dir(sdk, "arm64-v8a"),
            os.path.join(sdk, "system-images", "android-34", "pleos_car_emulator", "arm64-v8a"),
        )
        self.assertEqual(
            se.image_sysdir("x86_64", se.OFFICIAL_IMAGE_SUBDIRS),
            "system-images/android-34/pleos_car_emulator/x86_64/",
        )

    def test_avd_points_at_the_given_sysdir(self):
        text = se.build_avd_config(
            "x86_64", headless=False, sysdir="system-images/android-34/pleos_car_emulator/x86_64/"
        )
        self.assertIn("image.sysdir.1=system-images/android-34/pleos_car_emulator/x86_64/", text)
        self.assertNotIn("pleos-connect", text)
