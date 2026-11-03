package app.autobrief.car

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.ui.AutoBriefRoot
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.Tab
import app.autobrief.car.ui.VEHICLE_WAIT_MS
import app.autobrief.car.ui.keepWords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The screen rules a driver meets: the driving layout out of P, correction reasons, the vehicle wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class UiFlowTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun show(core: AutoBriefCore, tab: Tab) {
        compose.setContent { AutoBriefScreen(core, mutableStateOf(tab), onFlush = {}, night = true) }
        compose.waitForIdle()
    }

    private fun reasonOf(core: AutoBriefCore, id: String): String {
        val amend = core.ledgerFor(id).last()
        assertEquals(LedgerKind.AMEND, amend.kind)
        return Json.parseToJsonElement(amend.body).jsonObject.getValue("reason").jsonPrimitive.content
    }

    @Test fun theScreenSaysLoadingUntilTheStoredLogbookIsRead() {
        val core = MutableStateFlow<AutoBriefCore?>(null)
        compose.setContent { AutoBriefRoot(core, mutableStateOf(Tab.HOME), onFlush = {}, night = true) }
        compose.waitForIdle()
        compose.onNodeWithText("불러오는 중").assertExists()
        compose.onNodeWithText("설정").assertDoesNotExist()
        core.value = Harness().core
        compose.waitForIdle()
        compose.onNodeWithText("불러오는 중").assertDoesNotExist()
        compose.onNodeWithText("설정").assertExists()
    }

    @Test fun outOfParkTheScreenIsTheDrivingViewEvenAtAStandstill() {
        val h = Harness()
        h.odo(1_000.0); h.gear(Gear.PARK)
        show(h.core, Tab.HOME)
        compose.onNodeWithText("설정").assertExists() // the tab rail of the parked layout
        h.gear(Gear.DRIVE) // standing in D: CarUxRestrictions are not active yet
        compose.waitForIdle()
        assertFalse(h.core.restricted.value)
        compose.onAllNodesWithText("설정").assertCountEquals(0)
        compose.onNodeWithText("도착해서 P에 두면 목적을 여쭙니다").assertExists()
        h.advance(60_000); h.odo(1_003.0); h.gear(Gear.PARK)
        compose.waitForIdle()
        compose.onNodeWithText("설정").assertExists()
    }

    @Test fun tripDetailCorrectionWaitsForAReasonChipAndKeepsTheOptionalMemo() {
        val h = Harness(AppSettings(users = listOf(CarUser("김기현", "영업팀"))))
        h.drive(1_000.0, 12.0, null, null)
        val id = h.core.trips.value.last().id
        assertEquals(AutoBriefCore.ClassifyResult.OK, h.core.classifyTrip(id, TripPurpose.BUSINESS, null, "", ClassifiedBy.SCREEN))
        show(h.core, Tab.TRIPS)
        compose.onNode(button and hasText("12.0 km")).performClick() // the row opens the detail
        val save = compose.onNode(button and hasText("저장"))
        save.assertIsNotEnabled() // no reason is preselected
        compose.onNode(radio and hasText("비업무")).performClick()
        save.assertIsNotEnabled() // a new purpose alone does not save a correction
        compose.onNode(radio and hasText("개인 용무를 겸했어요")).performClick()
        compose.onNode(hasSetTextAction() and hasText("정정 메모 (선택)")).performTextInput("주유소 경유")
        save.assertIsEnabled().performClick()
        val row = h.core.trips.value.single { it.id == id }
        assertEquals(TripPurpose.PERSONAL, row.purpose)
        assertTrue(row.amended)
        assertEquals("개인 용무를 겸했어요 · 주유소 경유", reasonOf(h.core, id))
    }

    @Test fun homeCorrectionHasNoPreselectedReasonAndThePurposeButtonsWaitForOne() {
        val h = Harness(startAt = Harness.at(LocalDate.of(2026, 12, 3), 8, 5))
        h.core.setDemoModeNow(true, null)
        h.core.loadSampleHistoryNow()
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.core.onNavi(NaviEvent.Ready(true))
        h.at(SampleHistory.HOME.point); h.odo(45_200.0); h.gear(Gear.PARK)
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(38 * 60_000); h.odo(45_218.0)
        h.at(SampleHistory.OFFICE.point); h.gear(Gear.PARK) // home → office: classified as a commute by the place rule
        val trip = h.core.trips.value.last()
        assertEquals(ClassifiedBy.AUTO, trip.classifiedBy)
        show(h.core, Tab.HOME)
        compose.onNode(button and hasText("바꾸기")).performClick()
        val personal = compose.onNode(button and hasText("비업무"))
        personal.assertIsNotEnabled()
        compose.onNode(radio and hasText("경유지가 있었어요")).performClick()
        personal.assertIsEnabled().performClick()
        val row = h.core.trips.value.single { it.id == trip.id }
        assertEquals(TripPurpose.PERSONAL, row.purpose)
        assertTrue(row.amended)
        assertEquals("경유지가 있었어요", reasonOf(h.core, trip.id))
    }

    @Test fun koreanLinesBreakOnlyBetweenWords() {
        val wj = '\u2060'
        assertEquals("다${wj}음 해${wj}로 넘${wj}어${wj}갑${wj}니${wj}다${wj}.", keepWords("다음 해로 넘어갑니다."))
        assertEquals("800${wj}만${wj}원 (${wj}비${wj}율 94.8%)", keepWords("800만원 (비율 94.8%)"))
        assertEquals("12.0 km", keepWords("12.0 km")) // no Hangul: untouched
    }

    @Test fun aMinuteWithoutVehicleDataTurnsTheHomeCardIntoTheWaitMessage() {
        val h = Harness()
        show(h.core, Tab.HOME)
        val drive = "시동을 걸고 기어를 D로 바꾸면 기록이 시작됩니다"
        val wait = "차량 정보를 기다리고 있습니다. 1분이 넘으면 앱을 다시 열어 주세요"
        compose.onNodeWithText(drive).assertExists()
        compose.mainClock.advanceTimeBy(VEHICLE_WAIT_MS - 5_000)
        compose.onNodeWithText(drive).assertExists() // not before the minute is over
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText(wait).assertExists()
        h.odo(1_000.0); h.gear(Gear.PARK) // the car answers
        compose.waitForIdle()
        compose.onNodeWithText(drive).assertExists()
    }
}
