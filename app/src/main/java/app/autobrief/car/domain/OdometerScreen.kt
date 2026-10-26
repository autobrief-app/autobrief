package app.autobrief.car.domain

import kotlin.math.abs

/**
 * Which dashboard odometer readings the logbook takes. Not a reading at all: a value that is not finite, is negative,
 * or is 0 while a distance is already known (the SDK's value before the car's own arrives). A step back, or a step of
 * more than [JUMP_KM], is taken only when the next reading agrees with it within [AGREE_KM] (the Pleos SDK repeats
 * the value every few seconds); until then the last value stands. Steps are measured from the last value taken; until
 * the logbook took one itself (after a restart or a space switch) its last row's end counts as a true value as well.
 */
class OdometerScreen {
    private var doubtfulKm: Double? = null
    private var readingTaken = false

    /** A reading is held for confirmation: a departure now waits for the next value taken. */
    val inDoubt: Boolean get() = doubtfulKm != null

    /** [references] = the last value taken (or seeded) and, until a reading was taken, the logbook's own end values. */
    fun references(latest: Double?, logbookEnds: List<Double>): List<Double> =
        if (readingTaken) listOfNotNull(latest) else listOfNotNull(latest) + logbookEnds

    /** A value that can never be a reading, whatever came before. */
    fun impossible(km: Double, references: List<Double>): Boolean =
        !km.isFinite() || km < 0.0 || (km == 0.0 && references.any { it > 0.0 })

    /** The values to take for one reading, in order: none, the reading, or the held value and the reading confirming it. */
    fun screen(km: Double, latest: Double?, references: List<Double>): List<Double> {
        if (impossible(km, references)) return emptyList()
        if (references.isEmpty()) return listOf(km)
        if (references.any { km - it in -JITTER_KM..JUMP_KM }) {
            doubtfulKm = null
            // a few metres below the last value is the same dashboard reading (Float steps), not a step back
            return if (latest != null && km < latest && km >= latest - JITTER_KM) emptyList() else listOf(km)
        }
        val doubtful = doubtfulKm
        doubtfulKm = km
        if (doubtful == null || abs(km - doubtful) > AGREE_KM) return emptyList()
        doubtfulKm = null
        return if (km > doubtful) listOf(doubtful, km) else listOf(km)
    }

    fun taken() {
        readingTaken = true
    }

    companion object {
        /** More than an hour of motorway driving between two readings: such a step needs a second reading. */
        const val JUMP_KM = 100.0

        /** Two readings this close confirm each other (the car may move a little between them). */
        const val AGREE_KM = 1.0

        /** A reading this little below the last one is the same dashboard value. */
        const val JITTER_KM = 0.05
    }
}
