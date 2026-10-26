package app.autobrief.car

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

    @Test fun anUnknownDrivingRestrictionStateCountsAsRestrictedOutsidePark() {
        val h = Harness()
        h.core.setRestricted(null) // the car service is not reachable (yet)
        assertTrue("gear unknown: cannot tell the car is parked", h.core.restricted.value)
        h.gear(Gear.PARK)
        assertFalse(h.core.restricted.value)
        h.gear(Gear.DRIVE)
        assertTrue(h.core.restricted.value)
        h.core.setRestricted(false) // the car service answered
        assertFalse(h.core.restricted.value)
        h.core.setRestricted(true)
        h.gear(Gear.PARK)
        assertTrue("a known state is taken as it is", h.core.restricted.value)
    }
}
