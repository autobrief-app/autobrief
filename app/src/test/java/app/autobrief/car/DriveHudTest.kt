package app.autobrief.car

import app.autobrief.car.service.HudHidden
import app.autobrief.car.service.HudState
import app.autobrief.car.service.hudHidden
import app.autobrief.car.service.hudState
import app.autobrief.car.service.recordingLine
import app.autobrief.car.service.recordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the status pill over the front app is drawn: a trip is being recorded, the app's own screen is not started, and
 * the user allowed drawing over other apps. Every other combination draws none and names the first reason.
 */
class DriveHudTest {
    @Test fun thePillIsDrawnOnlyWhenATripIsRecordedBehindAnotherAppAndOverlaysAreAllowed() {
        assertEquals(HudState("12.0"), hudState(tripInProgress = true, distanceKm = 12.0, appVisible = false, canDrawOverlays = true))
        assertNull(hudHidden(tripInProgress = true, appVisible = false, canDrawOverlays = true))
    }

    @Test fun noTripDrawsNoPillWhateverElseHolds() {
        for (visible in listOf(false, true)) for (allowed in listOf(false, true)) {
            assertNull(hudState(tripInProgress = false, distanceKm = 3.0, appVisible = visible, canDrawOverlays = allowed))
            assertEquals(HudHidden.TRIP_ENDED, hudHidden(tripInProgress = false, appVisible = visible, canDrawOverlays = allowed))
        }
    }

    @Test fun theAppsOwnScreenHidesThePill() {
        for (allowed in listOf(false, true)) {
            assertNull(hudState(tripInProgress = true, distanceKm = 3.0, appVisible = true, canDrawOverlays = allowed))
            assertEquals(HudHidden.APP_VISIBLE, hudHidden(tripInProgress = true, appVisible = true, canDrawOverlays = allowed))
        }
    }

    @Test fun withoutThePermissionThereIsNoPill() {
        assertNull(hudState(tripInProgress = true, distanceKm = 3.0, appVisible = false, canDrawOverlays = false))
        assertEquals(HudHidden.NO_PERMISSION, hudHidden(tripInProgress = true, appVisible = false, canDrawOverlays = false))
    }

    @Test fun aPillIsDrawnExactlyWhenNoReasonHidesIt() {
        for (trip in listOf(false, true)) for (visible in listOf(false, true)) for (allowed in listOf(false, true)) {
            val state = hudState(trip, 1.0, visible, allowed)
            val why = hudHidden(trip, visible, allowed)
            assertEquals("trip=$trip visible=$visible allowed=$allowed", state == null, why != null)
        }
    }

    @Test fun theDistanceIsPrintedAsOnTheHomeCard() {
        assertEquals("+12.0 km", hudState(true, 12.04, appVisible = false, canDrawOverlays = true)!!.distance)
        assertEquals("+0.0 km", hudState(true, 0.0, appVisible = false, canDrawOverlays = true)!!.distance)
        assertEquals("+1,234.6 km", hudState(true, 1234.55, appVisible = false, canDrawOverlays = true)!!.distance)
    }

    @Test fun aFamilyDriveIsCarriedToThePill() {
        assertEquals(HudState("7.5", family = true), hudState(true, 7.5, appVisible = false, canDrawOverlays = true, family = true))
    }

    @Test fun theRecordedTripIsSaidWhetherOrNotAPillIsDrawn() {
        assertEquals(HudState("12.0"), recordingState(tripInProgress = true, distanceKm = 12.0))
        assertNull(recordingState(tripInProgress = false, distanceKm = 12.0))
        assertEquals("운행 기록 중 · +12.0 km", recordingLine("운행 기록 중", HudState("12.0").distance))
    }

    @Test fun theLoggedReasonsAreTheThreeTheEmulatorCheckReads() {
        assertEquals(listOf("trip_ended", "app_visible", "no_permission"), HudHidden.values().map { it.reason })
    }
}
