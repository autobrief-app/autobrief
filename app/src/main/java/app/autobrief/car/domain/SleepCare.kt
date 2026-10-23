package app.autobrief.car.domain

import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.SeatState

/**
 * Mode 2 detector: a passenger counts as asleep after sitting reclined, with the reading light off,
 * for [holdMs]. Inputs are Pleos Seat (occupancy, belt, backrest position + capability bounds) and
 * Light (reading-light state) values.
 *
 * What the app does with it is limited to what a third-party app may do: a quiet on-screen hint to the
 * driver (rear sleep mode / volume), Gleo replies switched to a beep, and — only where the vehicle allows
 * `Light.setAmbientLightBrightness` — dimmer ambient light. Per-seat speaker routing is not exposed to
 * third-party apps (AAOS audio settings are signature|privileged), so it is not attempted.
 */
class SleepCareDetector(
    private val holdMs: Long = 60_000,
    private val sleepReclineRatio: Double = 0.6,
    private val wakeReclineRatio: Double = 0.4,
) {
    private val reclinedSince = mutableMapOf<SeatPos, Long>()
    private val asleep = mutableSetOf<SeatPos>()

    fun update(seats: Map<SeatPos, SeatState>, nowEpochMs: Long, enabled: Boolean = true): SleepCareState {
        if (!enabled) {
            reclinedSince.clear()
            asleep.clear()
            return SleepCareState(emptySet(), emptyMap())
        }
        for (pos in SeatPos.values()) {
            val s = seats[pos]
            val ratio = s?.reclineRatio
            val candidate = s != null && s.occupied == true && s.readingLightOn != true &&
                s.belted != false && ratio != null && ratio >= sleepReclineRatio
            val awake = s == null || s.occupied != true || s.readingLightOn == true ||
                ratio == null || ratio < wakeReclineRatio
            when {
                pos in asleep && awake -> {
                    asleep -= pos
                    reclinedSince -= pos
                }
                pos !in asleep && candidate -> {
                    val since = reclinedSince.getOrPut(pos) { nowEpochMs }
                    if (nowEpochMs - since >= holdMs) asleep += pos
                }
                pos !in asleep -> reclinedSince -= pos
            }
        }
        return SleepCareState(asleep.toSet(), reclinedSince.filterKeys { it !in asleep })
    }
}

data class SleepCareState(
    val sleeping: Set<SeatPos>,
    /** Seats reclined but not yet past the hold time, with the time they started. */
    val pending: Map<SeatPos, Long>,
) {
    /** Gleo replies use a beep instead of speech while someone sleeps. */
    val quietMode: Boolean get() = sleeping.isNotEmpty()

    val driverHint: String?
        get() = when {
            sleeping.isEmpty() -> null
            sleeping.any { it != SeatPos.ROW1_RIGHT } && SeatPos.ROW1_RIGHT !in sleeping ->
                "${names()} 동승자가 잠든 것 같습니다. 순정 '후석 취침 모드'를 켜면 뒷좌석 스피커가 꺼집니다."
            else ->
                "${names()} 동승자가 잠든 것 같습니다. 음량을 낮추거나 음향 설정의 음위치를 운전석 쪽으로 옮겨 보세요."
        }

    private fun names() = sleeping.sortedBy { it.ordinal }.joinToString(", ") { it.label }
}
