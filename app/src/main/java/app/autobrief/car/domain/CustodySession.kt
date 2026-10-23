package app.autobrief.car.domain

import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.CustodyReport
import app.autobrief.car.model.Gear
import kotlin.math.max

/**
 * Risky-driving thresholds in km/h per second. Defaults follow the taxi (passenger car) rows of the
 * Korea Transportation Safety Authority eTAS risky-driving table, the closest published standard for a
 * passenger car; they are settings, not constants of nature.
 */
data class DrivingThresholds(
    /** Acceleration threshold by the speed it starts from: ≤10, ≤20, above 20 km/h. */
    val accelLow: Double = 12.0,
    val accelMid: Double = 10.0,
    val accelHigh: Double = 8.0,
    /** Deceleration threshold by the speed it ends at: ≤30 km/h, above 30 km/h. */
    val brakeLow: Double = 14.0,
    val brakeHigh: Double = 15.0,
    /** Over the posted limit by this much counts as speeding (eTAS: +20 km/h). */
    val overspeedMarginKph: Double = 20.0,
    /** A repeat within this window belongs to the same event. */
    val eventMergeMs: Long = 3_000,
)

/**
 * Mode 3: one designated-driver (대리운전) or valet session.
 *
 * Starts only after the driver agrees on screen (consent covers sharing the car's trip with the owner's
 * contacts). Counts events, not samples: a hard acceleration lasting two seconds is one event. Ends on
 * arrival (gear P within [arrivalRadiusM] of the navigation destination, or NaviHelper's arrival event),
 * or when the owner ends it early with the PIN — which reports an early end, never a safe arrival.
 */
class CustodySession(
    val sessionId: String,
    private val startEpochMs: Long,
    private val startOdometerKm: Double?,
    val destinationLabel: String?,
    val driverConsented: Boolean,
    private val thresholds: DrivingThresholds = DrivingThresholds(),
    private val arrivalRadiusM: Int = 300,
) {
    private var lastSpeedKph: Double? = null
    private var lastSpeedMs: Long = 0
    private var lastAccelEventMs = Long.MIN_VALUE / 2
    private var lastBrakeEventMs = Long.MIN_VALUE / 2
    private var speeding = false
    private var speedLimitKph: Int? = null
    private var arrivedSignal = false
    private var lastGear = Gear.UNKNOWN

    var maxSpeedKph = 0.0
        private set
    var harshAccelEvents = 0
        private set
    var harshBrakeEvents = 0
        private set
    var overspeedEpisodes = 0
        private set
    var intermediateStops = 0
        private set

    fun onSpeedLimit(limitKph: Int) {
        speedLimitKph = limitKph.takeIf { it > 0 }
    }

    fun onArrivedSignal() {
        arrivedSignal = true
    }

    fun onSpeed(kph: Double, nowEpochMs: Long) {
        maxSpeedKph = max(maxSpeedKph, kph)
        val prev = lastSpeedKph
        val dtMs = nowEpochMs - lastSpeedMs
        if (prev != null && dtMs in 500..3_000) {
            val rate = (kph - prev) / (dtMs / 1000.0) // km/h per second
            // a burst that keeps qualifying within eventMergeMs of its previous sample is one event
            if (prev >= 6.0 && rate >= accelThreshold(prev)) {
                if (nowEpochMs - lastAccelEventMs > thresholds.eventMergeMs) harshAccelEvents++
                lastAccelEventMs = nowEpochMs
            }
            // a hard stop (ending at or below 5 km/h) counts as a hard brake too
            if (-rate >= brakeThreshold(kph)) {
                if (nowEpochMs - lastBrakeEventMs > thresholds.eventMergeMs) harshBrakeEvents++
                lastBrakeEventMs = nowEpochMs
            }
        }
        if (prev == null || dtMs >= 500) {
            lastSpeedKph = kph
            lastSpeedMs = nowEpochMs
        }
        val limit = speedLimitKph
        if (limit != null) {
            val over = kph > limit + thresholds.overspeedMarginKph
            if (over && !speeding) overspeedEpisodes++
            speeding = over || (speeding && kph > limit)
        }
    }

    private fun accelThreshold(fromKph: Double) = when {
        fromKph <= 10 -> thresholds.accelLow
        fromKph <= 20 -> thresholds.accelMid
        else -> thresholds.accelHigh
    }

    private fun brakeThreshold(toKph: Double) = if (toKph <= 30) thresholds.brakeLow else thresholds.brakeHigh

    /**
     * Gear change while the session runs. Returns the finished report on arrival, null otherwise
     * (a stop away from the destination is counted, not treated as arrival).
     */
    fun onGear(gear: Gear, nowEpochMs: Long, odometerKm: Double?, remainingDistanceM: Int?, doorsLocked: Boolean?): CustodyReport? {
        val parkedNow = gear == Gear.PARK && lastGear != Gear.PARK
        lastGear = gear
        if (!parkedNow) return null
        val nearDestination = remainingDistanceM != null && remainingDistanceM <= arrivalRadiusM
        if (arrivedSignal || nearDestination) {
            return report(nowEpochMs, odometerKm, CustodyEndReason.ARRIVED, doorsLocked)
        }
        intermediateStops++
        return null
    }

    fun endEarly(nowEpochMs: Long, odometerKm: Double?, doorsLocked: Boolean?): CustodyReport =
        report(nowEpochMs, odometerKm, CustodyEndReason.OWNER_ENDED_EARLY, doorsLocked)

    private fun report(nowEpochMs: Long, odometerKm: Double?, reason: CustodyEndReason, doorsLocked: Boolean?) = CustodyReport(
        sessionId = sessionId,
        startEpochMs = startEpochMs,
        endEpochMs = nowEpochMs,
        startOdometerKm = startOdometerKm,
        endOdometerKm = odometerKm,
        destinationLabel = destinationLabel,
        maxSpeedKph = maxSpeedKph,
        overspeedEpisodes = overspeedEpisodes,
        harshAccelEvents = harshAccelEvents,
        harshBrakeEvents = harshBrakeEvents,
        endReason = reason,
        doorsLocked = doorsLocked,
        driverConsented = driverConsented,
    )
}

