package app.autobrief.car

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.VehicleEvent
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
}
