"""Device-free tests for demo/navmain_check.py (stdlib unittest, Python 3.9+).

Run from the repository root:  python3 -m unittest discover -s demo -v
"""

import os
import sys
import tempfile
import unittest
from dataclasses import replace
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import director  # noqa: E402
import navmain_check as nm  # noqa: E402
from test_demo_kit import FakeAdb, FakeClock, FakeConsole  # noqa: E402

APP = "app.autobrief.car"
HOME = "ai.umos.homescreen/.HomeActivity"


def window(number, title, package, kind, visible=True, user=10, extra=""):
    """One window as Android 14's `dumpsys window windows` prints it (the fields the check reads)."""
    return (
        "  Window #%d Window{a1b2c%d u%d %s}:\n"
        "    mDisplayId=0 rootTaskId=1 mSession=Session{c6a7f1e 2345:u10a175} mClient=android.os.BinderProxy@e1e0ff\n"
        "    mOwnerUid=1010175 showForAllUsers=false package=%s appop=%s\n"
        "    mAttrs={(16,16)(wrapxwrap) gr=TOP END sim={adjust=pan} ty=%s fmt=TRANSLUCENT\n"
        "      fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED}\n"
        "    mHasSurface=%s isReadyForDisplay()=%s mWindowRemovalAllowed=false\n"
        "%s"
        % (
            number,
            number,
            user,
            title,
            package,
            "SYSTEM_ALERT_WINDOW" if kind == "APPLICATION_OVERLAY" else "NONE",
            kind,
            str(visible).lower(),
            str(visible).lower(),
            extra,
        )
    )


def visibility(visible):
    return "    isOnScreen=%s\n    isVisible=%s\n" % ((str(visible).lower(),) * 2)


NAV_WINDOW = window(
    1,
    "ai.umos.maps.android.navigation.app/ai.umos.maps.MainActivity",
    director.NAV_PACKAGE,
    "BASE_APPLICATION",
    extra=visibility(True),
)
HUD_WINDOW = window(
    2, "AutoBriefHud", APP, "APPLICATION_OVERLAY", extra=visibility(True)
)
DUMP_HEAD = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
DRIVE_DUMP = DUMP_HEAD + HUD_WINDOW + NAV_WINDOW
PARK_DUMP = DUMP_HEAD + NAV_WINDOW

SCENARIO = nm.navmain_scenario()
OPENED = "11-15 10:00:01.000 I AutoBriefLog: TRIP_OPENED odo=45259.0"
SHOWN = "11-15 10:00:01.100 I AutoBriefLog: HUD_SHOWN km=0.0"
CLOSED = "11-15 10:00:09.000 I AutoBriefLog: TRIP_CLOSED id=t1 start=45259.0 end=45264.0 km=5.0 purpose=UNCLASSIFIED"
HIDDEN = "11-15 10:00:09.100 I AutoBriefLog: HUD_HIDDEN reason=trip_ended"


def resumed(*components, user=10):
    lines = ["  topResumedActivity=ActivityRecord{1 u%d %s t1}" % (user, components[0])]
    lines += [
        "  ResumedActivity: ActivityRecord{2 u%d %s t2}" % (user, c)
        for c in components[1:]
    ]
    return "\n".join(lines) + "\n"


def passing_evidence(front="navigation"):
    shown = FakeAdb.NAV if front == "navigation" else HOME
    return nm.Evidence(
        front=front,
        user=10,
        scenario=SCENARIO,
        activities_drive=resumed(shown),
        logcat_drive="\n".join([OPENED, SHOWN]),
        windows_drive=DRIVE_DUMP,
        logcat_park="\n".join([OPENED, SHOWN, CLOSED, HIDDEN]),
        windows_park=PARK_DUMP,
        activities_after=resumed(FakeAdb.APP),
    )


