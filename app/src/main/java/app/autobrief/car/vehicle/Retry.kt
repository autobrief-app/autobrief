package app.autobrief.car.vehicle

import app.autobrief.car.model.Gear

/** The pause before the next try to reach a car service that is not ready: 1.5 s, doubling, at most a minute. */
object Backoff {
    const val FIRST_MS = 1_500L
    const val MAX_MS = 60_000L

    fun delayMs(attempt: Int): Long = minOf(FIRST_MS shl attempt.coerceIn(0, 16), MAX_MS)
}

/**
 * Notices listeners that went quiet. Outside P the Pleos SDK reports the odometer every few seconds, so nothing for
 * [quietMs] while the car is in D, R or N means the connection is gone and has to be made again. In P silence is
 * normal; before any gear is known the start-up retries are at work.
 */
class QuietWatch(private val quietMs: Long, private val clock: () -> Long) {
    /** Set from the SDK's callback threads, read on the source's own thread. */
    @Volatile private var lastHeard = clock()

    fun heard() {
        lastHeard = clock()
    }

    /** True when the source should connect again; the wait then starts over. */
    fun reconnectDue(gear: Gear): Boolean {
        if (gear == Gear.PARK || gear == Gear.UNKNOWN) return false
        val now = clock()
        if (now - lastHeard < quietMs) return false
        lastHeard = now
        return true
    }
}
