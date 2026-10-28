package app.autobrief.car.domain

import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

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
    )

    val records: List<TripRecord> get() = rows.toList()
    val openTrip: OpenTrip? get() = open
    val tripInProgress: Boolean get() = open != null

    /** Gear left P before an odometer value was taken (cold start, value in doubt): the trip opens at the next one. */
    private var pendingStart: OpenTrip? = null

    /** The last odometer value the logbook took; null until one is taken (see [onOdometer]). */
    val odometerKm: Double? get() = latestOdometerKm

    /** Which readings are taken: never a NaN, a start-up 0 or a step back or jump that the next reading does not confirm. */
    private val screen = OdometerScreen()

    private fun references(): List<Double> =
        screen.references(latestOdometerKm, listOfNotNull(rows.lastOrNull()?.endOdometerKm, open?.startOdometerKm))

    /** The dashboard odometer, screened by [OdometerScreen]; while a value is in doubt a departure waits for the next one. */
    fun onOdometer(km: Double): List<TripRecord> = screen.screen(km, latestOdometerKm, references()).flatMap(::takeOdometer)

    /**
     * The car's current value when this logbook takes over (switching data space): taken as it is, as the other
     * logbook already checked it, unless it cannot be a reading at all.
     */
    fun seedOdometer(km: Double) {
        if (!screen.impossible(km, references())) latestOdometerKm = km
    }

    /** A trip resumed after a restart met P before any odometer value: it closes at the next value, at this time. */
    private var closeAtNextValueMs: Long? = null

    /**
     * A trip that was open when the app stopped (saved when it opened) goes on and closes at the next P. Refused when
     * its row exists already (the app stopped after writing the row, before clearing the saved trip).
     */
    fun resume(trip: OpenTrip): Boolean {
        if (open != null || rows.any { it.id == tripId(trip.startEpochMs) }) return false
        open = trip
        return true
    }

    private fun takeOdometer(km: Double): List<TripRecord> {
        screen.taken()
        latestOdometerKm = km
        closeAtNextValueMs?.let { at ->
            closeAtNextValueMs = null
            return listOfNotNull(closeOpen(at, km))
        }
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
    ): List<TripRecord> {
        val previous = lastGear
        lastGear = gear
        if (gear == Gear.UNKNOWN) return emptyList()
        if (gear == Gear.PARK) pendingStart = null else closeAtNextValueMs = null
        val odo = latestOdometerKm
        val departing = gear != Gear.PARK && open == null && previous != gear
        if (odo == null || (departing && screen.inDoubt)) {
            // remember the departure; the trip opens at the next odometer value taken
            if (gear != Gear.PARK && open == null) pendingStart = pendingStart ?: OpenTrip(nowEpochMs, 0.0, routeTollEstimateKrw)
            // a trip resumed after a restart found in P: it closes as soon as the odometer is known
            if (gear == Gear.PARK && open != null) closeAtNextValueMs = nowEpochMs
            return emptyList()
        }
        val added = mutableListOf<TripRecord>()
        if (departing) {
            added += gapRowIfMoved(odo, nowEpochMs)
            open = OpenTrip(nowEpochMs, odo, routeTollEstimateKrw)
        } else if (gear == Gear.PARK) {
            closeOpen(nowEpochMs, odo)?.let { added += it }
        }
        return added
    }

    /** Closes the open trip at [odo]; a trip that moved a few metres only is no logbook row. */
    private fun closeOpen(nowEpochMs: Long, odo: Double): TripRecord? {
        val trip = open ?: return null
        open = null
        if (odo - trip.startOdometerKm <= 0.0) return null
        val row = TripRecord(
            id = tripId(trip.startEpochMs),
            startEpochMs = trip.startEpochMs,
            endEpochMs = nowEpochMs,
            startOdometerKm = trip.startOdometerKm,
            endOdometerKm = odo,
            purpose = TripPurpose.UNCLASSIFIED,
            user = null,
            memo = "",
            routeTollEstimateKrw = trip.tollEstimateKrw,
        )
        rows += row
        return row
    }

    private fun tripId(startEpochMs: Long) = "TRIP-$startEpochMs"

    /** A toll estimate that arrives after departure (navigation started late) still belongs to the open trip. */
    fun updateOpenRoute(tollEstimateKrw: Int?) {
        val o = open ?: return
        open = o.copy(tollEstimateKrw = tollEstimateKrw ?: o.tollEstimateKrw)
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

    /**
     * Marks [id] as a family trip (가족 운행): only its distance is kept, so no places and no route toll estimate (a toll
     * says something about where the car went).
     */
    fun markFamily(id: String): TripRecord? {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return null
        rows[index] = rows[index].copy(family = true, startPlace = null, endPlace = null, routeTollEstimateKrw = null)
        return rows[index]
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

        /** Sums ⑦ as printed, so ⑪ and ⑫ always equal the column totals of the exported rows. */
        fun summarize(records: List<TripRecord>): LogbookSummary {
            fun km(rows: List<TripRecord>) = round1(rows.sumOf { printedKm(it) })
            fun of(p: TripPurpose) = records.filter { it.purpose == p }
            val total = km(records)
            val commute = km(of(TripPurpose.COMMUTE))
            val business = km(of(TripPurpose.BUSINESS))
            val unclassified = of(TripPurpose.UNCLASSIFIED)
            val gaps = of(TripPurpose.UNLOGGED_GAP)
            val breaks = records.sortedBy { it.startEpochMs }.zipWithNext()
                .count { (a, b) -> abs(b.startOdometerKm - a.endOdometerKm) > CONTINUITY_TOLERANCE_KM }
            return LogbookSummary(
                rowCount = records.size,
                totalKm = total,
                commuteKm = commute,
                businessKm = business,
                personalKm = km(of(TripPurpose.PERSONAL)),
                unclassifiedKm = km(unclassified),
                gapKm = km(gaps),
                businessUseRatio = if (total > 0) round1(commute + business) / total else null,
                unclassifiedCount = unclassified.size,
                gapRowCount = gaps.size,
                continuityBreaks = breaks,
                autoCount = records.count { it.classifiedBy == ClassifiedBy.AUTO && !it.amended },
                voiceCount = records.count { it.classifiedBy == ClassifiedBy.VOICE },
            )
        }

        /**
         * Half-up to 0.1 (반올림) on the decimal value: the rule `%.1f` prints with, so a reading shows the same digits in
         * the form and in the ledger sheet (45,218.25 → 45,218.3, 100.05 → 100.1). Non-finite values pass through.
         */
        fun round1(v: Double): Double = if (v.isFinite()) BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toDouble() else v

        /** ⑦ as printed: ⑥ and ⑤ are each rounded once to 0.1 km, then subtracted, so every row reads ⑦ = ⑥ − ⑤. */
        fun printedKm(r: TripRecord): Double = round1(round1(r.endOdometerKm) - round1(r.startOdometerKm))
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