class WindowDumpTests(unittest.TestCase):
    def test_reads_owner_type_and_visibility_of_each_window(self):
        windows = nm.parse_windows(DRIVE_DUMP)
        self.assertEqual(
            [w.title for w in windows],
            [
                "AutoBriefHud",
                "ai.umos.maps.android.navigation.app/ai.umos.maps.MainActivity",
            ],
        )
        hud = windows[0]
        self.assertEqual(
            (hud.user, hud.package, hud.type, hud.visible, hud.overlay),
            (10, APP, "APPLICATION_OVERLAY", True, True),
        )
        self.assertFalse(windows[1].overlay)

    def test_only_the_apps_own_overlay_windows_count(self):
        dump = (
            DUMP_HEAD
            + window(1, "Other", "com.other", "APPLICATION_OVERLAY")
            + window(2, APP + "/.ui.MainActivity", APP, "BASE_APPLICATION")
        )
        self.assertEqual(nm.app_overlays(nm.parse_windows(dump)), [])
        self.assertEqual(
            [w.title for w in nm.app_overlays(nm.parse_windows(DRIVE_DUMP))],
            ["AutoBriefHud"],
        )

    def test_the_numeric_type_of_an_older_dump_is_the_overlay_too(self):
        dump = DUMP_HEAD + window(
            1, "AutoBriefHud", APP, "2038", extra=visibility(True)
        )
        self.assertTrue(nm.parse_windows(dump)[0].overlay)

    def test_is_visible_wins_over_the_surface_fields(self):
        dump = DUMP_HEAD + window(
            1,
            "AutoBriefHud",
            APP,
            "APPLICATION_OVERLAY",
            visible=True,
            extra=visibility(False),
        )
        self.assertIs(nm.parse_windows(dump)[0].visible, False)

    def test_without_is_visible_a_ready_surface_is_visible(self):
        self.assertIs(
            nm.parse_windows(
                DUMP_HEAD
                + window(1, "AutoBriefHud", APP, "APPLICATION_OVERLAY", visible=True)
            )[0].visible,
            True,
        )
        self.assertIs(
            nm.parse_windows(
                DUMP_HEAD
                + window(1, "AutoBriefHud", APP, "APPLICATION_OVERLAY", visible=False)
            )[0].visible,
            False,
        )

    def test_a_dump_that_says_nothing_about_visibility_is_unknown_not_visible(self):
        dump = (
            DUMP_HEAD
            + "  Window #1 Window{abc u10 AutoBriefHud}:\n    mOwnerUid=1 package=%s appop=SYSTEM_ALERT_WINDOW\n    mAttrs={ty=APPLICATION_OVERLAY}\n"
            % APP
        )
        hud = nm.parse_windows(dump)[0]
        self.assertIsNone(hud.visible)
        e = replace(passing_evidence(), windows_drive=dump)
        self.assertFalse(nm.judge(e)[2].ok)

    def test_a_window_without_an_owner_line_is_nobodys(self):
        dump = (
            DUMP_HEAD
            + "  Window #1 Window{abc u10 AutoBriefHud}:\n    mAttrs={ty=APPLICATION_OVERLAY}\n    isVisible=true\n"
        )
        self.assertIsNone(nm.parse_windows(dump)[0].package)
        self.assertEqual(nm.app_overlays(nm.parse_windows(dump)), [])

    def test_an_empty_dump_has_no_windows(self):
        self.assertEqual(nm.parse_windows(""), [])
        self.assertEqual(nm.parse_windows(DUMP_HEAD), [])


class LogTests(unittest.TestCase):
    def test_hud_shown_counts_only_after_the_trip_opened(self):
        self.assertIsNone(
            nm.shown_after_trip_start(nm.log_events("\n".join([SHOWN, OPENED])))
        )
        self.assertEqual(
            nm.shown_after_trip_start(nm.log_events("\n".join([OPENED, SHOWN]))).line,
            SHOWN,
        )

    def test_hud_hidden_counts_only_after_shown_and_for_the_trip_end(self):
        other = "I AutoBriefLog: HUD_HIDDEN reason=no_permission"
        self.assertIsNone(
            nm.hidden_after_shown(nm.log_events("\n".join([HIDDEN, SHOWN])))
        )
        self.assertIsNone(
            nm.hidden_after_shown(nm.log_events("\n".join([SHOWN, other])))
        )
        self.assertEqual(
            nm.hidden_after_shown(
                nm.log_events("\n".join([other, SHOWN, CLOSED, HIDDEN]))
            ).reason,
            "trip_ended",
        )

    def test_the_trip_is_found_by_its_odometers_and_km(self):
        self.assertEqual(nm.find_trip(CLOSED, SCENARIO).km, 5.0)
        self.assertIsNone(nm.find_trip(CLOSED.replace("km=5.0", "km=4.0"), SCENARIO))
        self.assertIsNone(
            nm.find_trip(CLOSED.replace("start=45259.0", "start=45248.0"), SCENARIO)
        )
        self.assertIsNone(nm.find_trip("", SCENARIO))


