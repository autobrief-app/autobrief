package app.autobrief.car.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/**
 * 유류비 정산 for an employee's own car used for work: instead of a flat rate per km, the business distance of a calendar
 * month at the car's own average fuel economy and the fuel price the owner entered.
 *
 * liters = business km × L/100km ÷ 100, amount = liters × price rounded half-up to 10 KRW. The economy is taken to two
 * decimals (L/100km), as the settlement sheet prints it, so every printed row reconciles. Nothing is assumed: without a
 * usable economy or price there is no amount, and [Result.missing] says which one is missing.
 */
object FuelReimbursement {
    /** A usable economy is above 0 and below this (L/100 km); anything else the car reports is no reading. */
    const val MAX_LITERS_PER_100KM = 50.0

    enum class Missing { ECONOMY, PRICE }

    /** Where the economy came from, as the card and the sheet name it. */
    enum class Source(val label: String) {
        VEHICLE("차량 누적 연비"),
        MANUAL("직접 입력"),
    }

    /** The economy a settlement uses (L/100 km) and its source. */
    data class Economy(val litersPer100Km: Double, val source: Source)

    data class Result(
        val businessKm: Double,
        /** The economy used, to two decimals; null when there was no usable one. */
        val litersPer100Km: Double?,
        val priceKrwPerLiter: Int?,
        val liters: Double?,
        val amountKrw: Long?,
        val missing: Missing?,
    )

    /** One calendar month's settlement as the card, the sheet and the payload show it. */
    data class Month(val month: YearMonth, val economySource: Source?, val result: Result)

    fun validEconomy(litersPer100Km: Double?): Boolean =
        litersPer100Km != null && litersPer100Km.isFinite() && litersPer100Km > 0 && litersPer100Km < MAX_LITERS_PER_100KM

    /** An economy entered in km/L as L/100 km; null when it is no usable figure. */
    fun litersPer100Km(kmPerLiter: Double): Double? =
        if (kmPerLiter.isFinite() && kmPerLiter > 0) (100.0 / kmPerLiter).takeIf(::validEconomy) else null

    fun kmPerLiter(litersPer100Km: Double): Double = 100.0 / litersPer100Km

    fun compute(businessKm: Double, litersPer100Km: Double?, priceKrwPerLiter: Int?): Result {
        val km = if (businessKm.isFinite() && businessKm > 0) businessKm else 0.0
        val price = priceKrwPerLiter?.takeIf { it > 0 }
        val economy = litersPer100Km?.takeIf(::validEconomy)?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
            ?: return Result(km, null, price, null, null, Missing.ECONOMY)
        val liters = BigDecimal.valueOf(km).multiply(economy).movePointLeft(2)
        if (price == null) return Result(km, economy.toDouble(), null, liters.toDouble(), null, Missing.PRICE)
        val amount = liters.multiply(BigDecimal.valueOf(price.toLong())).setScale(-1, RoundingMode.HALF_UP).toBigInteger().toLong()
        return Result(km, economy.toDouble(), price, liters.toDouble(), amount, null)
    }
}
