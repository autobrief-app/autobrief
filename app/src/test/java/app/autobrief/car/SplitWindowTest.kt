package app.autobrief.car

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.NaviBookmark
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.Tab
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The window this app gets when the display shows two apps side by side: half of the application area, portrait. */
const val SPLIT_WIDTH_DP = 853
const val SPLIT_HEIGHT_DP = 1210
const val SPLIT_WINDOW = "w853dp-h1210dp-large-port-mdpi"
const val WINDOW_TAG = "window"

/** The states the screenshots and the window checks are drawn from, each driven by the events the car sends. */
object Scenes {
    val ptDay: LocalDate = LocalDate.of(2026, 12, 3)

    /** Demo space with the fictional year, parked at home at 45,200 km on the presentation day. */
    fun demo(): Harness {
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

    /** Just shifted into D with a navigation route, still standing. */
    fun driving(): Harness {
        val h = demo()
        h.core.onNavi(NaviEvent.RouteStarted("회사", 1_200))
        h.advance(60_000); h.gear(Gear.DRIVE)
        return h
    }

    /** Twelve minutes into the drive, with the car's driving restrictions on. */
    fun restricted(): Harness {
        val h = driving()
        h.advance(12 * 60_000); h.odo(45_212.0)
        h.core.setRestricted(true)
        return h
    }

    /** Parked at the office after the morning commute, classified by itself. */
    fun commuted(): Harness {
        val h = demo()
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(38 * 60_000); h.odo(45_218.0)
        h.at(SampleHistory.OFFICE.point); h.gear(Gear.PARK)
        return h
    }

    /** Parked at a place the app does not know, so the screen asks the purpose. */
    fun unknownPlace(): Harness {
        val h = commuted()
        h.core.dismissLastClosed()
        h.advance(5 * 3_600_000)
        h.gear(Gear.DRIVE); h.advance(25 * 60_000); h.odo(45_225.0)
        h.at(GeoPoint(37.5665, 126.9780)); h.gear(Gear.PARK)
        return h
    }

    /** The documents' sample day, with the company address set and every queued entry delivered. */
    fun report(): Harness {
        val h = DocsSample.build(webhook = "https://fleet.example.com/hook")
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.core.onNavi(NaviEvent.Ready(true))
        h.core.markDeliveries(h.core.outbox.value.map { AutoBriefCore.Delivery(it.key, delivered = true, error = null) })
        return h
    }

    /** An employee's own car (fuel reimbursement) with 가족 운행 driven after the commute. */
    fun familyAndFuel(): Harness {
        val h = commuted()
        h.core.saveSettings(
            h.core.settings.value.copy(carUse = CarUse.OWN, manualKmPerLiter = 12.1, fuelPriceKrwPerLiter = 1_650),
            null,
            null,
        )
        h.core.startFamilyDrive()
        h.advance(3 * 3_600_000)
        h.gear(Gear.DRIVE); h.advance(22 * 60_000); h.odo(45_230.0); h.gear(Gear.PARK)
        return h
    }

    /** After the commute, with the navigation app's bookmarks received. */
    fun settings(): Harness {
        val h = commuted()
        h.core.onNavi(
            NaviEvent.Bookmarks(
                listOf(
                    NaviBookmark(PlaceKind.HOME, "우리집", SampleHistory.HOME.point),
                    NaviBookmark(PlaceKind.OFFICE, "회사", SampleHistory.OFFICE.point),
                    NaviBookmark(PlaceKind.CLIENT, "한빛정밀", GeoPoint(37.2056, 127.0715)),
                ),
            ),
        )
        return h
    }
}

/**
 * The split window, guarded: in an 853 x 1210 dp portrait window every text stays inside the window and whole, no two
 * texts run into each other, every touch target keeps the 76dp floor inside the window, text stays at 24sp or more, and
 * whatever lies below the window can be scrolled to. Only the report's table may run wider than the window (it scrolls sideways).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = SPLIT_WINDOW)
class SplitWindowTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun ancestors(n: SemanticsNode): Sequence<SemanticsNode> = generateSequence(n.parent) { it.parent }

    private fun scrollsSideways(n: SemanticsNode) = ancestors(n).any { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }

    private fun scrollsVertically(n: SemanticsNode) = ancestors(n).any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }

    /** Where the node was laid out, whether or not a scrolling parent shows all of it. */
    private fun placed(n: SemanticsNode) = Rect(n.positionInRoot, n.size.toSize())

