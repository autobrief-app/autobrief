package app.autobrief.car.domain

import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.round

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * Turns gear and odometer events into logbook rows.
 *
 * A trip opens when the gear leaves PARK and closes when it returns to PARK; both ends latch the
 * dashboard odometer (Pleos Odometer.getCurrentDistanceDriven, km). If the odometer moved while the app
 * was not running, an UNLOGGED_GAP row (non-business) keeps the chain continuous instead of being
 * silently added to the next trip.
 */
class TripLogbook(initial: List<TripRecord> = emptyList()) {

    private val rows = initial.sortedBy { it.startEpochMs }.toMutableList()
    private var open: OpenTrip? = null
    private var lastGear: Gear = Gear.UNKNOWN
    private var latestOdometerKm: Double? = null

    data class OpenTrip(
        val startEpochMs: Long,
        val startOdometerKm: Double,
        val tollEstimateKrw: Int?,
        val destinationName: String?,
    )

    val records: List<TripRecord> get() = rows.toList()
    val openTrip: OpenTrip? get() = open
    val tripInProgress: Boolean get() = open != null

    /** Gear left P before the first odometer value arrived (cold start): the trip opens at that first value. */
    private var pendingStart: OpenTrip? = null

    fun onOdometer(km: Double): List<TripRecord> {
        latestOdometerKm = km
        val pending = pendingStart ?: return emptyList()
        pendingStart = null
        if (open != null) return emptyList()
        val added = gapRowIfMoved(km, pending.startEpochMs)
        open = pending.copy(startOdometerKm = km)
        return added
    }

    private fun gapRowIfMoved(odo: Double, nowEpochMs: Long): List<TripRecord> {
        val last = rows.lastOrNull() ?: return emptyList()
        if (odo - last.endOdometerKm <= CONTINUITY_TOLERANCE_KM) return emptyList()
        val gapRow = TripRecord(
            id = "GAP-$nowEpochMs",
            startEpochMs = last.endEpochMs,
            endEpochMs = nowEpochMs,
            startOdometerKm = last.endOdometerKm,
            endOdometerKm = odo,
            purpose = TripPurpose.UNLOGGED_GAP,
            user = null,
            memo = GAP_MEMO,
        )
        rows += gapRow
        return listOf(gapRow)
    }

    /** Returns the rows added by this event (a closed trip, possibly preceded by a gap row). */
    fun onGear(
        gear: Gear,
        nowEpochMs: Long,
        routeTollEstimateKrw: Int? = null,
        destinationName: String? = null,
    ): List<TripRecord> {
        val previous = lastGear
        lastGear = gear
        if (gear == Gear.UNKNOWN) return emptyList()
        val odo = latestOdometerKm
        if (odo == null) {
            // remember the departure; the trip opens when the first odometer value arrives
            pendingStart = if (gear != Gear.PARK) OpenTrip(nowEpochMs, 0.0, routeTollEstimateKrw, destinationName) else null
            return emptyList()
        }
        val added = mutableListOf<TripRecord>()
        if (gear != Gear.PARK && open == null && previous != gear) {
            added += gapRowIfMoved(odo, nowEpochMs)
            open = OpenTrip(nowEpochMs, odo, routeTollEstimateKrw, destinationName)
        } else if (gear == Gear.PARK) {
            val trip = open ?: return added
            open = null
            val distance = odo - trip.startOdometerKm
            if (distance <= 0.0) return added // moved a few metres only: not a logbook row
            val row = TripRecord(
                id = "TRIP-${trip.startEpochMs}",
                startEpochMs = trip.startEpochMs,
                endEpochMs = nowEpochMs,
                startOdometerKm = trip.startOdometerKm,
                endOdometerKm = odo,
                purpose = TripPurpose.UNCLASSIFIED,
                user = null,
                memo = "",
                routeTollEstimateKrw = trip.tollEstimateKrw,
                destinationName = trip.destinationName,
            )
            rows += row
            added += row
        }
        return added
    }

    /** Destination info that arrives after departure (navigation started late) still belongs to the open trip. */
    fun updateOpenRoute(tollEstimateKrw: Int?, destinationName: String?) {
        val o = open ?: return
        open = o.copy(tollEstimateKrw = tollEstimateKrw ?: o.tollEstimateKrw, destinationName = destinationName ?: o.destinationName)
    }

    fun latestUnclassified(): TripRecord? = rows.lastOrNull { it.purpose == TripPurpose.UNCLASSIFIED }

    /** The last trip that actually ended (gap rows excluded): the only one a voice command may label. */
    fun lastTrip(): TripRecord? = rows.lastOrNull { it.purpose != TripPurpose.UNLOGGED_GAP }

    fun find(id: String): TripRecord? = rows.firstOrNull { it.id == id }