class FrontTests(unittest.TestCase):
    def test_navigation_in_front_and_the_app_not_on_screen(self):
        self.assertTrue(nm.front_state(resumed(FakeAdb.NAV), 10, "navigation")[0])
        # the Pleos home is the top activity and shows the map in its panel
        self.assertTrue(nm.front_state(resumed(HOME, FakeAdb.NAV), 10, "navigation")[0])

    def test_the_app_still_on_screen_next_to_the_map_is_not_navigation_as_main_screen(
        self,
    ):
        ok, seen = nm.front_state(resumed(FakeAdb.NAV, FakeAdb.APP), 10, "navigation")
        self.assertFalse(ok)
        self.assertIn(FakeAdb.APP, seen)

    def test_the_app_in_front_is_a_fail_for_either_choice(self):
        self.assertFalse(nm.front_state(resumed(FakeAdb.APP), 10, "navigation")[0])
        self.assertFalse(nm.front_state(resumed(FakeAdb.APP), 10, "home")[0])

    def test_home_is_the_home_activity_not_the_map(self):
        self.assertTrue(nm.front_state(resumed(HOME), 10, "home")[0])
        self.assertFalse(nm.front_state(resumed(FakeAdb.NAV), 10, "home")[0])
        self.assertFalse(nm.front_state(resumed(HOME), 10, "navigation")[0])

    def test_another_users_activity_does_not_count(self):
        self.assertFalse(
            nm.front_state(resumed(FakeAdb.NAV, user=0), 10, "navigation")[0]
        )

    def test_an_unknown_front_is_refused(self):
        with self.assertRaises(ValueError):
            nm.front_component("launcher")


class JudgeTests(unittest.TestCase):
    def oks(self, evidence):
        return [c.ok for c in nm.judge(evidence)]

    def test_every_check_passes_on_the_expected_evidence(self):
        for front in nm.FRONTS:
            checks = nm.judge(passing_evidence(front))
            self.assertEqual([c.number for c in checks], [1, 2, 3, 4, 5, 6])
            self.assertTrue(all(c.ok for c in checks), [c.line() for c in checks])
            self.assertEqual(nm.result_line(checks), "검증 결과: 6/6 PASS")
            self.assertTrue(
                all(c.line().startswith("[PASS] %d " % c.number) for c in checks)
            )

    def test_each_broken_observation_fails_its_own_check_only(self):
        e = passing_evidence()
        broken = {
            1: replace(e, activities_drive=resumed(FakeAdb.APP)),
            2: replace(e, logcat_drive=OPENED),
            3: replace(e, windows_drive=PARK_DUMP),
            4: replace(e, logcat_park="\n".join([OPENED, SHOWN, HIDDEN])),
            6: replace(e, activities_after=resumed(FakeAdb.NAV)),
        }
        for number, evidence in broken.items():
            self.assertEqual(
                self.oks(evidence), [n != number for n in range(1, 7)], number
            )

    def test_check_five_needs_the_log_line_and_the_window_gone(self):
        e = passing_evidence()
        self.assertFalse(
            self.oks(replace(e, logcat_park="\n".join([OPENED, SHOWN, CLOSED])))[4]
        )
        self.assertFalse(self.oks(replace(e, windows_park=DRIVE_DUMP))[4])
        # a window still listed but not visible is not gone
        lingering = DUMP_HEAD + window(
            2,
            "AutoBriefHud",
            APP,
            "APPLICATION_OVERLAY",
            visible=False,
            extra=visibility(False),
        )
        self.assertFalse(self.oks(replace(e, windows_park=lingering))[4])

    def test_an_overlay_that_is_listed_but_not_visible_fails_check_three(self):
        hidden = DUMP_HEAD + window(
            2,
            "AutoBriefHud",
            APP,
            "APPLICATION_OVERLAY",
            visible=False,
            extra=visibility(False),
        )
        self.assertFalse(self.oks(replace(passing_evidence(), windows_drive=hidden))[2])

    def test_a_front_app_that_did_not_come_fails_everything_and_says_why(self):
        checks = nm.judge(
            replace(passing_evidence(), front_error="지도 앱이 앞에 뜨지 않았습니다")
        )
        self.assertEqual([c.ok for c in checks], [False] * 6)
        self.assertIn("지도 앱이 앞에 뜨지 않았습니다", checks[0].line())
        self.assertTrue(checks[0].line().startswith("[FAIL] 1 "))
        self.assertEqual(nm.result_line(checks), "검증 결과: 0/6 PASS")

    def test_the_app_that_does_not_reopen_fails_check_six(self):
        checks = nm.judge(
            replace(passing_evidence(), reopen_error="앱이 앞에 오지 않았습니다")
        )
        self.assertEqual([c.ok for c in checks], [True] * 5 + [False])


