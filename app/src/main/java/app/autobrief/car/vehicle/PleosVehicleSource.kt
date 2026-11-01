package app.autobrief.car.vehicle

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear

/**
 * Pleos Connect Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`), read APIs only:
 * Odometer (dashboard total distance, km) and Gear (CAR_DRIVE), CarInfo VIN (CAR_INFO), the cumulative fuel economy and
 * EV efficiency (CAR_ENERGY, read when connected and again after every trip, see [requestEnergy]).
 *
 * The vehicle service may not be up when the app starts: initialising and the first query are tried again with a
 * growing pause until they succeed. Once connected, listeners that went quiet outside P ([checkQuiet], called by the
 * monitor's tick) are connected again from scratch.
 *
 * Every call into the SDK runs on this source's own thread, never the main thread: the SDK reflects over its classes
 * with kotlin-reflect on its first calls, and on a loaded head unit the odometer probe kept the main thread for seconds
 * (rehearsal 40475583018: Android closed the app for not taking a focus event within 5 s). The link and the state below
 * belong to that thread. emit() posts to the main thread itself (see VehicleMonitorService), so it can be called from
 * here and from the SDK's callback threads.
 */
class PleosVehicleSource(
    context: Context,
    private val emit: (VehicleEvent) -> Unit,
    private val newLink: () -> VehicleLink = { PleosVehicleLink(context.applicationContext) },
) {
    private val thread = HandlerThread("AutoBriefVehicleSdk").apply { start() }
    private val sdk = Handler(thread.looper)
    private var link: VehicleLink? = null
    private var registered = false
    @Volatile private var stopped = false
    @Volatile private var lastGear = Gear.UNKNOWN
    private val quiet = QuietWatch(QUIET_MS, SystemClock::elapsedRealtime)

    private fun gearIs(gear: Gear) {
        quiet.heard()
        lastGear = gear
        emit(VehicleEvent.GearChanged(gear))
    }

    fun start() {
        stopped = false
        sdk.post(::connect)
    }

    /** A new link, initialised and probed. */
    private fun connect() {
        val l = newLink()
        link = l
        initialize(l, attempt = 0)
    }

    private fun initialize(l: VehicleLink, attempt: Int) {
        if (stopped || l !== link) return
        runCatching { l.initialize() }.onFailure {
            issue("initialize", it)
            emit(VehicleEvent.Connection(false, it.message))
            sdk.postDelayed({ initialize(l, attempt + 1) }, Backoff.delayMs(attempt))
            return
        }
        // initialize() binds to the Pleos vehicle service asynchronously: probe until it answers
        probe(l, attempt = 0)
    }

    private fun probe(l: VehicleLink, attempt: Int) {
        sdk.postDelayed({
            if (stopped || l !== link) return@postDelayed
            runCatching {
                l.currentDistance({ km ->
                    sdk.post {
                        if (stopped || l !== link) return@post
                        quiet.heard()
                        emit(VehicleEvent.Connection(true))
                        km?.let { emit(VehicleEvent.Odometer(it.toDouble())) }
                        if (!registered) {
                            registered = true
                            register(l)
                        }
                    }
                }, {
                    issue("probe", it)
                    sdk.post { probe(l, attempt + 1) }
                })
            }.onFailure {
                issue("probe", it)
                probe(l, attempt + 1)
            }
        }, Backoff.delayMs(attempt))
    }

    private fun register(l: VehicleLink) = runCatching {
        l.listen(
            onDistance = { km ->
                quiet.heard()
                km?.let { emit(VehicleEvent.Odometer(it.toDouble())) }
            },
            onGear = ::gearIs,
            onFailed = ::issue,
        )
        l.currentGear(::gearIs) { issue("gear", it) }
        l.vin({ emit(VehicleEvent.Vin(it)) }, { issue("vin", it) })
    }.onFailure {
        issue("register", it)
        emit(VehicleEvent.Connection(false, it.message))
    }.also { readEnergy(l) }

    /** The cumulative fuel economy and EV efficiency once more (the core asks after every trip); off the main thread. */
    fun requestEnergy() {
        sdk.post {
            val l = link
            if (!stopped && registered && l != null) readEnergy(l)
        }
    }

    /**
     * Both figures, each reported as it comes; a call that fails (a car without that value, or no CAR_ENERGY grant) reports
     * no value, so the log shows what the car answered either way. A Float goes on as the decimal it prints as.
     */
    private fun readEnergy(l: VehicleLink) {
        fun decimal(v: Float?): Double? = v?.toString()?.toDoubleOrNull()
        runCatching {
            l.fuelEconomy({ emit(VehicleEvent.FuelEconomy(decimal(it))) }) {
                issue("fuel economy", it)
                emit(VehicleEvent.FuelEconomy(null))
            }
        }.onFailure {
            issue("fuel economy", it)
            emit(VehicleEvent.FuelEconomy(null))
        }
        runCatching {
            l.evEfficiency({ emit(VehicleEvent.EvEfficiency(decimal(it))) }) {
                issue("ev efficiency", it)
                emit(VehicleEvent.EvEfficiency(null))
            }
        }.onFailure {
            issue("ev efficiency", it)
            emit(VehicleEvent.EvEfficiency(null))
        }
    }

    /**
     * Called by the monitor every tick: no value for [QUIET_MS] while the car is in D, R or N means the binding is gone.
     * A fresh SDK object is initialised and probed, and registers its listeners once it answers.
     */
    fun checkQuiet() {
        sdk.post {
            if (stopped || !registered || !quiet.reconnectDue(lastGear)) return@post
            Log.w(TAG, "no vehicle data for ${QUIET_MS / 60_000} min outside P: connecting again")
            emit(VehicleEvent.Connection(false, "no data"))
            sdk.removeCallbacksAndMessages(null) // the old link's retries end with it
            release()
            connect()
        }
    }

    private fun release() {
        link?.release()
        link = null
        registered = false
        lastGear = Gear.UNKNOWN // the next gear comes from the next link
    }

    /** Ends this source: the link is released on its thread, which then ends. */
    fun stop() {
        stopped = true
        sdk.removeCallbacksAndMessages(null)
        sdk.post(::release)
        thread.quitSafely()
    }

    private fun issue(what: String, e: Throwable) {
        Log.w(TAG, "$what: ${e.message}")
    }

    companion object {
        private const val TAG = "AutoBriefVehicle"

        /** Outside P the SDK reports the odometer every few seconds: three minutes without anything is a lost link. */
        const val QUIET_MS = 3 * 60_000L
    }
}
