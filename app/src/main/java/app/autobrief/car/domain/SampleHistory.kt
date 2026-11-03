package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TaxRate
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import app.autobrief.car.model.TripSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

/**
 * The FICTIONAL history the demo mode loads and the sample files quote (labelled 가상 예시 everywhere):
 * a business car used by two fictional employees from 2026-01-02, weekday commutes, client visits and a
 * few weekend personal trips, one stretch the app missed (2026-03-14, 27 km). The odometer chain ends at
 * 45,200 km, where the live demo drive starts. Same plan as the v1.6 sample, so the numbers already
 * quoted (to 2026-10-22: 487 rows, 9,636 km, 94.6%) stay reproducible.
 */
object SampleHistory {
    val SALES = CarUser("김기현", "영업팀")
    val PLANNING = CarUser("이동재", "기획팀")

    /** Public landmarks standing in for fictional places; the app stores only the labels on trips. */
    val HOME = Place("집", PlaceKind.HOME, GeoPoint(37.5444, 127.0374))
    val OFFICE = Place("회사", PlaceKind.OFFICE, GeoPoint(37.3947, 127.1112))
    val CLIENT = Place("일양상사", PlaceKind.CLIENT, GeoPoint(37.5126, 127.0590))

    const val END_ODOMETER_KM = 45_200
    const val GAP_KM = 27
    val GAP_DAY: LocalDate = LocalDate.of(2026, 3, 14)
    val FIRST_DAY: LocalDate = LocalDate.of(2026, 1, 2)

    private val CLIENTS = listOf("일양상사 미팅", "한빛정밀 납품 협의", "서진물류 현장 점검", "협력사 교육 참석", "대성전자 계약 협의")
    private val HOLIDAYS = setOf(
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02", "2026-05-05", "2026-05-25",
        "2026-06-03", "2026-08-17", "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25",
    ).map(LocalDate::parse).toSet()

    fun settings(webhookUrl: String = ""): AppSettings = AppSettings(
        entityName = "한성물산 주식회사",
        businessNumber = "000-00-00000",
        vehicleModel = "그랜저 하이브리드",
        plateNumber = "00가 0000",
        users = listOf(SALES, PLANNING),
        defaultUserIndex = 0,
        archiveWebhookUrl = webhookUrl,
        places = listOf(HOME, OFFICE, CLIENT),
        // fictional year: lease 1,320 + fuel 280 + insurance 170 + tax/repair 70 + tolls 60 (만원)
        annualCarCostKrw = 19_000_000,
        annualDepreciationKrw = 11_500_000,
        taxRate = TaxRate.CORP_22,
    )

    data class Planned(
        val start: Long,
        val end: Long,
        val km: Int,
        val purpose: TripPurpose,
        val user: CarUser,
        val memo: String,
        val by: String,
        val autoReason: String?,
        val startPlace: String?,
        val endPlace: String?,
    )

    fun at(d: LocalDate, h: Int, m: Int): Long =
        ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, KST).toInstant().toEpochMilli()

    /** Trips from [FIRST_DAY] up to (not including) [endExclusive]. */
    fun plan(endExclusive: LocalDate): List<Planned> {
        val out = mutableListOf<Planned>()
        var d = FIRST_DAY
        while (d.isBefore(endExclusive)) {
            val n = d.dayOfYear
            val weekday = d.dayOfWeek.value <= 5 && d !in HOLIDAYS
            if (weekday) {
                val user = if (d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0) SALES else PLANNING
                val commute = 14 + n % 5
                out += Planned(at(d, 8, 10), at(d, 8, 44), commute, TripPurpose.COMMUTE, user, "출근",
                    ClassifiedBy.AUTO, "집→회사", "집", "회사")
                if (n % 3 == 0) {
                    out += Planned(at(d, 14, 0), at(d, 15, 5), 20 + (n * 7) % 37, TripPurpose.BUSINESS, user, CLIENTS[n % CLIENTS.size],
                        if (n % 2 == 0) ClassifiedBy.VOICE else ClassifiedBy.SCREEN, null, "회사", null)
                }
                out += Planned(at(d, 18, 30), at(d, 19, 4), commute, TripPurpose.COMMUTE, user, "퇴근",
                    ClassifiedBy.AUTO, "회사→집", "회사", "집")
            } else if (d.dayOfWeek == DayOfWeek.SATURDAY && n % 2 == 0) {
                out += Planned(at(d, 11, 0), at(d, 11, 40), 12 + n % 23, TripPurpose.PERSONAL, SALES, "", ClassifiedBy.SCREEN, null, "집", null)
            }
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * Rows and ledger for a history ending the day before [endExclusive]; the odometer ends at
     * [END_ODOMETER_KM]. Every ledger entry is stamped with the time the app would have written it.
     */
    fun build(endExclusive: LocalDate, vin: String? = SAMPLE_VIN): Pair<List<TripRecord>, Ledger> {
        val planned = plan(endExclusive)
        val gapBefore = planned.indexOfFirst { it.start > at(GAP_DAY, 0, 0) }.takeIf { endExclusive.isAfter(GAP_DAY) } ?: -1
        val historyKm = planned.sumOf { it.km } + if (gapBefore >= 0) GAP_KM else 0
        var odo = END_ODOMETER_KM - historyKm
        val rows = mutableListOf<TripRecord>()
        val ledger = Ledger()
        planned.forEachIndexed { i, p ->
            if (i == gapBefore) {
                val last = rows.last()
                val gap = TripRecord(
                    id = "GAP-${p.start}", startEpochMs = last.endEpochMs, endEpochMs = p.start,
                    startOdometerKm = odo.toDouble(), endOdometerKm = (odo + GAP_KM).toDouble(),
                    purpose = TripPurpose.UNLOGGED_GAP, user = null, memo = TripLogbook.GAP_MEMO, source = TripSource.SAMPLE,
                )
                rows += gap
                ledger.append(LedgerKind.GAP, gap.id, Ledger.tripBody(gap, vin), p.start)
                odo += GAP_KM
            }
            val trip = TripRecord(
                id = "TRIP-${p.start}", startEpochMs = p.start, endEpochMs = p.end,
                startOdometerKm = odo.toDouble(), endOdometerKm = (odo + p.km).toDouble(),
                purpose = TripPurpose.UNCLASSIFIED, user = null, memo = "",
                startPlace = p.startPlace, endPlace = p.endPlace, source = TripSource.SAMPLE,
            )
            ledger.append(LedgerKind.TRIP, trip.id, Ledger.tripBody(trip, vin), p.end)
            val classified = trip.copy(purpose = p.purpose, user = p.user, memo = p.memo, classifiedBy = p.by, autoReason = p.autoReason)
            // automatic rows are classified the moment the car parks; screen/voice rows a minute later
            ledger.append(LedgerKind.CLASSIFY, trip.id, Ledger.classifyBody(classified), if (p.by == ClassifiedBy.AUTO) p.end else p.end + 60_000)
            rows += classified
            odo += p.km
        }
        check(odo == END_ODOMETER_KM) { "sample chain must end at $END_ODOMETER_KM km, ended at $odo" }
        return rows to ledger
    }

    const val SAMPLE_VIN = "KMHSAMPLE00000000"
}
