package app.autobrief.car.model

import kotlinx.serialization.Serializable

@Serializable
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * Logbook categories of the NTS form 「업무용승용차 운행기록부」: column ⑧ 출·퇴근용 and ⑨ 일반 업무용
 * count as business use; everything else is non-business. A trip stays UNCLASSIFIED until a rule the
 * owner set up (home/office/client places) or the user picks a purpose — it is never assumed to be business.
 */
@Serializable
enum class TripPurpose(val label: String, val countsAsBusiness: Boolean) {
    UNCLASSIFIED("미분류", false),
    COMMUTE("출퇴근", true),
    BUSINESS("일반 업무", true),
    PERSONAL("비업무", false),
    UNLOGGED_GAP("미기록 구간", false),
    ;

    val isUserPurpose: Boolean get() = this == COMMUTE || this == BUSINESS || this == PERSONAL
}

/** Who set a trip's purpose; shown in the remarks column so an auditor can tell the sources apart. */
object ClassifiedBy {
    const val SCREEN = "화면"
    const val VOICE = "음성"
    const val AUTO = "자동"
}

/** Column ④ 사용자: the person who used the car. */
@Serializable
data class CarUser(val name: String, val department: String)

/** Where a trip's dashboard values came from. */
object TripSource {
    const val SDK = "SDK"
    const val SAMPLE = "가상 예시"
}

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
    /** [ClassifiedBy] once classified; null while unclassified. */
    val classifiedBy: String? = null,
    /** Place labels matched at departure and arrival ("집", "회사", a client name); coordinates are never stored. */
    val startPlace: String? = null,
    val endPlace: String? = null,
    /** The rule that classified the trip automatically, e.g. "집→회사". */
    val autoReason: String? = null,
    /** The purpose changed after it was first set; the ledger keeps every version. */
    val amended: Boolean = false,
    val source: String = TripSource.SDK,
) {
    val distanceKm: Double get() = endOdometerKm - startOdometerKm
}

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

@Serializable
enum class PlaceKind { HOME, OFFICE, CLIENT }

/** A place the classifier matches against; HOME/OFFICE normally come from the car's navigation bookmarks. */
@Serializable
data class Place(val label: String, val kind: PlaceKind, val point: GeoPoint, val fromNavigation: Boolean = false)

/** Corporate tax brackets for business years starting 2026-01-01, local income tax (10%) included. */
@Serializable
enum class TaxRate(val label: String, val ratePct: Double) {
    CORP_11("법인 과세표준 2억 원 이하 11.0%", 11.0),
    CORP_22("법인 2억 초과 200억 원 이하 22.0%", 22.0),
    CORP_24("법인 200억 초과 3,000억 원 이하 24.2%", 24.2),
    CORP_27("법인 3,000억 원 초과 27.5%", 27.5),
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
    /** The company's or owner's endpoint: it receives every ledger entry and the period workbooks. Empty = not set. */
    val archiveWebhookUrl: String = "",
    /** Places the owner registered in the app (fallback when navigation has no home/office bookmark, and clients). */
    val places: List<Place> = emptyList(),
    val autoClassify: Boolean = true,
    /** Annual car-related cost of the business year (감가상각비·리스료, 유류비, 보험료, 수리비, 자동차세, 통행료). 0 = not entered. */
    val annualCarCostKrw: Long = 0,
    /** Depreciation (or the depreciation-equivalent part of a lease/rent) inside [annualCarCostKrw]. */
    val annualDepreciationKrw: Long = 0,
    val taxRate: TaxRate = TaxRate.CORP_22,
    /** SHA-256 of salt + PIN; empty = no admin PIN yet. */
    val adminPinHash: String = "",
    val adminPinSalt: String = "",
    /** YYYY-MM of the last month whose workbook was closed automatically. */
    val lastMonthlyCloseYm: String = "",
)

data class VehicleSnapshot(
    val sdkConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val odometerKm: Double? = null,
    val vin: String? = null,
)

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
