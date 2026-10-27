package app.autobrief.car

import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.service.CoreInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the car reports while the stored logbook is read at start waits, and goes in first, in arrival order. */
class CoreInputsTest {
    @Test fun reportsMadeBeforeTheCoreIsThereGoInFirstInArrivalOrder() {
        val applied = mutableListOf<String>()
        val inputs = CoreInputs<MutableList<String>>()
        inputs.deliver { it += "connected" }
        inputs.deliver { it += "odometer" }
        inputs.deliver { it += "gear P" }
        assertTrue("nothing applied before the core is there", applied.isEmpty())
        assertEquals(3, inputs.waitingCount)
        inputs.open(applied)
        assertEquals(listOf("connected", "odometer", "gear P"), applied)
        inputs.deliver { it += "gear D" }
        assertEquals("after that at once", listOf("connected", "odometer", "gear P", "gear D"), applied)
        assertEquals(0, inputs.waitingCount)
    }

    @Test fun aReportMadeWhileTheWaitingOnesGoInJoinsTheEnd() {
        val applied = mutableListOf<String>()
        val inputs = CoreInputs<MutableList<String>>()
        inputs.deliver {
            it += "first"
            inputs.deliver { l -> l += "made meanwhile" }
        }
        inputs.deliver { it += "second" }
        inputs.open(applied)
        assertEquals(listOf("first", "second", "made meanwhile"), applied)
    }

    @Test fun pastTheCapTheOldestReportsGoAndAreCounted() {
        val applied = mutableListOf<Int>()
        var dropped = 0
        val inputs = CoreInputs<MutableList<Int>>(cap = 3) { dropped = it }
        (1..5).forEach { n -> inputs.deliver { it += n } }
        inputs.open(applied)
        assertEquals(listOf(3, 4, 5), applied)
        assertEquals(2, dropped)
    }

    @Test fun aDriveReportedWhileTheLogbookWasReadIsRecordedOnceItIsThere() {
        val h = Harness()
        val inputs = CoreInputs<app.autobrief.car.domain.AutoBriefCore>()
        fun report(e: VehicleEvent, afterMs: Long = 0) = inputs.deliver {
            h.advance(afterMs)
            it.onVehicle(e)
        }
        report(VehicleEvent.Connection(true))
        report(VehicleEvent.Odometer(1_000.0))
        report(VehicleEvent.GearChanged(Gear.PARK))
        report(VehicleEvent.GearChanged(Gear.DRIVE), afterMs = 60_000)
        report(VehicleEvent.Odometer(1_012.0), afterMs = 20 * 60_000)
        report(VehicleEvent.GearChanged(Gear.PARK))
        assertTrue(h.core.trips.value.isEmpty())
        inputs.open(h.core)
        val trip = h.core.trips.value.single()
        assertEquals(1_000.0, trip.startOdometerKm, 0.0)
        assertEquals(1_012.0, trip.endOdometerKm, 0.0)
        assertEquals(Gear.PARK, h.core.vehicle.value.gear)
    }
}
