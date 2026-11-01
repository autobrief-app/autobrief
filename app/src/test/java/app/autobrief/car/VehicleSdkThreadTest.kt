package app.autobrief.car

import android.os.Looper
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.vehicle.PleosVehicleSource
import app.autobrief.car.vehicle.VehicleLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections

/**
 * The Pleos Vehicle SDK reflects over its own classes with kotlin-reflect on its first calls: the odometer probe kept the
 * main thread for seconds on a loaded head unit, and Android closed the app for not taking a focus event within 5 s
 * (bug report of rehearsal 40475583018: main in OdometerImpl.getCurrentDistanceDriven <- PleosVehicleSource.probe).
 * No call into the SDK may run on the main thread: not the probe, not the registration, not a reconnect, not the release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleSdkThreadTest {
    @get:Rule val stalePleosBinds = RefuseStalePleosBinds()
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<VehicleEvent> = Collections.synchronizedList(mutableListOf())
    private val links: MutableList<CarLink> = Collections.synchronizedList(mutableListOf())

    /** A car whose vehicle service is up; every call into the SDK is noted with " on main" when it ran on the main thread. */
    private inner class CarLink : VehicleLink {
        @Volatile var onGear: ((Gear) -> Unit)? = null

        private fun call(name: String) {
            calls += name + if (Looper.myLooper() == Looper.getMainLooper()) " on main" else ""
        }

        override fun initialize() = call("initialize")
        override fun currentDistance(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) {
            call("currentDistance")
            onValue(45_200f)
        }
        override fun currentGear(onValue: (Gear) -> Unit, onFailed: (Exception) -> Unit) {
            call("currentGear")
            onValue(Gear.PARK)
        }
        override fun vin(onValue: (String?) -> Unit, onFailed: (Exception) -> Unit) {
            call("vin")
            onValue("\"KMHXX81AXRU000001\"")
        }
        override fun fuelEconomy(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) {
            call("fuelEconomy")
            onValue(8.26f)
        }
        override fun evEfficiency(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) {
            call("evEfficiency")
            onFailed(IllegalStateException("not an electric car"))
        }
        override fun listen(onDistance: (Float?) -> Unit, onGear: (Gear) -> Unit, onFailed: (String, Exception) -> Unit) {
            call("listen")
            this.onGear = onGear
        }
        override fun release() = call("release")
    }

    private fun source() = PleosVehicleSource(RuntimeEnvironment.getApplication(), { events += it }) { CarLink().also { links += it } }

    /** The fake clock moves on (the source's thread runs what falls due) and the main looper runs what was posted to it. */
    private fun passTime(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    /** Moves the fake clock on in small steps (a probe waits 1.5 s for the bind at first) until [done]; 20 s of real time at most. */
    private fun passTimeUntil(what: String, done: () -> Boolean) {
        val until = System.nanoTime() + 20_000_000_000L
        while (!done()) {
            check(System.nanoTime() < until) { "timed out waiting for $what: $calls" }
            passTime(Duration.ofMillis(250))
            Thread.sleep(5)
        }
    }

    private val connect = listOf("initialize", "currentDistance", "listen", "currentGear", "vin", "fuelEconomy", "evEfficiency")

    @Test fun theProbeTheRegistrationAReconnectAndTheReleaseAllRunOffTheMainThread() {
        val vehicle = source()
        vehicle.start()
        passTimeUntil("the probe's answer and the registration") { events.any { it is VehicleEvent.EvEfficiency } }
        assertEquals(connect, calls.toList())
        // the figures go on as the decimals the car printed; a call that failed reports no value
        assertTrue(events.toString(), VehicleEvent.FuelEconomy(8.26) in events && VehicleEvent.EvEfficiency(null) in events)

        // after a trip the core asks for the figures again: read on the source's thread too
        calls.clear()
        vehicle.requestEnergy()
        passTimeUntil("the figures read again") { calls.size == 2 }
        assertEquals(listOf("fuelEconomy", "evEfficiency"), calls.toList())
        // what the core is told stays the same: connected, the odometer, the gear, the VIN
        assertEquals(
            listOf(VehicleEvent.Connection(true), VehicleEvent.Odometer(45_200.0), VehicleEvent.GearChanged(Gear.PARK)),
            events.take(3),
        )

        // out of P and silent for longer than QUIET_MS: the monitor's tick (on the main thread) has the link made again
        links.last().onGear!!(Gear.DRIVE)
        passTime(Duration.ofMillis(PleosVehicleSource.QUIET_MS + 1_000))
        calls.clear()
        vehicle.checkQuiet()
        passTimeUntil("the second link") { calls.count { it.startsWith("evEfficiency") } == 1 }
        assertEquals(listOf("release") + connect, calls.toList())

        calls.clear()
        vehicle.stop()
        runMainLooperUntil("the release") { calls.isNotEmpty() }
        assertEquals(listOf("release"), calls.toList())
        assertTrue(links.size == 2)
    }
}
