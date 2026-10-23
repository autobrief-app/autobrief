package app.autobrief.car.domain

import app.autobrief.car.model.CarUser
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.round

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * Mode 1 engine: turns gear and odometer events into logbook rows.
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

    private data class OpenTrip(
        val startEpochMs: Long,
        val startOdometerKm: Double,
        val tollEstimateKrw: Int?,
        val destinationName: String?,
        val designatedDriver: Boolean,
    )

    val records: List<TripRecord> get() = rows.toList()
    val tripInProgress: Boolean get() = open != null

    fun onOdometer(km: Double) {
        latestOdometerKm = km
    }

    /** Returns the rows added by this event (a closed trip, possibly preceded by a gap row). */
    fun onGear(
        gear: Gear,
        nowEpochMs: Long,
        routeTollEstimateKrw: Int? = null,
        destinationName: String? = null,
        designatedDriver: Boolean = false,
    ): List<TripRecord> {
        val previous = lastGear
        lastGear = gear
        val odo = latestOdometerKm ?: return emptyList()
        if (gear == Gear.UNKNOWN) return emptyList()
        val added = mutableListOf<TripRecord>()
        if (gear != Gear.PARK && open == null && previous != gear) {
            rows.lastOrNull()?.let { last ->
                val gap = odo - last.endOdometerKm
                if (gap >= GAP_THRESHOLD_KM) {
                    val gapRow = TripRecord(
                        id = "GAP-${nowEpochMs}",
                        startEpochMs = last.endEpochMs,
                        endEpochMs = nowEpochMs,
                        startOdometerKm = last.endOdometerKm,
                        endOdometerKm = odo,
                        purpose = TripPurpose.UNLOGGED_GAP,
                        user = null,
                        memo = "앱이 기록하지 못한 주행(비업무로 처리)",
                    )
                    rows += gapRow
                    added += gapRow
                }
            }
            open = OpenTrip(nowEpochMs, odo, routeTollEstimateKrw, destinationName, designatedDriver)
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
                designatedDriver = trip.designatedDriver,
            )
            rows += row
            added += row
        }
        return added
    }

    /** The newest finished trip that still needs a purpose (what a voice command refers to). */
    fun latestUnclassified(): TripRecord? = rows.lastOrNull { it.purpose == TripPurpose.UNCLASSIFIED }

    fun classify(id: String, purpose: TripPurpose, user: CarUser?, memo: String, source: String): TripRecord? {
        require(purpose != TripPurpose.UNCLASSIFIED && purpose != TripPurpose.UNLOGGED_GAP) { "not a user purpose: $purpose" }
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0 || rows[index].purpose == TripPurpose.UNLOGGED_GAP) return null
        val updated = rows[index].copy(purpose = purpose, user = user ?: rows[index].user, memo = memo, classifiedBy = source)
        rows[index] = updated
        return updated
    }

    fun recordsIn(period: ClosedRange<LocalDate>): List<TripRecord> =
        rows.filter { localDate(it.startEpochMs) in period }

    fun summarize(period: ClosedRange<LocalDate>): LogbookSummary = summarize(recordsIn(period))

    companion object {
        /** The Virtual Console odometer moves in 1 km steps; a real car reports finer values. */
        const val GAP_THRESHOLD_KM = 0.5
        private const val CONTINUITY_TOLERANCE_KM = 0.05

        fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(KST).toLocalDate()

        fun summarize(records: List<TripRecord>): LogbookSummary {
            val total = records.sumOf { it.distanceKm }
            val commute = records.filter { it.purpose == TripPurpose.COMMUTE }.sumOf { it.distanceKm }
            val business = records.filter { it.purpose == TripPurpose.BUSINESS }.sumOf { it.distanceKm }
            val breaks = records.zipWithNext().count { (a, b) -> abs(b.startOdometerKm - a.endOdometerKm) > CONTINUITY_TOLERANCE_KM }
            return LogbookSummary(
                rowCount = records.size,
                totalKm = round1(total),
                commuteKm = round1(commute),
                businessKm = round1(business),
                businessUseRatio = if (total > 0) (commute + business) / total else null,
                unclassifiedCount = records.count { it.purpose == TripPurpose.UNCLASSIFIED },
                gapRowCount = records.count { it.purpose == TripPurpose.UNLOGGED_GAP },
                continuityBreaks = breaks,
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
    val businessUseRatio: Double?,
    val unclassifiedCount: Int,
    val gapRowCount: Int,
    val continuityBreaks: Int,
) {
    val businessUseKm: Double get() = TripLogbook.round1(commuteKm + businessKm)
}

/** Filing windows inside a business year that may start in any month (사업연도). */
object TaxPeriods {
    fun businessYear(year: Int, startMonth: Int): ClosedRange<LocalDate> {
        val start = LocalDate.of(year, startMonth, 1)
        return start..start.plusYears(1).minusDays(1)
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
}
