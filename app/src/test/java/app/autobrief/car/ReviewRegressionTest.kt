package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.CustodyMessages
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.domain.VehicleControl
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.ActiveCustody
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.TripPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** One test per finding of the independent v6 review, so each defect stays fixed. */
class ReviewRegressionTest {
    private var now = ZonedDateTime.of(2026, 10, 23, 22, 0, 0, 0, KST).toInstant().toEpochMilli()
    private val family = AppSettings(familyWebhookUrl = "https://example.invalid/f")

    private fun core(settings: AppSettings = family, active: ActiveCustody? = null) =
        AutoBriefCore(initialSettings = settings, initialActiveCustody = active, clock = { now })

    private fun texts(c: AutoBriefCore) = c.outbox.value.map { it.payloadJson }

    @Test fun settingsAreLockedDuringCustodyAndPinChangesNeedTheCurrentPin() {
        val c = core()
        assertEquals(AutoBriefCore.SettingsResult.SAVED, c.setCustodyPin("2580"))
        assertEquals(AutoBriefCore.SettingsResult.PIN_REQUIRED, c.setCustodyPin("1111", currentPin = "0000"))
        assertEquals(AutoBriefCore.SettingsResult.PIN_REQUIRED,
            c.saveOwnerSettings(c.settings.value.copy(familyWebhookUrl = ""), null, currentPin = null))
        c.startCustody(driverConsented = true, notifyFamily = true)
        assertEquals(AutoBriefCore.SettingsResult.LOCKED_DURING_CUSTODY, c.setCustodyPin("1111", currentPin = "2580"))
        assertEquals(AutoBriefCore.EndResult.WRONG_PIN, c.endCustodyEarly("1111"))
    }

    @Test fun demoModeSendsNoFamilyNotice() {
        val c = core()
        c.setCustodyPin("2580")
        assertEquals(AutoBriefCore.SettingsResult.SAVED, c.saveOwnerSettings(c.settings.value.copy(demoMode = true), null, "2580"))
        c.startCustody(driverConsented = true, notifyFamily = true)
        c.onNavi(NaviEvent.Arrived(null))
        c.onVehicle(VehicleEvent.DoorsLocked(true))
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertTrue(c.outbox.value.isEmpty())
    }

    @Test fun arrivalWaitsForTheLockOrSaysItWasNotSeen() {
        val c = core()
        c.setCustodyPin("2580")
        c.startCustody(driverConsented = true, notifyFamily = true)
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        c.onNavi(NaviEvent.Arrived(null))
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertEquals(1, c.outbox.value.size) // departure only: the arrival waits for the doors
        c.onVehicle(VehicleEvent.DoorsLocked(true))
        assertTrue(texts(c).last().contains("문 잠김 확인"))

        val d = core()
        d.setCustodyPin("2580")
        d.startCustody(driverConsented = true, notifyFamily = true)
        d.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE)); d.onNavi(NaviEvent.Arrived(null)); d.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        now += AutoBriefCore.LOCK_WAIT_MS + 1
        d.tick()
        assertTrue(texts(d).last().contains("문 잠김은 확인되지 않았습니다"))
    }

    @Test fun countsWithoutSpeedReadingsAreReportedAsUnmeasured() {
        val c = core()
        c.setCustodyPin("2580")
        c.startCustody(driverConsented = true, notifyFamily = true)
        c.onVehicle(VehicleEvent.DoorsLocked(true))
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        c.onNavi(NaviEvent.Destination("가상 목적지", null, 4_000, 600, null))
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK)) // a stop 4 km before the destination
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        c.onNavi(NaviEvent.Arrived(null))
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val report = c.reports.value.single()
        assertEquals(1, report.intermediateStops)
        val text = CustodyMessages.end(report, "23:00")
        assertTrue(text.contains("측정하지 못했습니다"))
        assertFalse(text.contains("과속 0회"))
        assertTrue(text.contains("중간 정차 1회"))
    }

    @Test fun aSessionCutByARestartIsReportedAsInterrupted() {
        val c = core(active = ActiveCustody("CUSTODY-1", now - 600_000, 100.0, "가상 목적지", notifyFamily = true))
        val r = c.reports.value.single()
        assertEquals(CustodyEndReason.INTERRUPTED, r.endReason)
        assertTrue(texts(c).single().contains("기록이 중간에 끊겼습니다"))
        assertNull(c.custody.value)
    }

    @Test fun gearBeforeTheFirstOdometerStillOpensTheTrip() {
        val book = TripLogbook()
        book.onGear(Gear.DRIVE, 1_000)
        book.onOdometer(500.0)
        book.onOdometer(512.0)
        val rows = book.onGear(Gear.PARK, 2_000)
        assertEquals(12.0, rows.single().distanceKm, 1e-9)
        assertEquals(TripPurpose.UNCLASSIFIED, rows.single().purpose)
    }

    @Test fun ambientLightReturnsToItsEarlierLevelAndARefusalShows() {
        val c = core()
        val sets = mutableListOf<Int>()
        var allow = true
        c.control = object : VehicleControl {
            override fun readAmbientBrightness(onResult: (Int?) -> Unit) = onResult(60)
            override fun setAmbientBrightness(value: Int, onResult: (Boolean) -> Unit) { sets += value; onResult(allow) }
        }
        c.onVehicle(VehicleEvent.AmbientBounds(0..100))
        c.onVehicle(VehicleEvent.BackrestBounds(SeatPos.values().associateWith { 0..100 }))
        c.onVehicle(VehicleEvent.SeatOccupancy(SeatPos.ROW2_LEFT, true))
        c.onVehicle(VehicleEvent.SeatBelt(SeatPos.ROW2_LEFT, true))
        c.onVehicle(VehicleEvent.ReadingLight(SeatPos.ROW2_LEFT, false))
        c.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_LEFT, 20))
        now += 61_000; c.tick()
        c.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_LEFT, 90))
        assertEquals(listOf(20, 60), sets)
        assertFalse(c.ambientRefused.value)

        allow = false
        c.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_LEFT, 20))
        now += 61_000; c.tick()
        assertTrue(c.ambientRefused.value)
    }

    @Test fun voiceLabelsOnlyTheLastTripAndAsksAboutUnknownUsers() {
        val c = core(AppSettings(users = listOf(CarUser("김예시", "영업팀"))))
        c.onVehicle(VehicleEvent.Odometer(100.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now += 60_000; c.onVehicle(VehicleEvent.Odometer(110.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now += 60_000; c.onVehicle(VehicleEvent.Odometer(120.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val last = c.trips.value.last()
        c.classifyTrip(last.id, TripPurpose.PERSONAL, null, "", "화면")
        // the newest trip is labelled: voice must not reach back to the older one
        assertEquals("fail", c.handleGleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"BUSINESS"}""")).status)
        assertEquals(TripPurpose.UNCLASSIFIED, c.trips.value.first().purpose)

        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now += 60_000; c.onVehicle(VehicleEvent.Odometer(130.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertEquals("asking", c.handleGleo(GleoContract.LOG_TRIP_PURPOSE,
            GleoContract.parseParams("""{"purpose":"BUSINESS","user_name":"누구"}""")).status)
    }
}
