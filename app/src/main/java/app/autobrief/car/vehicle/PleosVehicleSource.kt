package app.autobrief.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear

/**
 * Pleos Connect Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`), read APIs only:
 * Odometer (dashboard total distance, km) and Gear (CAR_DRIVE), CarInfo VIN (CAR_INFO).
 */
class PleosVehicleSource(context: Context, private val emit: (VehicleEvent) -> Unit) {
    // emit() posts to the main thread itself (see VehicleMonitorService), so SDK callback threads are safe here

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(km: Float?) {
            km?.let { emit(VehicleEvent.Odometer(it.toDouble())) }
        }
        override fun onFailed(e: Exception) = issue("odometer", e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = emit(VehicleEvent.GearChanged(gear.toGear()))
        override fun onFailed(e: Exception) = issue("gear", e)
    }

    fun start() {
        runCatching { vehicle.initialize() }.onFailure {
            issue("initialize", it)
            emit(VehicleEvent.Connection(false, it.message))
            return
        }
        // initialize() binds to the Pleos vehicle service asynchronously: probe until it answers
        probe(attempt = 0)
    }

    private fun probe(attempt: Int) {
        val delay = (BIND_DELAY_MS shl attempt.coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
        main.postDelayed({
            runCatching {
                vehicle.getOdometer().getCurrentDistanceDriven({ km ->
                    main.post {
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
        }, delay)
    }

    private fun register() = runCatching {
        vehicle.getOdometer().registerDistanceDriven(odometerListener)
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ emit(VehicleEvent.GearChanged(it.toGear())) }, { issue("gear", it) })
        gear.registerGearSelection(gearListener)
        vehicle.getCarInfo().getVINInfo({ emit(VehicleEvent.Vin(it)) }, { issue("vin", it) })
    }.onFailure {
        issue("register", it)
        emit(VehicleEvent.Connection(false, it.message))
    }

    fun stop() {
        runCatching {
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.release()
        }
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
        private const val BIND_DELAY_MS = 1_500L
        private const val MAX_RETRY_MS = 30_000L
    }
}
