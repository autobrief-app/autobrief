package app.autobrief.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.autobrief.car.domain.VehicleControl
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos

/**
 * Pleos Connect Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`) — read APIs only, plus the ambient
 * brightness setter, which the vehicle may refuse. Permissions: pleos.car.permission.CAR_DRIVE
 * (odometer, gear), CAR_SEATS, CAR_LIGHTS, CAR_DOORS, CAR_INFO.
 */
class PleosVehicleSource(context: Context, private val emit: (VehicleEvent) -> Unit) : VehicleControl {
    // emit() posts to the main thread itself (see VehicleMonitorService), so SDK callback threads are safe here

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val doorLocks = mutableMapOf<DoorValue, Boolean?>()
    private var ambientArea: SeatArea? = null

    private val seatValues = mapOf(
        SeatPos.ROW1_RIGHT to SeatValue.ROW_1_RIGHT,
        SeatPos.ROW2_LEFT to SeatValue.ROW_2_LEFT,
        SeatPos.ROW2_RIGHT to SeatValue.ROW_2_RIGHT,
    )
    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

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

    private val seatListeners = seatValues.mapValues { (pos, _) ->
        Triple(
            object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) = emit(VehicleEvent.SeatOccupancy(pos, occupied))
                override fun onFailed(e: Exception) = issue("seat occupancy", e)
            },
            object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = emit(VehicleEvent.SeatBelt(pos, buckled))
                override fun onFailed(e: Exception) = issue("seat belt", e)
            },
            object : SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(position: Int?) = emit(VehicleEvent.Backrest(pos, position))
                override fun onFailed(e: Exception) = issue("backrest", e)
            },
        )
    }

    private val readingLightListeners = seatValues.mapValues { (pos, _) ->
        object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = emit(VehicleEvent.ReadingLight(pos, state?.let { it == LightState.ON }))
            override fun onFailed(e: Exception) = issue("reading light", e)
        }
    }

    private val doorListeners = doors.associateWith { door ->
        object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(locked: Boolean?) {
                val all = synchronized(doorLocks) {
                    doorLocks[door] = locked
                    doors.map { doorLocks[it] }
                }
                emit(VehicleEvent.DoorsLocked(if (all.any { it == null }) null else all.all { it == true }))
            }
            override fun onFailed(e: Exception) = issue("door lock", e)
        }
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

    private var registered = false

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

        val seat = vehicle.getSeat()
        seat.checkSeatBackrestAnglePositionCapabilities({ _, bounds ->
            emit(VehicleEvent.BackrestBounds(bounds.entries.mapNotNull { (area, b) ->
                val pos = seatValues.entries.firstOrNull { it.value in area.values }?.key ?: return@mapNotNull null
                pos to (b.minValue..b.maxValue)
            }.toMap()))
        }, { issue("backrest bounds", it) })
        val light = vehicle.getLight()
        seatValues.forEach { (pos, value) ->
            val area = SeatArea(value)
            val (occ, belt, back) = seatListeners.getValue(pos)
            seat.getCurrentSeatOccupancy(area, { emit(VehicleEvent.SeatOccupancy(pos, it)) }, { issue("seat occupancy", it) })
            seat.registerSeatOccupancy(area, occ)
            seat.getCurrentSeatBeltBuckledState(area, { emit(VehicleEvent.SeatBelt(pos, it)) }, { issue("seat belt", it) })
            seat.registerSeatBeltBuckledState(area, belt)
            seat.getCurrentSeatBackrestAnglePosition(area, { emit(VehicleEvent.Backrest(pos, it)) }, { issue("backrest", it) })
            seat.registerSeatBackrestAnglePosition(area, back)
            light.getCurrentReadingLightState(area, { st -> emit(VehicleEvent.ReadingLight(pos, st?.let { it == LightState.ON })) }, { issue("reading light", it) })
            light.registerReadingLightState(area, readingLightListeners.getValue(pos))
        }
        light.checkAmbientLightBrightnessCapabilities({ _, bounds ->
            val first = bounds.entries.firstOrNull()
            ambientArea = first?.key
            emit(VehicleEvent.AmbientBounds(first?.value?.let { it.minValue..it.maxValue }))
        }, { issue("ambient bounds", it) })

        val door = vehicle.getDoor()
        doors.forEach { d ->
            val area = DoorArea(d)
            door.getDoorLockState(area, { doorListeners.getValue(d).onDoorLockStateUpdated(it) }, { issue("door lock", it) })
            door.registerDoorLockState(area, doorListeners.getValue(d))
        }
    }.onFailure {
        issue("register", it)
        emit(VehicleEvent.Connection(false, it.message))
    }

    override fun readAmbientBrightness(onResult: (Int?) -> Unit) {
        val area = ambientArea ?: return onResult(null)
        runCatching {
            vehicle.getLight().getAmbientLightBrightness(area, { v -> main.post { onResult(v) } }, { main.post { onResult(null) } })
        }.onFailure { onResult(null) }
    }

    override fun setAmbientBrightness(value: Int, onResult: (Boolean) -> Unit) {
        val area = ambientArea ?: return onResult(false)
        runCatching {
            vehicle.getLight().setAmbientLightBrightness(area, value, { _, _ -> main.post { onResult(true) } }, {
                issue("ambient set", it)
                main.post { onResult(false) }
            })
        }.onFailure { onResult(false) }
    }

    fun stop() {
        runCatching {
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener)
            vehicle.getGear().unregisterGearSelection(gearListener)
            val seat = vehicle.getSeat()
            val light = vehicle.getLight()
            seatValues.forEach { (pos, value) ->
                val area = SeatArea(value)
                val (occ, belt, back) = seatListeners.getValue(pos)
                seat.unregisterSeatOccupancy(area, occ)
                seat.unregisterSeatBeltBuckledState(area, belt)
                seat.unregisterSeatBackrestAnglePosition(area, back)
                light.unregisterReadingLightState(area, readingLightListeners.getValue(pos))
            }
            doors.forEach { d -> vehicle.getDoor().unregisterDoorLockState(DoorArea(d), doorListeners.getValue(d)) }
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
