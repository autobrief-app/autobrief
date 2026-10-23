package app.autobrief.car.domain

import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos

/** Values coming from the Pleos Vehicle SDK and the AAOS car service. */
sealed interface VehicleEvent {
    data class Connection(val connected: Boolean, val detail: String? = null) : VehicleEvent
    data class Odometer(val km: Double) : VehicleEvent
    data class GearChanged(val gear: Gear) : VehicleEvent
    data class Speed(val kph: Double) : VehicleEvent
    data class Vin(val vin: String?) : VehicleEvent
    data class SeatOccupancy(val seat: SeatPos, val occupied: Boolean?) : VehicleEvent
    data class SeatBelt(val seat: SeatPos, val buckled: Boolean?) : VehicleEvent
    data class Backrest(val seat: SeatPos, val position: Int?) : VehicleEvent
    data class BackrestBounds(val bounds: Map<SeatPos, IntRange>) : VehicleEvent
    data class ReadingLight(val seat: SeatPos, val on: Boolean?) : VehicleEvent
    data class DoorsLocked(val locked: Boolean?) : VehicleEvent
    data class AmbientBounds(val range: IntRange?) : VehicleEvent
}

/** Values coming from the Pleos NaviHelper SDK. */
sealed interface NaviEvent {
    data class RouteStarted(val destinationName: String?, val tollFareKrw: Int?) : NaviEvent
    data class Destination(
        val name: String?,
        val address: String?,
        val remainDistanceM: Int?,
        val remainTimeS: Int?,
        val tollFareKrw: Int?,
    ) : NaviEvent
    data class Arrived(val name: String?) : NaviEvent
    data class SpeedLimit(val kph: Int) : NaviEvent
    data object RouteEnded : NaviEvent
}

/**
 * Vehicle-side effects the core asks for. Both calls answer asynchronously on the main thread; a
 * refusal (vehicle or approved permission scope does not allow it) answers null / false.
 */
interface VehicleControl {
    fun readAmbientBrightness(onResult: (Int?) -> Unit)
    fun setAmbientBrightness(value: Int, onResult: (Boolean) -> Unit)
}
