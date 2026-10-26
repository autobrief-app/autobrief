package app.autobrief.car

import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The NTS form on small hand-made logbooks: real-car decimals, labels, layout. */
class LogbookFormTest {
    private val user = CarUser("김예시", "영업팀")
    private val settings = AppSettings(
        entityName = "가상예시 주식회사",
        businessNumber = "000-00-00000",
        vehicleModel = "가상 예시 차량",
        plateNumber = "00가 0000",
    )
    private val year2026 = TaxPeriods.businessYear(2026, 1)
    private val march2 = LocalDate.of(2026, 3, 2)

    private fun trip(id: String, day: LocalDate, hour: Int, start: Double, end: Double, purpose: TripPurpose) =
        TripRecord(id, Harness.at(day, hour, 0), Harness.at(day, hour, 30), start, end, purpose, user, "")

    /** Three trips of 10.04 km in one chain: 100.00 → 110.04 → 120.08 → 130.12. */
    private fun chainOf1004() = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)
        .mapIndexed { i, p -> trip("T$i", march2, 8 + i, 100.0 + i * 10.04, 100.0 + (i + 1) * 10.04, p) }

    @Test fun eachOdometerValueIsRoundedOnceAndSevenIsSixMinusFive() {
        val row = LogbookExport.build(listOf(trip("T1", march2, 9, 100.04, 110.06, TripPurpose.BUSINESS)), settings, year2026, "CODE")
            .rows.single()
        assertEquals(100.0, row[3])
        assertEquals(110.1, row[4])
        assertEquals(10.1, row[5]) // printed ⑥ − printed ⑤, not the raw 10.02 rounded to 10.0
        assertEquals(null, row[6])
        assertEquals(10.1, row[7])
    }

    @Test fun elevenAndTwelveAreTheSumsOfThePrintedCells() {
        val sheet = LogbookExport.build(chainOf1004(), settings, year2026, "CODE")
        val sevens = sheet.rows.map { it[5] as Double }
        assertEquals(listOf(10.0, 10.1, 10.0), sevens)
        sheet.rows.forEach { assertEquals(TripLogbook.round1(it[4] as Double - it[3] as Double), it[5]) }
        val businessCells = sheet.rows.sumOf { ((it[6] as Double?) ?: 0.0) + ((it[7] as Double?) ?: 0.0) }
        assertEquals(30.1, sheet.totals.totalKm, 0.0)
        assertEquals(TripLogbook.round1(sevens.sum()), sheet.totals.totalKm, 0.0)
        assertEquals(20.1, sheet.totals.businessUseKm, 0.0) // ⑧ 10.0 + ⑨ 10.1
        assertEquals(TripLogbook.round1(businessCells), sheet.totals.businessUseKm, 0.0)
        assertEquals(20.1 / 30.1, sheet.totals.ratio!!, 1e-12)
        // three separate 10.04 km trips print 10.0 each, so ⑪ = 30.0
        val separate = listOf(100.0, 200.0, 300.0).mapIndexed { i, s -> trip("S$i", march2, 8 + i, s, s + 10.04, TripPurpose.BUSINESS) }
        val s = LogbookExport.build(separate, settings, year2026, "CODE")
        assertEquals(listOf(10.0, 10.0, 10.0), s.rows.map { it[5] })
        assertEquals(30.0, s.totals.totalKm, 0.0)
        assertEquals(30.0, s.totals.businessUseKm, 0.0)
    }
}
