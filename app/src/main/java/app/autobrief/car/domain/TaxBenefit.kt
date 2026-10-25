package app.autobrief.car.domain

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What the logbook changes in deductible car cost for one business year (법인세법 시행령 §50의2, the same
 * rule for 복식부기 sole proprietors in 소득세법 시행령 §78의3):
 *
 * - With a logbook the business-use ratio is ⑫/⑪ from the logbook.
 * - Without one it is 100% when the year's car cost is at most 15,000,000 KRW, otherwise 15,000,000 / cost.
 * - Either way, depreciation counted as business use above 8,000,000 KRW a year is not deductible that
 *   year (it is carried over), so it is subtracted from this year's amount.
 *
 * Full-year holding is assumed; part-year cars, special corporations (부동산임대업 주업 등, 500만원 한도)
 * and leases' insurance/tax splits are out of scope and the screen says so.
 */
object TaxBenefit {
    const val NO_LOGBOOK_CAP_KRW = 15_000_000L
    const val DEPRECIATION_CAP_KRW = 8_000_000L

    data class Case(
        val ratio: Double,
        val businessCostKrw: Long,
        val depreciationCarriedKrw: Long,
        val deductibleKrw: Long,
    )

    data class Result(
        val withLogbook: Case,
        val withoutLogbook: Case,
        /** withLogbook − withoutLogbook; negative when the logbook ratio is below the no-logbook ratio. */
        val extraDeductibleKrw: Long,
        val taxEffectKrw: Long,
        val ratePct: Double,
    )

    fun case(costKrw: Long, depreciationKrw: Long, ratio: Double): Case {
        val r = ratio.coerceIn(0.0, 1.0)
        val business = (costKrw * r).roundToLong()
        val depBusiness = (depreciationKrw.coerceAtMost(costKrw) * r).roundToLong()
        val carried = max(0L, depBusiness - DEPRECIATION_CAP_KRW)
        return Case(r, business, carried, business - carried)
    }

    fun noLogbookRatio(costKrw: Long): Double =
        if (costKrw <= NO_LOGBOOK_CAP_KRW) 1.0 else NO_LOGBOOK_CAP_KRW.toDouble() / costKrw

    /** Null when the owner has not entered the year's car cost or the logbook has no distance yet. */
    fun compute(costKrw: Long, depreciationKrw: Long, logbookRatio: Double?, ratePct: Double): Result? {
        if (costKrw <= 0 || logbookRatio == null) return null
        val with = case(costKrw, depreciationKrw, logbookRatio)
        val without = case(costKrw, depreciationKrw, noLogbookRatio(costKrw))
        val extra = with.deductibleKrw - without.deductibleKrw
        return Result(with, without, extra, (extra * ratePct / 100.0).roundToLong(), ratePct)
    }
}
