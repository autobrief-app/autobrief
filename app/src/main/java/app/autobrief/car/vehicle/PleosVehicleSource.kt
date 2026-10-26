package app.autobrief.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear

/**
 * Pleos Connect Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`), read APIs only:
 * Odometer (dashboard total distance, km) and Gear (CAR_DRIVE), CarInfo VIN (CAR_INFO).
 *
 * The vehicle service may not be up when the app starts: initialising and the first query are tried again with a
 * growing pause until they succeed. Once connected, listeners that went quiet outside P ([checkQuiet], called by the
 * monitor's tick) are connected again from scratch.
 */
class PleosVehicleSource(context: Context, private val emit: (VehicleEvent) -> Unit) {
    // emit() posts to the main thread itself (see VehicleMonitorService), so SDK callback threads are safe here

    private val app = context.applicationContext
    private var vehicle = Vehicle(app)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var stopped = false
    @Volatile private var lastGear = Gear.UNKNOWN
    private val quiet = QuietWatch(QUIET_MS, SystemClock::elapsedRealtime)

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) {
            quiet.heard()
            km?.let { emit(VehicleEvent.Odometer(it.toDouble())) }
        }
        override fun onFailed(e: Exception) = issue("odometer", e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = gearIs(gear.toGear())
        override fun onFailed(e: Exception) = issue("gear", e)
    }

    private fun gearIs(gear: Gear) {
        quiet.heard()
        lastGear = gear
        emit(VehicleEvent.GearChanged(gear))
    }

    fun start() {
        stopped = false
        initialize(attempt = 0)
    }

    private fun initialize(attempt: Int) {
        if (stopped) return
        runCatching { vehicle.initialize() }.onFailure {
            issue("initialize", it)
            emit(VehicleEvent.Connection(false, it.message))
            main.postDelayed({ initialize(attempt + 1) }, Backoff.delayMs(attempt))
            return
        }
        // initialize() binds to the Pleos vehicle service asynchronously: probe until it answers
        probe(attempt = 0)
    }

    private fun probe(attempt: Int) {
        main.postDelayed({
            if (stopped) return@postDelayed
            runCatching {
                vehicle.getOdometer().getCurrentDistanceDriven({ km ->
                    main.post {
                        if (stopped) return@post
                        quiet.heard()
                        emit(VehicleEvent.Connection(true))
                        km?.let { emit(VehicleEvent.Odometer(it.toDouble())) }
                        if (!registered) {
                            registered = true
                            register()
                        }
                    }
                }, {
                    issue("probe", it)
                    main.post { probe(attempt + 1) }
                })
            }.onFailure {
                issue("probe", it)
                probe(attempt + 1)
            }
        }, Backoff.delayMs(attempt))
    }

    private fun register() = runCatching {
        vehicle.getOdometer().registerDistanceDriven(odometerListener)
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ gearIs(it.toGear()) }, { issue("gear", it) })
        gear.registerGearSelection(gearListener)
        vehicle.getCarInfo().getVINInfo({ emit(VehicleEvent.Vin(it)) }, { issue("vin", it) })
    }.onFailure {
        issue("register", it)
        emit(VehicleEvent.Connection(false, it.message))
    }

    /**
     * Called by the monitor every tick: no value for [QUIET_MS] while the car is in D, R or N means the binding is gone.
     * A fresh SDK object is initialised and probed, and registers its listeners once it answers.
     */
    fun checkQuiet() {
        if (stopped || !registered || !quiet.reconnectDue(lastGear)) return
        Log.w(TAG, "no vehicle data for ${QUIET_MS / 60_000} min outside P: connecting again")
        emit(VehicleEvent.Connection(false, "no data"))
        release()
        vehicle = Vehicle(app)
        initialize(attempt = 0)
    }

    private fun release() {
        runCatching { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.release() }
        registered = false
        lastGear = Gear.UNKNOWN // the next gear comes from the next link
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        release()
    }

    private fun issue(what: String, e: Throwable) {
        Log.w(TAG, "$what: ${e.message}")
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        null -> Gear.UNKNOWN
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE and manual-range gears all mean the car is in motion gear
    }

    companion object {
        private const val TAG = "AutoBriefVehicle"

        /** Outside P the SDK reports the odometer every few seconds: three minutes without anything is a lost link. */
        const val QUIET_MS = 3 * 60_000L
    }
}
