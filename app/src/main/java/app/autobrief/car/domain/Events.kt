package app.autobrief.car.domain

import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind

/** Values coming from the Pleos Vehicle SDK. */
sealed interface VehicleEvent {
    data class Connection(val connected: Boolean, val detail: String? = null) : VehicleEvent
    data class Odometer(val km: Double) : VehicleEvent
    data class GearChanged(val gear: Gear) : VehicleEvent
    data class Vin(val vin: String?) : VehicleEvent

    /** Vehicle Fuel.getCumulativeFuelEconomy in L/100 km (CAR_ENERGY); null = no value, or the call failed. */
    data class FuelEconomy(val litersPer100Km: Double?) : VehicleEvent

    /** Vehicle EvBattery.getCumulativeEvEfficiency in km/kWh (CAR_ENERGY); null = no value, or the call failed. */
    data class EvEfficiency(val kmPerKwh: Double?) : VehicleEvent
}

/** Values coming from the Pleos NaviHelper SDK (results always arrive through its listener). */
sealed interface NaviEvent {
    data class Ready(val ready: Boolean) : NaviEvent
    data class RouteStarted(val destinationName: String?, val tollFareKrw: Int?) : NaviEvent
    data class Destination(
        val name: String?,
        val remainDistanceM: Int?,
        val remainTimeS: Int?,
        val tollFareKrw: Int?,
        val point: GeoPoint? = null,
    ) : NaviEvent
    data class Arrived(val name: String?) : NaviEvent
    data object RouteEnded : NaviEvent

    /** NaviHelper CurrentLocationInfo; kept in memory only, compared with places and dropped. */
    data class Location(val point: GeoPoint) : NaviEvent

    /** NaviHelper BookmarkInfo entries (HOME, OFFICE, and saved DEFAULT client bookmarks). */
    data class Bookmarks(val places: List<NaviBookmark>) : NaviEvent
}

data class NaviBookmark(val kind: PlaceKind, val name: String, val point: GeoPoint)

/**
 * A fix from the car's own position (Pleos FusedLocation SDK), used when navigation has no fresh position: [epochMs] is
 * when the fix was taken, [accuracyM] its horizontal accuracy when the source documents one (FusedLocation does not: null). Kept in memory only.
 */
data class PlatformFix(val point: GeoPoint, val epochMs: Long, val accuracyM: Float? = null)
