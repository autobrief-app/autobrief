package app.autobrief.car

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.Tab
import app.autobrief.car.ui.VEHICLE_WAIT_MS
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Renders the real Compose screens on a car-display-sized canvas (2560x1440 at 160 dpi, the Pleos Connect
 * emulator's display) and a portrait form factor, from a core driven by the same events the car sends.
 * The images (build/outputs/roborazzi) are reviewed by eye; the test fails only if a screen cannot render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val ptDay = LocalDate.of(2026, 12, 3)

    /** Demo space with the fictional year, parked at home at 45,200 km on the presentation day. */
    private fun demoHarness(): Harness {
        val h = Harness(startAt = Harness.at(ptDay, 8, 5))
        h.core.setDemoModeNow(true, null)
        h.core.loadSampleHistoryNow()
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.core.onVehicle(VehicleEvent.Vin(SampleHistory.SAMPLE_VIN))
        h.core.onNavi(NaviEvent.Ready(true))
        h.at(SampleHistory.HOME.point)
        h.odo(45_200.0)
        h.gear(Gear.PARK)
        return h
    }

    /** [waitMs]: screen time to let pass before the shot (the test clock, so on-screen timers fire). */
    private fun shoot(core: AutoBriefCore, tab: Tab, name: String, night: Boolean = true, waitMs: Long = 0) {
        compose.setContent { AutoBriefScreen(core, mutableStateOf(tab), onFlush = {}, night = night) }
        compose.waitForIdle()
        if (waitMs > 0) {
            compose.mainClock.advanceTimeBy(waitMs)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun homeReady() = shoot(demoHarness().core, Tab.HOME, "01_home_ready")

    /** Just shifted into D, still standing (no UX restrictions yet): the screen is already the driving view. */
    @Test fun homeDriving() {
        val h = demoHarness()
        h.advance(60_000); h.gear(Gear.DRIVE)
        shoot(h.core, Tab.HOME, "02_home_trip_in_progress")
    }

    @Test fun drivingRestrictedView() {
        val h = demoHarness()
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(12 * 60_000); h.odo(45_212.0)
        h.core.setRestricted(true)
        shoot(h.core, Tab.HOME, "03_driving_restricted")
    }

    private fun commuted(): Harness {
        val h = demoHarness()
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(38 * 60_000); h.odo(45_218.0)
        h.at(SampleHistory.OFFICE.point); h.gear(Gear.PARK)
        return h
    }

    @Test fun homeAutoCommute() = shoot(commuted().core, Tab.HOME, "04_home_auto_commute")

    @Test fun homeAutoCommuteDay() = shoot(commuted().core, Tab.HOME, "05_home_auto_commute_day", night = false)

    private fun unknownPlace(): Harness {
        val h = commuted()
        h.core.dismissLastClosed()
        h.advance(5 * 3_600_000)
        h.gear(Gear.DRIVE); h.advance(25 * 60_000); h.odo(45_225.0)
        h.at(GeoPoint(37.5665, 126.9780)); h.gear(Gear.PARK)
        return h
    }

    @Test fun homeAskPurpose() = shoot(unknownPlace().core, Tab.HOME, "06_home_ask_purpose")

    @Config(qualifiers = "w1200dp-h1920dp-xlarge-port-mdpi")
    @Test fun homeAskPurposePortrait() = shoot(unknownPlace().core, Tab.HOME, "07_home_ask_purpose_portrait")

    @Test fun tripsList() = shoot(unknownPlace().core, Tab.TRIPS, "08_trips")

    /**
     * The report on the documents' sample day (DocsSample, 2026-10-23 23:06), so it shows the totals and code the text quotes,
     * with the company address set and every queued entry delivered, as the fleet console screenshots show it received.
     */
    @Test fun report() {
        val h = DocsSample.build(webhook = "https://fleet.example.com/hook")
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.core.onNavi(NaviEvent.Ready(true))
        h.core.markDeliveries(h.core.outbox.value.map { AutoBriefCore.Delivery(it.key, delivered = true, error = null) })
        shoot(h.core, Tab.REPORT, "09_report")
    }

    @Test fun settings() = shoot(commuted().core, Tab.SETTINGS, "10_settings")

    @Test fun liveFirstRun() {
        // a fresh install on a real car: no history, no settings, and a minute without gear or odometer from the vehicle service
        shoot(Harness(startAt = Harness.at(ptDay, 8, 0)).core, Tab.HOME, "11_live_first_run", waitMs = VEHICLE_WAIT_MS + 1_000)
    }

    @Test fun homeFamilyAndFuel() {
        val h = commuted()
        h.core.saveSettings(
            h.core.settings.value.copy(carUse = CarUse.OWN, manualKmPerLiter = 12.1, fuelPriceKrwPerLiter = 1_650),
            null,
            null,
        )
        h.core.startFamilyDrive()
        h.advance(3 * 3_600_000)
        h.gear(Gear.DRIVE); h.advance(22 * 60_000); h.odo(45_230.0); h.gear(Gear.PARK)
        shoot(h.core, Tab.HOME, "12_home_family_and_fuel")
    }
}