class PlanTests(unittest.TestCase):
    def test_the_trip_is_the_kits_own_without_the_steps_that_change_the_front(self):
        beats = nm.background_beats(SCENARIO, director.FAST_KM_INTERVAL)
        self.assertEqual(
            [name for name, _ in beats], ["ready", "depart", "drive", "arrive", "park"]
        )
        steps = [s for _, body in beats for s in body]
        self.assertFalse([s for s in steps if isinstance(s, nm.FRONT_CHANGING_STEPS)])
        self.assertIn(director.SetSeat("driver", True), steps)
        self.assertIn(director.SetGear("Drive"), steps)
        self.assertEqual(
            [s.km for s in steps if isinstance(s, director.SetOdometer)],
            list(range(45259, 45265)),
        )
        self.assertEqual(
            sum(1 for s in steps if isinstance(s, director.ParkVehicle)), 2
        )
        # everything else of the kit's plan is played as it is, in its order
        kit = [
            s
            for b in director.plan_beats(SCENARIO, director.FAST_KM_INTERVAL)
            for s in b.steps
        ]
        self.assertEqual(
            steps, [s for s in kit if not isinstance(s, nm.FRONT_CHANGING_STEPS)]
        )

    def test_the_existing_scenarios_still_bring_the_app_to_the_front(self):
        for scenario in director.SCENARIOS + director.OPTIONAL_SCENARIOS:
            steps = [s for b in director.plan_beats(scenario) for s in b.steps]
            self.assertEqual(
                sum(1 for s in steps if isinstance(s, director.ShowApp)),
                2,
                scenario.key,
            )

    def test_the_trip_goes_on_from_the_optional_scenes(self):
        self.assertEqual(
            SCENARIO.odometer_start, director.OPTIONAL_SCENARIOS[-1].odometer_end
        )


class HudAdb(FakeAdb):
    """An emulator whose AutoBrief does what the app does: the pill shows while a trip is open, the
    overlay is allowed and the app's own screen is not in front."""

    def __init__(self, console):
        super().__init__()
        self.console = console
        self.allowed = False
        self.nav_starts = True
        self.cleared = 0

    def shell(self, *args, timeout=None):
        line = " ".join(args)
        if line.startswith("appops set") and line.endswith(
            "%s SYSTEM_ALERT_WINDOW allow" % APP
        ):
            self.allowed = True
            self.shells.append(line)
            return ""
        if line.startswith("appops get"):
            return "SYSTEM_ALERT_WINDOW: %s\n" % (
                "allow" if self.allowed else "default"
            )
        if line == "dumpsys window windows":
            return DUMP_HEAD + (HUD_WINDOW if self.pill() else "") + NAV_WINDOW
        if line.startswith("am start") and "-n " + HOME in line:
            self.shells.append(line)
            self.front = HOME
            return "Status: ok\n"
        if (
            line.startswith("am start")
            and "-p " + director.NAV_PACKAGE in line
            and not self.nav_starts
        ):
            self.shells.append(line)
            return "Status: ok\n"
        return super().shell(*args, timeout=timeout)

    def gears(self):
        return [c[1] for c in self.console.calls if c[0] == "gear"]

    def trip_open(self):
        gears = self.gears()
        return "Drive" in gears and gears[-1] != "Park"

    def trip_closed(self):
        gears = self.gears()
        return "Drive" in gears and gears[-1] == "Park"

    def pill(self):
        return self.trip_open() and self.allowed and self.front != self.APP

    def logcat_clear(self, **kwargs):
        self.cleared += 1

    def logcat_dump(self, *args, **kwargs):
        lines = []
        if self.trip_open() or self.trip_closed():
            lines.append(OPENED)
            if self.allowed and self.front != self.APP:
                lines.append(SHOWN)
        if self.trip_closed():
            lines.append(CLOSED)
            if SHOWN in lines:
                lines.append(HIDDEN)
        return "\n".join(lines)


