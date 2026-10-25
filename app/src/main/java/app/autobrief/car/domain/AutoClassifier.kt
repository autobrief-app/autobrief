package app.autobrief.car.domain

import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Purpose rules from places the owner set up: the navigation's HOME/OFFICE bookmarks (or places registered
 * in the app) and the client places. Only the place label is kept on the trip; the departure and arrival
 * coordinates are compared in memory and dropped.
 *
 * 집→회사 or 회사→집 = 출퇴근; arriving at a client, or 거래처→회사 = 일반 업무; anything else is left
 * for the driver (screen or voice) — the classifier never guesses a purpose for an unknown place.
 */
object AutoClassifier {

    /** Parking spots sit around a building, not on its pin. */
    const val RADIUS_M = 300.0

    data class Result(
        val purpose: TripPurpose?,
        val reason: String?,
        val startLabel: String?,
        val endLabel: String?,
        val memo: String = "",
    )

    fun classify(start: GeoPoint?, end: GeoPoint?, places: List<Place>): Result {
        val from = start?.let { nearest(it, places) }
        val to = end?.let { nearest(it, places) }
        val purpose: TripPurpose?
        val reason: String?
        var memo = ""
        when {
            from?.kind == PlaceKind.HOME && to?.kind == PlaceKind.OFFICE -> {
                purpose = TripPurpose.COMMUTE; reason = "${from.label}→${to.label}"
            }
            from?.kind == PlaceKind.OFFICE && to?.kind == PlaceKind.HOME -> {
                purpose = TripPurpose.COMMUTE; reason = "${from.label}→${to.label}"
            }
            to?.kind == PlaceKind.CLIENT -> {
                purpose = TripPurpose.BUSINESS; reason = "거래처 도착(${to.label})"; memo = "${to.label} 방문"
            }
            from?.kind == PlaceKind.CLIENT && to?.kind == PlaceKind.OFFICE -> {
                purpose = TripPurpose.BUSINESS; reason = "${from.label}→${to.label}"; memo = "${from.label} 방문 후 복귀"
            }
            else -> {
                purpose = null; reason = null
            }
        }
        return Result(purpose, reason, from?.label, to?.label, memo)
    }

    /** The closest place within [RADIUS_M]; client places win a tie with home/office only by distance. */
    fun nearest(p: GeoPoint, places: List<Place>): Place? =
        places.map { it to distanceM(p, it.point) }
            .filter { it.second <= RADIUS_M }
            .minByOrNull { it.second }
            ?.first

    /** Haversine distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
