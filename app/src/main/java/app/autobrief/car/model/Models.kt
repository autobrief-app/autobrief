package app.autobrief.car.model

import kotlinx.serialization.Serializable

@Serializable
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * Logbook categories of the NTS form 「업무용승용차 운행기록부」: column ⑧ 출·퇴근용 and ⑨ 일반 업무용
 * count as business use; everything else is non-business. A trip stays UNCLASSIFIED until the user
 * picks a purpose — it is never assumed to be business.
 */
@Serializable
enum class TripPurpose(val label: String, val countsAsBusiness: Boolean) {
    UNCLASSIFIED("미분류", false),
    COMMUTE("출퇴근", true),
    BUSINESS("일반 업무", true),
    PERSONAL("비업무", false),
    UNLOGGED_GAP("미기록 구간", false),
}

/** Column ④ 사용자: the person who used the car (not necessarily the driver, e.g. with a designated driver). */
@Serializable
data class CarUser(val name: String, val department: String)

@Serializable
data class TripRecord(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val purpose: TripPurpose,
    val user: CarUser?,
    val memo: String,
    /** NaviHelper DestinationInfo.tollFare at departure: the navigation's toll estimate for the route, not a charge. */
    val routeTollEstimateKrw: Int? = null,
    val destinationName: String? = null,
    /** "화면" or "음성" once classified; null while unclassified. */
    val classifiedBy: String? = null,
    val designatedDriver: Boolean = false,
) {
    val distanceKm: Double get() = endOdometerKm - startOdometerKm
}

@Serializable
data class AppSettings(
    val entityName: String = "",
    val businessNumber: String = "",
    val vehicleModel: String = "",
    val plateNumber: String = "",
    /** First month of the business year (사업연도); 1 = calendar year. */
    val fiscalYearStartMonth: Int = 1,
    val users: List<CarUser> = emptyList(),
    val defaultUserIndex: Int = 0,
    /** The owner's own HTTPS endpoint; it forwards to cloud storage, e-mail or a messenger. Empty = not set. */
    val archiveWebhookUrl: String = "",
    val familyWebhookUrl: String = "",
    val sleepCareEnabled: Boolean = true,
    val dimAmbientOnSleep: Boolean = true,
    /** SHA-256 of salt + PIN; empty = no PIN set yet. */
    val custodyPinHash: String = "",
    val custodyPinSalt: String = "",
    /** Shows the labelled demo-signal buttons (the Virtual Console lacks some cards); family notices are off meanwhile. */
    val demoMode: Boolean = false,
)

/** Passenger seats the sleep detector watches (the driver seat is never a sleep candidate). */
@Serializable
enum class SeatPos(val label: String) {
    ROW1_RIGHT("조수석"),
    ROW2_LEFT("2열 왼쪽"),
    ROW2_RIGHT("2열 오른쪽"),
}

data class SeatState(
    val occupied: Boolean? = null,
    val belted: Boolean? = null,
    /** Raw Pleos backrest position; AAOS convention: min = fully reclined, max = most upright. */
    val backrestPosition: Int? = null,
    val backrestMin: Int? = null,
    val backrestMax: Int? = null,
    val readingLightOn: Boolean? = null,
) {
    /** 0 = upright, 1 = fully reclined; null when the position or its bounds are unknown. */
    val reclineRatio: Double?
        get() {
            val pos = backrestPosition ?: return null
            val min = backrestMin ?: return null
            val max = backrestMax ?: return null
            if (max <= min) return null
            return ((max - pos).toDouble() / (max - min)).coerceIn(0.0, 1.0)
        }
}

data class VehicleSnapshot(
    val sdkConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val odometerKm: Double? = null,
    val speedKph: Double? = null,
    val vin: String? = null,
    val seats: Map<SeatPos, SeatState> = emptyMap(),
    val doorsLocked: Boolean? = null,
    val ambientBrightnessBounds: IntRange? = null,
)

@Serializable
enum class CustodyEndReason { ARRIVED, OWNER_ENDED_EARLY, INTERRUPTED }

/** A running designated-driver session, saved so a restart can report that recording stopped. */
@Serializable
data class ActiveCustody(
    val sessionId: String,
    val startEpochMs: Long,
    val startOdometerKm: Double?,
    val destinationLabel: String?,
    val notifyFamily: Boolean,
)

@Serializable
data class CustodyReport(
    val sessionId: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val startOdometerKm: Double?,
    val endOdometerKm: Double?,
    val destinationLabel: String?,
    val maxSpeedKph: Double,
    val overspeedEpisodes: Int,
    val harshAccelEvents: Int,
    val harshBrakeEvents: Int,
    val endReason: CustodyEndReason,
    val doorsLocked: Boolean?,
    val driverConsented: Boolean,
    val intermediateStops: Int = 0,
    /** 0 = no speed reading arrived (permission refused or no speed source), so the counts mean nothing. */
    val speedSamples: Int = 0,
    val speedLimitKnown: Boolean = false,
) {
    val distanceKm: Double?
        get() = if (startOdometerKm != null && endOdometerKm != null) endOdometerKm - startOdometerKm else null
}

/** A message waiting for the owner's webhook; `deliveredEpochMs` is set only after an HTTP 2xx. */
@Serializable
data class OutboxItem(
    val key: String,
    val kind: String,
    val payloadJson: String,
    val createdEpochMs: Long,
    val attempts: Int = 0,
    val deliveredEpochMs: Long? = null,
    val lastError: String? = null,
)
