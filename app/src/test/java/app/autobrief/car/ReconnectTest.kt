package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.vehicle.Backoff
import app.autobrief.car.vehicle.QuietWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app tries again to reach the car's services, and what the screen does while it cannot tell. */
class ReconnectTest {
    @Test fun aServiceThatIsNotReadyIsTriedAgainWithAGrowingPause() {
        assertEquals(listOf(1_500L, 3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L), (0..7).map(Backoff::delayMs))
        assertEquals(60_000L, Backoff.delayMs(1_000))
    }

    @Test fun listenersThatWentQuietOutsideParkAreRegisteredAgain() {
        var now = 0L
        val watch = QuietWatch(3 * 60_000L) { now }
        now += 10 * 60_000L
        assertFalse("silence in P is normal", watch.reconnectDue(Gear.PARK))
        assertFalse("nothing known about the gear yet: the start-up retries handle it", watch.reconnectDue(Gear.UNKNOWN))
        watch.heard()
        now += 2 * 60_000L
        assertFalse(watch.reconnectDue(Gear.DRIVE))
        now += 61_000L
        assertTrue("three minutes without a value while driving", watch.reconnectDue(Gear.DRIVE))
        assertFalse("then the wait starts over", watch.reconnectDue(Gear.DRIVE))
        now += 3 * 60_000L
        assertTrue(watch.reconnectDue(Gear.REVERSE))
        now += 60_000L
        watch.heard()
        now += 2 * 60_000L
        assertFalse("a value arrived meanwhile", watch.reconnectDue(Gear.NEUTRAL))
    }

    /** A core as the app starts it: neither the car service nor the vehicle SDK has answered yet. */
    private fun starting() = AutoBriefCore(MemoryStore(), clock = { 0L })

    @Test fun unknownRestrictionsAndAnUnknownGearAreRestricted() {
        val c = starting()
        assertTrue(c.restricted.value)
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK)) // a gear without a live link proves nothing yet
        assertTrue(c.restricted.value)
    }

    @Test fun unknownRestrictionsButALiveLinkInParkAreNotRestricted() {
        val c = starting()
        c.onVehicle(VehicleEvent.Connection(true))
        assertTrue("connected, gear not known yet", c.restricted.value)
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertFalse(c.restricted.value)
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        assertTrue(c.restricted.value)
    }

    @Test fun unknownRestrictionsAndALinkLostAfterParkAreRestricted() {
        val c = starting()
        c.onVehicle(VehicleEvent.Connection(true))
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertFalse(c.restricted.value)
        c.onVehicle(VehicleEvent.Connection(false, "binder died")) // the last gear no longer says anything
        assertEquals(Gear.UNKNOWN, c.vehicle.value.gear)
        assertTrue(c.restricted.value)
    }

    @Test fun theCarServicesAnswerWinsOnceItIsKnown() {
        val c = starting()
        c.setRestricted(false) // CarUxRestrictions: not required
        c.onVehicle(VehicleEvent.Connection(true))
        c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        assertFalse("the platform value; the screen's own gear rule still shows the glance view in D", c.restricted.value)
        c.setRestricted(true)
        c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertTrue(c.restricted.value)
        c.setRestricted(null) // the car service went down: back to the fallback
        assertFalse("live link in P", c.restricted.value)
        c.onVehicle(VehicleEvent.Connection(false))
        assertTrue(c.restricted.value)
    }
}
