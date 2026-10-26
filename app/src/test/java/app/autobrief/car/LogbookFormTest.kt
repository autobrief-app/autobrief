package app.autobrief.car

import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun aBusinessYearStillRunningIsLabelledAsTheTotalToDate() {
        val running = "2026-10-26 현재 누계(사업연도 진행 중)"
        val sheet = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = LocalDate.of(2026, 10, 26))
        assertTrue(sheet.headerLines.toString(), sheet.headerLines.any { it.startsWith("사업연도: 2026-01-01 ~ 2026-12-31, $running    법인명") })
        assertEquals("⑪$running 총주행 거리(㎞)", sheet.totals.totalLabel)
        assertEquals("⑫$running 업무용 사용거리(㎞)", sheet.totals.businessUseLabel)
        // with no export date the end of the latest record stands in for it
        val asOfLastRow = LogbookExport.build(chainOf1004(), settings, year2026, "CODE")
        assertEquals("⑪2026-03-02 현재 누계(사업연도 진행 중) 총주행 거리(㎞)", asOfLastRow.totals.totalLabel)
    }

    @Test fun aFinishedBusinessYearKeepsThePlainLabelsOfTheForm() {
        val exportedLater = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = LocalDate.of(2027, 1, 5))
        assertTrue(exportedLater.headerLines.any { it.startsWith("사업연도: 2026-01-01 ~ 2026-12-31    법인명") })
        assertEquals("⑪사업연도 총주행 거리(㎞)", exportedLater.totals.totalLabel)
        assertEquals("⑫사업연도 업무용 사용거리(㎞)", exportedLater.totals.businessUseLabel)
        // with no export date, a record after the year's end shows the year is over
        val next = chainOf1004() + trip("N", LocalDate.of(2027, 1, 4), 9, 130.12, 140.0, TripPurpose.BUSINESS)
        val byRecords = LogbookExport.build(next, settings, year2026, "CODE")
        assertEquals("⑪사업연도 총주행 거리(㎞)", byRecords.totals.totalLabel)
        assertFalse(byRecords.headerLines.any { "진행 중" in it })
        assertEquals(3, byRecords.rows.size)
    }

    @Test fun aPeriodExtractSaysSoAndPointsTheTaxRatioAtTheBusinessYear() {
        val march = TaxPeriods.month(2026, 3)
        val sheet = LogbookExport.build(chainOf1004(), settings, march, "CODE", businessYear = false)
        assertEquals("업무용승용차 운행기록부 (기간 발췌)", sheet.title)
        assertTrue(sheet.headerLines.toString(), sheet.headerLines.any { it.startsWith("사업연도: 2026-01-01 ~ 2026-12-31    법인명(상호): 가상예시 주식회사") })
        assertTrue(sheet.headerLines.toString(), "발췌 기간: 2026-03-01 ~ 2026-03-31" in sheet.headerLines)
        assertEquals("⑪발췌 기간 총주행 거리(㎞)", sheet.totals.totalLabel)
        assertTrue(sheet.note, "세무 신고에 쓰는 ⑬업무사용비율은 이 발췌 기간이 아니라 사업연도 전체의 ⑫/⑪입니다." in sheet.note)
        // a business year that starts in July: the extract names that year
        val july = LogbookExport.build(chainOf1004(), settings.copy(fiscalYearStartMonth = 7), march, "CODE", businessYear = false)
        assertTrue(july.headerLines.toString(), july.headerLines.any { it.startsWith("사업연도: 2025-07-01 ~ 2026-06-30    법인명") })
        // the business-year file keeps the plain title and has no extract line or note
        val year = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = LocalDate.of(2027, 1, 5))
        assertEquals("업무용승용차 운행기록부", year.title)
        assertFalse(year.headerLines.any { it.startsWith("발췌 기간") })
        assertFalse("발췌" in year.note)
    }
}