    /**
     * Every way [what] does not fit its window; empty when the screen is clean. The window is the node tagged [WINDOW_TAG],
     * or for a dialog (a root of its own) that root.
     */
    private fun problems(what: String): List<String> {
        compose.waitForIdle()
        val tagged = compose.onNodeWithTag(WINDOW_TAG).fetchSemanticsNode()
        val slack = 2f
        val floor = with(compose.density) { 76.dp.toPx() }
        val found = mutableListOf<String>()

        fun windowOf(n: SemanticsNode): Rect {
            val chain = generateSequence(n) { it.parent }.toList()
            return if (chain.any { it.id == tagged.id }) placed(tagged) else placed(chain.last())
        }

        fun outside(n: SemanticsNode, label: String) {
            val r = placed(n)
            val window = windowOf(n)
            val sideways = scrollsSideways(n)
            if (!sideways && (r.left < window.left - slack || r.right > window.right + slack)) {
                found += "$what: $label runs outside the window sideways (${r.left.toInt()}..${r.right.toInt()} of ${window.left.toInt()}..${window.right.toInt()})"
            }
            if (!scrollsVertically(n) && r.bottom > window.bottom + slack) found += "$what: $label lies below the window and nothing scrolls to it (bottom ${r.bottom.toInt()})"
            // a frame that clips (a card, a fixed row) shows less of it than was laid out, and nothing scrolls there
            val visible = n.boundsInRoot
            if (!sideways && visible.width > 0f && (visible.left > r.left + slack || visible.right < r.right - slack)) {
                found += "$what: $label is cut off sideways by its frame (${visible.left.toInt()}..${visible.right.toInt()} of ${r.left.toInt()}..${r.right.toInt()})"
            }
        }

        // the drawn texts: Txt puts the plain words on a box round the drawn Text, so only nodes that can report a layout count
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what shows no text", texts.isNotEmpty())
        // a dialog is a root of its own with its own coordinates: texts are compared within one root
        val shown = mutableListOf<Triple<String, Rect, Int>>()
        for (n in texts) {
            val words = n.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty().replace("\u2060", "")
            val label = "text '${words.take(28)}'"
            outside(n, label)
            val layouts = mutableListOf<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.firstOrNull()?.let { layout ->
                val size = layout.layoutInput.style.fontSize
                if (!(size.isSp && size.value >= 24f)) found += "$what: $label is $size"
                val lines = layout.multiParagraph
                val cut = lines.didExceedMaxLines || lines.height > n.size.height + 1f ||
                    (!layout.layoutInput.softWrap && lines.maxIntrinsicWidth > n.size.width + 1f)
                if (cut) found += "$what: $label is cut off inside ${n.size}"
                // a wrapped text in a column under five letters wide reads one or two letters a line
                if (layout.lineCount > 1 && n.size.width < 5 * with(compose.density) { size.toPx() }) found += "$what: $label is squeezed into ${n.size.width}px"
            }
            // a text field's own node spans the whole field, its floating label included: it is no second text over the label
            val field = n.config.getOrNull(SemanticsProperties.EditableText) != null
            val visible = n.boundsInRoot
            if (!field && visible.width > 0f && visible.height > 0f) shown += Triple(label, visible, generateSequence(n) { it.parent }.last().id)
        }
        for (i in shown.indices) {
            for (j in i + 1 until shown.size) {
                if (shown[i].third != shown[j].third) continue
                val a = shown[i].second
                val b = shown[j].second
                val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                if (w > slack && h > slack) found += "$what: ${shown[i].first} and ${shown[j].first} overlap by ${w.toInt()}x${h.toInt()}px"
            }
        }
        for (n in compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()) {
            val label = "button '${n.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.take(28)}'"
            if (n.size.width < floor - 0.5f || n.size.height < floor - 0.5f) found += "$what: $label is ${n.size}, under 76dp"
            outside(n, label)
        }
        return found
    }

