package app.autobrief.car

import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the logbook does with odometer values a real SDK can send: NaN, a 0.0 at start-up, a step back, a jump. */
class OdometerSanityTest {
    /** A logbook as it is loaded after a restart: one trip that ended at 45,200 km. */
    private fun afterRestart() = TripLogbook(
        listOf(TripRecord("TRIP-1", 1_000, 2_000, 45_182.0, 45_200.0, TripPurpose.COMMUTE, null, "")),
    )

    @Test fun nonFiniteAndNegativeReadingsAreIgnored() {
        val lb = TripLogbook()
        lb.onOdometer(100.0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0).forEach { assertTrue(lb.onOdometer(it).isEmpty()) }
        assertEquals(100.0, lb.odometerKm!!, 0.0)
        lb.onGear(Gear.DRIVE, 10)
        lb.onOdometer(Double.NaN)
        lb.onOdometer(112.0)
        val row = lb.onGear(Gear.PARK, 20).single()
        assertEquals(100.0, row.startOdometerKm, 0.0)
        assertEquals(112.0, row.endOdometerKm, 0.0)
    }

    @Test fun aZeroFirstReadingAfterARestartOpensNoTripAtZero() {
        val lb = afterRestart()
        lb.onOdometer(0.0) // the SDK's value before the car's own arrives
        lb.onOdometer(0.0)
        assertNull(lb.odometerKm)
        lb.onGear(Gear.DRIVE, 3_000) // leaves P before a real value: the trip opens at the first real one
        lb.onOdometer(45_200.0)
        lb.onOdometer(45_212.0)
        val rows = lb.onGear(Gear.PARK, 4_000)
        assertEquals(1, rows.size)
        assertEquals(45_200.0, rows.single().startOdometerKm, 0.0)
        assertEquals(12.0, rows.single().distanceKm, 0.0)
    }

    @Test fun aStepBackIsTakenOnlyWhenASecondReadingAgrees() {
        val lb = TripLogbook()
        lb.onOdometer(45_236.0)
        lb.onOdometer(45_200.0) // the demo console was set back
        assertEquals(45_236.0, lb.odometerKm!!, 0.0)
        lb.onGear(Gear.DRIVE, 10) // departing while the value is in doubt waits for it
        lb.onOdometer(45_200.0) // the SDK repeats the value every few seconds
        assertEquals(45_200.0, lb.odometerKm!!, 0.0)
        lb.onOdometer(45_218.0)
        val row = lb.onGear(Gear.PARK, 20).single()
        assertEquals(45_200.0, row.startOdometerKm, 0.0)
        assertEquals(18.0, row.distanceKm, 0.0)
    }

    @Test fun aLargeJumpIsTakenOnlyWhenASecondReadingAgrees() {
        val lb = afterRestart()
        assertTrue(lb.onOdometer(46_000.0).isEmpty())
        assertNull("not taken yet", lb.odometerKm)
        lb.onOdometer(46_000.0) // the SDK repeats the value a few seconds later
        assertEquals(46_000.0, lb.odometerKm!!, 0.0)
        val gap = lb.onGear(Gear.DRIVE, 3_000).single()
        assertEquals(TripPurpose.UNLOGGED_GAP, gap.purpose)
        assertEquals(45_200.0, gap.startOdometerKm, 0.0)
        assertEquals(46_000.0, gap.endOdometerKm, 0.0)
    }

    @Test fun oneWildReadingBetweenGoodOnesIsDropped() {
        val lb = TripLogbook()
        lb.onOdometer(45_200.0)
        lb.onGear(Gear.DRIVE, 10)
        lb.onOdometer(99_999.0)
        lb.onOdometer(45_200.4)
        lb.onOdometer(45_207.0)
        val row = lb.onGear(Gear.PARK, 20).single()
        assertEquals(45_200.0, row.startOdometerKm, 0.0)
        assertEquals(45_207.0, row.endOdometerKm, 0.0)
    }

    @Test fun theCoreSurvivesANanReadingMidTripAndKeepsTheLastGoodValueOnScreen() {
        val h = Harness(AppSettings(plateNumber = "00가 0000"))
        h.odo(45_200.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        h.core.onVehicle(VehicleEvent.Odometer(Double.NaN))
        assertEquals(45_200.0, h.core.vehicle.value.odometerKm!!, 0.0)
        h.advance(20 * 60_000); h.odo(45_211.0); h.gear(Gear.PARK)
        val row = h.core.trips.value.single()
        assertEquals(11.0, row.distanceKm, 0.0)
        assertTrue(h.core.integrity.value.ok)
        assertTrue(h.store.data.values.all { d -> d.trips.all { it.startOdometerKm.isFinite() && it.endOdometerKm.isFinite() } })
    }
}