/** Texts the owner's contacts receive. An early end never claims a safe arrival. */
object CustodyMessages {
    fun departure(destination: String?, etaText: String?): String = buildString {
        append("[오토브리프] 대리운전이 시작되었습니다.")
        destination?.let { append(" 목적지: $it.") }
        etaText?.let { append(" 도착 예정: $it.") }
    }

    fun end(report: CustodyReport, timeText: String): String = when (report.endReason) {
        CustodyEndReason.ARRIVED -> buildString {
            append("[오토브리프] $timeText 목적지에 도착해 주차했습니다.")
            report.distanceKm?.let { append(" 주행 ${"%.0f".format(it)}km.") }
            append(" 과속 ${report.overspeedEpisodes}회, 급가속 ${report.harshAccelEvents}회, 급감속 ${report.harshBrakeEvents}회.")
            when (report.doorsLocked) {
                true -> append(" 문 잠김 확인.")
                false -> append(" 문이 잠기지 않았습니다.")
                null -> {}
            }
        }
        CustodyEndReason.OWNER_ENDED_EARLY ->
            "[오토브리프] $timeText 차주가 대리운전 모드를 도착 전에 종료했습니다."
    }
}

/**
 * Masks what THIS app shows (it cannot change the built-in navigation screen): apartment
 * building/unit numbers and private bookmark aliases. Administrative districts such as 역삼1동,
 * subway lines such as 2호선 and branch names such as 3호점 are left alone.
 */
object DisplayMasking {
    private val buildingUnit = Regex("""\d{1,4}\s*동\s*\d{1,5}\s*호(?![선점실])""")
    private val unitAfterComplex = Regex("""(?<=\s)\d{3,5}\s*호(?![선점실])""")
    private val alias = Regex("""^(우리집|내 집|집|자택)\s*[:：]?\s*""")

    fun mask(address: String): String = address
        .replace(alias, "")
        .replace(buildingUnit, "동·호수 비공개")
        .replace(unitAfterComplex, "호수 비공개")
        .trim()
}