class RunTests(unittest.TestCase):
    def setUp(self):
        self.console = FakeConsole()
        self.adb = HudAdb(self.console)
        self.clock = FakeClock()
        self.said = []
        self.out = tempfile.mkdtemp()

    def check(self, front="navigation", out=True):
        return nm.NavMainCheck(
            self.console,
            self.adb,
            front,
            out_dir=self.out if out else None,
            km_interval=director.FAST_KM_INTERVAL,
            sleep=self.clock.sleep,
            clock=self.clock.now,
            say_fn=self.said.append,
        )

    def test_a_trip_behind_navigation_passes_all_six(self):
        checks = self.check().run()
        self.assertEqual(
            nm.result_line(checks), "검증 결과: 6/6 PASS", [c.line() for c in checks]
        )
        self.assertIn(
            "appops set --user 10 %s SYSTEM_ALERT_WINDOW allow" % APP, self.adb.shells
        )
        self.assertEqual(self.adb.cleared, 1)
        self.assertEqual(
            sorted(os.listdir(self.out)),
            [
                "navmain_drive.png",
                "navmain_logcat.txt",
                "navmain_park.png",
                "navmain_windows_drive.txt",
                "navmain_windows_park.txt",
            ],
        )
        odometers = [c[1] for c in self.console.calls if c[0] == "odometer"]
        self.assertEqual((odometers[0], odometers[-1]), (45259, 45264))
        self.assertIn(("seat", "driver", True), self.console.calls)

    def test_the_app_is_brought_to_the_front_only_after_everything_was_read(self):
        self.check().run()
        shells = self.adb.shells
        starts = [i for i, line in enumerate(shells) if line.startswith("am start") and "-n " + FakeAdb.APP in line]
        self.assertEqual(len(starts), 1, shells)
        # nothing is read from the emulator's windows after it, and the whole trip was driven before it
        self.assertFalse([line for line in shells[starts[0]:] if line.startswith("dumpsys window")])
        self.assertEqual(self.adb.gears()[-1], "Park")
        self.assertEqual(self.adb.front, FakeAdb.APP)

    def test_navigation_is_never_force_stopped(self):
        self.check().run()
        self.assertFalse(
            [line for line in self.adb.shells if "force-stop" in line or " -S " in line]
        )

    def test_home_can_be_the_front_app(self):
        checks = self.check("home").run()
        self.assertEqual(
            nm.result_line(checks), "검증 결과: 6/6 PASS", [c.line() for c in checks]
        )
        self.assertFalse(
            [line for line in self.adb.shells if "-p " + director.NAV_PACKAGE in line]
        )

    def test_a_front_app_that_does_not_come_is_a_fail_and_nothing_is_driven(self):
        self.adb.nav_starts = False
        checks = self.check().run()
        self.assertEqual(nm.result_line(checks), "검증 결과: 0/6 PASS")
        self.assertEqual(self.console.calls, [])
        self.assertFalse(
            [line for line in self.adb.shells if "-n " + HOME in line]
        )  # no silent switch to the home

    def test_without_the_pill_the_trip_checks_still_tell_recording_from_showing(self):
        original = self.adb.shell
        # the platform does not honour the appops change: the app never shows the pill
        self.adb.shell = lambda *args, timeout=None: (
            "" if args[:2] == ("appops", "set") else original(*args, timeout=timeout)
        )
        checks = self.check(out=False).run()
        self.assertEqual(
            [c.ok for c in checks],
            [True, False, False, True, False, True],
            [c.line() for c in checks],
        )

    def test_the_cli_requires_the_front_app(self):
        parser = nm.build_parser()
        with self.assertRaises(SystemExit):
            with (
                open(os.devnull, "w") as devnull,
                mock.patch("sys.stderr", devnull),
            ):
                parser.parse_args([])
        self.assertEqual(parser.parse_args(["--front", "navigation"]).out, nm.OUT_DIR)


if __name__ == "__main__":
    unittest.main()
