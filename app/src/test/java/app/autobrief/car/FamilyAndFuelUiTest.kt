package app.autobrief.car

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.ui.AutoBriefScreen
import app.autobrief.car.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The family drive switch and badge, and the fuel reimbursement card that replaces the tax meter for an employee's own car. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2560dp-h1440dp-xlarge-land-mdpi")
class FamilyAndFuelUiTest {
    @get:Rule(order = 0) val stalePleosBinds = RefuseStalePleosBinds()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(core: AutoBriefCore, tab: Tab) {
        compose.setContent { AutoBriefScreen(core, mutableStateOf(tab), onFlush = {}, night = true) }
        compose.waitForIdle()
    }

    @Test fun theHomeSwitchArmsTheNextDriveAndTheDrivingViewSaysNoLocationIsRecorded() {
        val h = Harness()
        h.core.onVehicle(VehicleEvent.Connection(true))
        h.odo(1_000.0); h.gear(Gear.PARK)
        show(h.core, Tab.HOME)
        compose.onNodeWithText("위치를 읽지 않고 거리만 비업무로 남깁니다").assertExists()
        compose.onNodeWithText("다음 운행은 가족 운행").performClick()
        compose.waitForIdle()
        assertTrue(h.core.settings.value.familyNext)
        compose.onNodeWithText("위치 읽지 않음 (가족 운행)").assertExists()

        h.advance(60_000); h.gear(Gear.DRIVE)
        compose.waitForIdle()
        compose.onNodeWithText("가족 운행 중 (위치 기록 안 함)").assertExists()
        compose.onNodeWithText("다음 운행은 가족 운행").assertDoesNotExist() // the driving layout has no switch

        h.advance(20 * 60_000); h.odo(1_012.0); h.gear(Gear.PARK)
        compose.waitForIdle()
        compose.onNodeWithText("가족 운행 (위치 기록 안 함)").assertExists()
        compose.onNodeWithText("비업무로 기록했습니다").assertExists()
        assertEquals(TripPurpose.PERSONAL, h.core.trips.value.single().purpose)
        assertFalse("it ended with that drive", h.core.settings.value.familyNext)
        compose.onNodeWithText("위치 읽지 않음 (가족 운행)").assertDoesNotExist()
    }

    @Test fun ownCarModeShowsTheFuelCardInsteadOfTheTaxMeter() {
        val h = Harness(AppSettings(annualCarCostKrw = 19_000_000, annualDepreciationKrw = 11_500_000))
        h.odo(1_000.0); h.gear(Gear.PARK)
        show(h.core, Tab.HOME)
        compose.onNodeWithText("올해 운행기록부 효과", substring = true).assertExists()
        compose.onNodeWithText("유류비 정산").assertDoesNotExist()

        h.core.saveSettings(h.core.settings.value.copy(carUse = CarUse.OWN), null, null)
        compose.waitForIdle()
        compose.onNodeWithText("올해 운행기록부 효과", substring = true).assertDoesNotExist()
        compose.onNodeWithText("유류비 정산").assertExists()
        compose.onNodeWithText("차량 연비 값을 받지 못했습니다").assertExists()
        compose.onNodeWithText("연비 직접 입력(km/L)").assertExists()

        // the car reports its economy, the owner enters a price, a business drive: the card gives the amount
        h.core.onVehicle(VehicleEvent.FuelEconomy(8.26))
        h.core.saveSettings(h.core.settings.value.copy(fuelPriceKrwPerLiter = 1_650), null, null)
        h.longDrive(1_000.0, 312.4)
        h.core.classifyTrip(h.core.trips.value.single().id, TripPurpose.BUSINESS, null, "", ClassifiedBy.SCREEN)
        compose.waitForIdle()
        compose.onNodeWithText("42,580원").assertExists()
        compose.onNodeWithText("이번 달 업무 312.4km, 평균 연비 12.1km/L(차량 누적 연비), 유가 1,650원/L, 정산 42,580원").assertExists()
    }

    @Test fun anElectricCarIsToldItsSettlementComesLater() {
        val h = Harness(AppSettings(carUse = CarUse.OWN, fuelPriceKrwPerLiter = 1_650))
        h.core.onVehicle(VehicleEvent.EvEfficiency(5.1))
        show(h.core, Tab.HOME)
        compose.onNodeWithText("전기차 정산은 다음 버전").assertExists()
    }
}