    private fun assertFits(what: String, core: AutoBriefCore, tab: Tab) {
        val shownTab = mutableStateOf(tab)
        compose.setContent { Box(Modifier.fillMaxSize().testTag(WINDOW_TAG)) { AutoBriefScreen(core, shownTab, onFlush = {}, night = false) } }
        val found = problems(what)
        assertTrue("${found.size} layout problems in the split window:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun homeReadyFits() = assertFits("home, ready", Scenes.demo().core, Tab.HOME)

    @Test fun homeAutoCommuteFits() = assertFits("home, automatic commute", Scenes.commuted().core, Tab.HOME)

    @Test fun homeAskPurposeFits() = assertFits("home, asking the purpose", Scenes.unknownPlace().core, Tab.HOME)

    /** An unclassified trip that is no longer the one just driven: the home card lists it with its own purpose buttons. */
    @Test fun homePendingTripFits() {
        val h = Scenes.unknownPlace()
        h.core.dismissLastClosed()
        assertFits("home, a trip waiting for its purpose", h.core, Tab.HOME)
    }

    @Test fun homeFamilyAndFuelFits() = assertFits("home, family drive and fuel", Scenes.familyAndFuel().core, Tab.HOME)

    /** A fresh install on a real car: the first-run card and the waiting states. */
    @Test fun homeFirstRunFits() = assertFits("home, first run", Harness(startAt = Harness.at(Scenes.ptDay, 8, 0)).core, Tab.HOME)

    /** 바꾸기 on the automatic record opens the reasons and the purpose buttons in the card. */
    @Test fun homeChangingThePurposeFits() {
        val core = Scenes.commuted().core
        val tab = mutableStateOf(Tab.HOME)
        compose.setContent { Box(Modifier.fillMaxSize().testTag(WINDOW_TAG)) { AutoBriefScreen(core, tab, onFlush = {}, night = false) } }
        compose.onNodeWithText("바꾸기").performClick()
        val found = problems("home, changing the purpose")
        assertTrue("${found.size} layout problems in the split window:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A row of the trips list opens the trip's detail, where its purpose is set: a dialog over the window. */
    @Test fun tripDetailFits() {
        val core = Scenes.unknownPlace().core
        val tab = mutableStateOf(Tab.TRIPS)
        compose.setContent { Box(Modifier.fillMaxSize().testTag(WINDOW_TAG)) { AutoBriefScreen(core, tab, onFlush = {}, night = false) } }
        compose.onNodeWithText("목적 정하기 ›", useUnmergedTree = true).performClick()
        compose.onNodeWithText("닫기").assertExists()
        val found = problems("trip detail")
        assertTrue("${found.size} layout problems in the split window:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun homeTripInProgressFits() = assertFits("home, trip in progress", Scenes.driving().core, Tab.HOME)

    @Test fun drivingRestrictedFits() = assertFits("driving, restricted", Scenes.restricted().core, Tab.HOME)

    @Test fun tripsFit() = assertFits("trips", Scenes.unknownPlace().core, Tab.TRIPS)

    @Test fun reportFits() = assertFits("report", Scenes.report().core, Tab.REPORT)

    @Test fun settingsFit() = assertFits("settings", Scenes.settings().core, Tab.SETTINGS)

    /** The Configuration says 2560 x 1440 while the window is 853 x 1210: the layout follows the window. */
    @Config(qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
    @Test fun windowInsideTheWideConfigurationFits() {
        val core = Scenes.unknownPlace().core
        val tab = mutableStateOf(Tab.HOME)
        compose.setContent {
            Box(Modifier.requiredSize(SPLIT_WIDTH_DP.dp, SPLIT_HEIGHT_DP.dp).testTag(WINDOW_TAG)) { AutoBriefScreen(core, tab, onFlush = {}, night = false) }
        }
        val found = problems("home in a split window, wide Configuration")
        assertTrue("${found.size} layout problems in the split window:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
