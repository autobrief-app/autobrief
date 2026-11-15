package app.autobrief.car

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.LocalThemeVariant
import app.autobrief.car.ui.Tab
import app.autobrief.car.ui.ThemeVariant
import app.autobrief.car.ui.VEHICLE_WAIT_MS
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose screens on a car-display-sized canvas (2560x1440 at 160 dpi, the Pleos Connect
 * emulator's display) and a portrait form factor, from a core driven by the same events the car sends.
 * The images (build/outputs/roborazzi) are reviewed by eye; the test fails only if a screen cannot render.
 * The key screens are also drawn in each design variant of [variants], night and day, into build/outputs/roborazzi/<VARIANT>/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val ptDay = Scenes.ptDay

    private fun demoHarness(): Harness = Scenes.demo()

    /** The variants the key screens are drawn in: -Pautobrief.variants=CURRENT,A (or =all); unset, the build's own. */
    private val variants: List<ThemeVariant> = System.getProperty("autobrief.variants").orEmpty().trim().let { prop ->
        when {
            prop.isEmpty() -> listOf(ThemeVariant.BUILD)
            prop.equals("all", ignoreCase = true) -> ThemeVariant.values().toList()
            else -> prop.split(',').map { ThemeVariant.valueOf(it.trim().uppercase()) }
        }
    }

    /**
     * [waitMs]: screen time to let pass before the shot (the test clock, so on-screen timers fire). [inVariants]: a key screen,
     * then also drawn in every [variants] entry, night and day, by switching the theme of the same composition.
     */
    private fun shoot(core: AutoBriefCore, tab: Tab, name: String, night: Boolean = true, waitMs: Long = 0, inVariants: Boolean = false) {
        val look = mutableStateOf(ThemeVariant.BUILD to night)
        val tabState = mutableStateOf(tab)
        compose.setContent {
            CompositionLocalProvider(LocalThemeVariant provides look.value.first) {
                AutoBriefScreen(core, tabState, onFlush = {}, night = look.value.second)
            }
        }
        compose.waitForIdle()
        if (waitMs > 0) {
            compose.mainClock.advanceTimeBy(waitMs)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        if (!inVariants) return
        for (variant in variants) {
            for (n in listOf(true, false)) {
                look.value = variant to n
                compose.waitForIdle()
                compose.onRoot().captureRoboImage("build/outputs/roborazzi/${variant.name}/${name}_${if (n) "night" else "day"}.png")
            }
        }
    }

    @Test fun homeReady() = shoot(demoHarness().core, Tab.HOME, "01_home_ready")

    /** Just shifted into D, still standing (no UX restrictions yet): the screen is already the driving view. */
    @Test fun homeDriving() = shoot(Scenes.driving().core, Tab.HOME, "02_home_trip_in_progress", inVariants = true)

    @Test fun drivingRestrictedView() = shoot(Scenes.restricted().core, Tab.HOME, "03_driving_restricted", inVariants = true)

    private fun commuted(): Harness = Scenes.commuted()

    @Test fun homeAutoCommute() = shoot(commuted().core, Tab.HOME, "04_home_auto_commute", inVariants = true)

    @Test fun homeAutoCommuteDay() = shoot(commuted().core, Tab.HOME, "05_home_auto_commute_day", night = false)

    private fun unknownPlace(): Harness = Scenes.unknownPlace()

    @Test fun homeAskPurpose() = shoot(unknownPlace().core, Tab.HOME, "06_home_ask_purpose")

    @Config(qualifiers = "w1200dp-h1920dp-xlarge-port-mdpi")
    @Test fun homeAskPurposePortrait() = shoot(unknownPlace().core, Tab.HOME, "07_home_ask_purpose_portrait")

    @Test fun tripsList() = shoot(unknownPlace().core, Tab.TRIPS, "08_trips", inVariants = true)

    /**
     * The report on the documents' sample day (DocsSample, 2026-10-23 23:06), so it shows the totals and code the text quotes,
     * with the company address set and every queued entry delivered, as the fleet console screenshots show it received.
     */
    @Test fun report() = shoot(Scenes.report().core, Tab.REPORT, "09_report", inVariants = true)

    @Test fun settings() = shoot(Scenes.settings().core, Tab.SETTINGS, "10_settings", inVariants = true)

    // The split window: the platform shows the navigation app and this app side by side, so this app gets a portrait
    // window of about 853 x 1210 dp. Day look, into build/outputs/roborazzi/portrait/.

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitHomeAutoCommute() = shoot(Scenes.commuted().core, Tab.HOME, "portrait/01_home_auto_commute", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitHomeAskPurpose() = shoot(Scenes.unknownPlace().core, Tab.HOME, "portrait/02_home_ask_purpose", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitHomeTripInProgress() = shoot(Scenes.driving().core, Tab.HOME, "portrait/03_home_trip_in_progress", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitDrivingRestricted() = shoot(Scenes.restricted().core, Tab.HOME, "portrait/04_driving_restricted", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitTrips() = shoot(Scenes.unknownPlace().core, Tab.TRIPS, "portrait/05_trips", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitReport() = shoot(Scenes.report().core, Tab.REPORT, "portrait/06_report", night = false)

    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitSettings() = shoot(Scenes.settings().core, Tab.SETTINGS, "portrait/07_settings", night = false)

    /**
     * The Configuration still says 2560 x 1440 (the whole display) while the app is drawn into an 853 x 1210 dp window:
     * the layout has to follow the window it is given, not the Configuration.
     */
    @Test fun splitWindowInsideTheWideConfiguration() {
        val core = Scenes.unknownPlace().core
        val tab = mutableStateOf(Tab.HOME)
        compose.setContent {
            Box(Modifier.requiredSize(SPLIT_WIDTH_DP.dp, SPLIT_HEIGHT_DP.dp).testTag(WINDOW_TAG)) { AutoBriefScreen(core, tab, onFlush = {}, night = false) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(WINDOW_TAG).captureRoboImage("build/outputs/roborazzi/portrait/08_home_ask_purpose_window_in_wide_configuration.png")
    }

    /** A row of the trips list opened in the split window: the trip's detail, where its purpose is set. */
    @Config(qualifiers = SPLIT_WINDOW)
    @Test fun splitTripDetail() {
        val core = Scenes.unknownPlace().core
        val tab = mutableStateOf(Tab.TRIPS)
        compose.setContent { AutoBriefScreen(core, tab, onFlush = {}, night = false) }
        compose.onNodeWithText("목적 정하기 ›", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/portrait/09_trip_detail.png")
    }

    @Test fun liveFirstRun() {
        // a fresh install on a real car: no history, no settings, and a minute without gear or odometer from the vehicle service
        shoot(Harness(startAt = Harness.at(ptDay, 8, 0)).core, Tab.HOME, "11_live_first_run", waitMs = VEHICLE_WAIT_MS + 1_000)
    }

    @Test fun homeFamilyAndFuel() = shoot(Scenes.familyAndFuel().core, Tab.HOME, "12_home_family_and_fuel")
}