    /** Sets or changes a purpose; returns (previous, updated) or null for an unknown id or a gap row. */
    fun classify(
        id: String,
        purpose: TripPurpose,
        user: CarUser?,
        memo: String,
        source: String,
        autoReason: String? = null,
    ): Pair<TripRecord, TripRecord>? {
        require(purpose.isUserPurpose) { "not a user purpose: $purpose" }
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || rows[index].purpose == TripPurpose.UNLOGGED_GAP) return null
        val before = rows[index]
        val amended = before.purpose != TripPurpose.UNCLASSIFIED
        val updated = before.copy(
            purpose = purpose,
            user = user ?: before.user,
            memo = memo,
            classifiedBy = source,
            autoReason = if (amended) before.autoReason else autoReason,
            amended = before.amended || amended,
        )
        rows[index] = updated
        return before to updated
    }

    fun setPlaces(id: String, startPlace: String?, endPlace: String?): TripRecord? {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return null
        rows[index] = rows[index].copy(startPlace = startPlace, endPlace = endPlace)
        return rows[index]
    }

    fun recordsIn(period: ClosedRange<LocalDate>): List<TripRecord> =
        rows.filter { localDate(it.startEpochMs) in period }

    fun summarize(period: ClosedRange<LocalDate>): LogbookSummary = summarize(recordsIn(period))

    companion object {
        /**
         * Readings closer than this are the same point on the dashboard (a real car reports the odometer as a Float, a few
         * metres per step). A larger forward jump between two trips becomes an UNLOGGED_GAP row, and any difference larger
         * than this left between consecutive rows counts as a continuity break.
         */
        const val CONTINUITY_TOLERANCE_KM = 0.05
        const val GAP_MEMO = "앱이 기록하지 못한 주행(비업무로 처리)"

        fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(KST).toLocalDate()
        fun yearMonth(epochMs: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(KST))

        fun summarize(records: List<TripRecord>): LogbookSummary {
            val total = records.sumOf { it.distanceKm }
            val commute = records.filter { it.purpose == TripPurpose.COMMUTE }.sumOf { it.distanceKm }
            val business = records.filter { it.purpose == TripPurpose.BUSINESS }.sumOf { it.distanceKm }
            val personal = records.filter { it.purpose == TripPurpose.PERSONAL }.sumOf { it.distanceKm }
            val unclassified = records.filter { it.purpose == TripPurpose.UNCLASSIFIED }
            val gaps = records.filter { it.purpose == TripPurpose.UNLOGGED_GAP }
            val breaks = records.sortedBy { it.startEpochMs }.zipWithNext()
                .count { (a, b) -> abs(b.startOdometerKm - a.endOdometerKm) > CONTINUITY_TOLERANCE_KM }
            return LogbookSummary(
                rowCount = records.size,
                totalKm = round1(total),
                commuteKm = round1(commute),
                businessKm = round1(business),
                personalKm = round1(personal),
                unclassifiedKm = round1(unclassified.sumOf { it.distanceKm }),
                gapKm = round1(gaps.sumOf { it.distanceKm }),
                businessUseRatio = if (total > 0) (commute + business) / total else null,
                unclassifiedCount = unclassified.size,
                gapRowCount = gaps.size,
                continuityBreaks = breaks,
                autoCount = records.count { it.classifiedBy == ClassifiedBy.AUTO && !it.amended },
                voiceCount = records.count { it.classifiedBy == ClassifiedBy.VOICE },
            )
        }

        fun round1(v: Double): Double = round(v * 10.0) / 10.0
    }
}

/** ⑪ total, ⑫ business (commute + general business) and ⑬ business-use ratio of the NTS logbook. */
data class LogbookSummary(
    val rowCount: Int,
    val totalKm: Double,
    val commuteKm: Double,
    val businessKm: Double,
    val personalKm: Double = 0.0,
    val unclassifiedKm: Double = 0.0,
    val gapKm: Double = 0.0,
    val businessUseRatio: Double?,
    val unclassifiedCount: Int,
    val gapRowCount: Int,
    val continuityBreaks: Int,
    val autoCount: Int = 0,
    val voiceCount: Int = 0,
) {
    val businessUseKm: Double get() = TripLogbook.round1(commuteKm + businessKm)
}

/** Filing windows inside a business year that may start in any month (사업연도). */
object TaxPeriods {
    fun businessYear(year: Int, startMonth: Int): ClosedRange<LocalDate> {
        val start = LocalDate.of(year, startMonth, 1)
        return start..start.plusYears(1).minusDays(1)
    }

    /** The business year containing [date]. */
    fun businessYearOf(date: LocalDate, startMonth: Int): ClosedRange<LocalDate> {
        val year = if (date.monthValue >= startMonth) date.year else date.year - 1
        return businessYear(year, startMonth)
    }

    /** half = 1 or 2 within the business year. */
    fun half(year: Int, startMonth: Int, half: Int): ClosedRange<LocalDate> {
        require(half in 1..2)
        val start = LocalDate.of(year, startMonth, 1).plusMonths(6L * (half - 1))
        return start..start.plusMonths(6).minusDays(1)
    }

    /** quarter = 1..4 within the business year. */
    fun quarter(year: Int, startMonth: Int, quarter: Int): ClosedRange<LocalDate> {
        require(quarter in 1..4)
        val start = LocalDate.of(year, startMonth, 1).plusMonths(3L * (quarter - 1))
        return start..start.plusMonths(3).minusDays(1)
    }

    fun month(year: Int, month: Int): ClosedRange<LocalDate> {
        val start = LocalDate.of(year, month, 1)
        return start..start.plusMonths(1).minusDays(1)
    }

    fun month(ym: YearMonth): ClosedRange<LocalDate> = month(ym.year, ym.monthValue)
}
