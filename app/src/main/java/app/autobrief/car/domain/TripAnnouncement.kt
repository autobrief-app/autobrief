package app.autobrief.car.domain

import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord

/**
 * What the car says once a parked trip's purpose is settled: the line the parked screen shows, so the driver need
 * not look at it. Purpose and distance only; a place name is never spoken.
 */
object TripAnnouncement {
    fun text(trip: TripRecord): String? {
        val km = spokenKm(TripLogbook.printedKm(trip))
        return when {
            trip.family -> "가족 운행을 비업무로 기록했습니다. ${km}이고, 위치는 읽지 않았습니다."
            trip.purpose == TripPurpose.COMMUTE -> "출퇴근으로 기록했습니다. ${km}입니다."
            trip.purpose == TripPurpose.BUSINESS -> "일반 업무로 기록했습니다. ${km}입니다."
            trip.purpose == TripPurpose.PERSONAL -> "비업무로 기록했습니다. ${km}입니다."
            trip.purpose == TripPurpose.UNCLASSIFIED -> "${km} 운행했습니다. 목적을 골라 주세요."
            else -> null // an unlogged gap is not a trip the driver just made
        }
    }

    /** "18킬로미터", "7.4킬로미터": the logbook's one decimal, without a trailing ".0". */
    private fun spokenKm(km: Double): String {
        val tenths = Math.round(km * 10)
        return if (tenths % 10 == 0L) "${tenths / 10}킬로미터" else "${tenths / 10}.${tenths % 10}킬로미터"
    }
}
