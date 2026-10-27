package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The FICTIONAL sample every document quotes, built by the app itself: the demo space's sample history
 * up to 2026-10-22, then the two drives of 2026-10-23 replayed as gear/odometer/navigation events
 * through the real core (one labelled by voice, one left unclassified).
 */
object DocsSample {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 23)

    /** [webhook] set = the company address is configured, so every ledger entry is also queued for the fleet console. */
    fun build(webhook: String = ""): Harness {
        val h = Harness(startAt = Harness.at(TODAY, 9, 0))
        check(h.core.setDemoModeNow(true, null) == AutoBriefCore.SettingsResult.SAVED)
        h.core.loadSampleHistoryNow(webhook)
        // 10:20 → 10:58, 45,200 → 45,218 km to a client, labelled by voice right after parking
        h.now = Harness.at(TODAY, 10, 20)
        h.core.onNavi(NaviEvent.RouteStarted("가상고객사 판교 사옥", 1_800))
        h.odo(45_200.0); h.gear(Gear.PARK); h.gear(Gear.DRIVE)
        h.now = Harness.at(TODAY, 10, 58)
        h.odo(45_218.0); h.gear(Gear.PARK)
        val voice = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"BUSINESS","memo":"가상고객사 미팅"}"""))
        check(voice.status == "success") { voice.message }
        h.core.onNavi(NaviEvent.RouteEnded)
        // 22:40 → 23:06, 11 km, purpose not given yet
        h.now = Harness.at(TODAY, 22, 40)
        h.gear(Gear.DRIVE)
        h.now = Harness.at(TODAY, 23, 6)
        h.odo(45_229.0); h.gear(Gear.PARK)
        return h
    }
}

class SampleScenarioTest {

    @Test fun historyMatchesWhatTheEngineWritesFromEvents() {
        // replay the plan as gear/odometer events through a fresh core and compare row by row
        val end = LocalDate.of(2026, 4, 1)
        val (rows, _) = SampleHistory.build(end)
        val plan = SampleHistory.plan(end)
        val h = Harness(SampleHistory.settings(), startAt = plan.first().start - 3_600_000)
        var odo = rows.first().startOdometerKm
        val gapBefore = plan.indexOfFirst { it.start > SampleHistory.at(SampleHistory.GAP_DAY, 0, 0) }
        plan.forEachIndexed { i, p ->
            if (i == gapBefore) odo += SampleHistory.GAP_KM
            h.now = p.start; h.odo(odo); h.gear(Gear.DRIVE)
            odo += p.km
            h.now = p.end; h.odo(odo); h.gear(Gear.PARK)
            val id = h.core.trips.value.last().id
            h.core.classifyTrip(id, p.purpose, p.user, p.memo, ClassifiedBy.SCREEN)
        }
        val replayed = h.core.trips.value
        assertEquals(rows.size, replayed.size)
        rows.zip(replayed).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.startOdometerKm, b.startOdometerKm, 0.0)
            assertEquals(a.endOdometerKm, b.endOdometerKm, 0.0)
            assertEquals(a.startEpochMs, b.startEpochMs)
            assertEquals(a.endEpochMs, b.endEpochMs)
            assertEquals(a.purpose, b.purpose)
            assertEquals(a.user, b.user)
        }
    }

    /**
     * The exact webhook bodies the app queues for the company endpoint (read by the fleet console check), from the same
     * state the documents quote: the fleet console screenshots must show the docs' verification code and totals.
     */
    @Test fun exportLedgerPayloadsForTheFleetConsole() {
        val h = DocsSample.build(webhook = "http://10.0.2.2:8787/hook")
        val items = h.core.outbox.value
        assertEquals(h.core.integrity.value.entryCount, items.count { it.kind == "ledger" })
        assertEquals(DocsSample.build().core.integrity.value.code, h.core.integrity.value.code)
        val dir = File("build/sample").apply { mkdirs() }
        File(dir, "outbox_payloads.jsonl").writeText(items.joinToString("\n") { it.key + "\t" + h.core.wireBody(it) } + "\n")
        val year = h.core.summary(TaxPeriods.businessYear(2026, 1))
        File(dir, "outbox_expected.json").writeText(
            buildJsonObject {
                put("entries", h.core.integrity.value.entryCount)
                put("code", h.core.integrity.value.code)
                put("year_total_km", year.totalKm)
                put("year_business_use_km", year.businessUseKm)
                put("year_ratio_pct", "%.1f".format(year.businessUseRatio!! * 100))
            }.toString(),
        )
    }

    @Test fun buildFictionalSampleAndPinEveryQuotedNumber() {
        val h = DocsSample.build()
        val core = h.core
        val year = TaxPeriods.businessYear(2026, 1)
        val october = TaxPeriods.month(2026, 10)
        val ys = core.summary(year)
        val ms = core.summary(october)
        val check = core.integrity.value
        val today = core.trips.value.filter { TripLogbook.localDate(it.startEpochMs) == DocsSample.TODAY }

        // the numbers the v1.6 documents and the short-form already quote
        assertEquals(487, ys.rowCount)
        assertEquals(9_636.0, ys.totalKm, 0.0)
        assertEquals(9_117.0, ys.businessUseKm, 0.0)
        assertEquals("94.6", "%.1f".format(ys.businessUseRatio!! * 100))
        assertEquals(1, ys.gapRowCount)
        assertEquals(36, ms.rowCount)
        assertEquals(699.0, ms.totalKm, 0.0)
        assertEquals("93.0", "%.1f".format(ms.businessUseRatio!! * 100))
        assertEquals(35_593.0, core.trips.value.first().startOdometerKm, 0.0)
        assertTrue(check.problems.toString(), check.ok)
        assertEquals(2, today.size)
        assertEquals(TripPurpose.BUSINESS, today[0].purpose)
        assertEquals(ClassifiedBy.VOICE, today[0].classifiedBy)
        assertEquals(1_800, today[0].routeTollEstimateKrw)
        assertEquals(TripPurpose.UNCLASSIFIED, today[1].purpose)
        val tax = core.taxBenefit()!! // the clock is still 2026-10-23: business year 2026
        assertEquals(19_000_000, h.core.settings.value.annualCarCostKrw)
        assertEquals(22.0, tax.ratePct, 0.0) // a business year starting 2026-01-01 takes the 2026 table

        val dir = File("build/sample").apply { mkdirs() }
        val yearBook = core.exportNow(year, businessYear = true, send = false)
        val monthBook = core.exportNow(october, businessYear = false, send = false)
        File(dir, "운행기록부_2026_사업연도_가상예시.xlsx").writeBytes(h.files.getValue("demo/${yearBook.fileName}"))
        File(dir, "운행기록부_2026_사업연도_가상예시.csv").writeBytes(h.files.getValue("demo/${yearBook.fileName.removeSuffix(".xlsx")}.csv"))
        File(dir, "운행기록부_2026-10_가상예시.xlsx").writeBytes(h.files.getValue("demo/${monthBook.fileName}"))
        val summary = buildJsonObject {
            put("label", "가상 예시 데이터: 실제 차량, 법인, 인물이 아닙니다 (오토브리프 ${AutoBriefCore.APP_VERSION} 기록 엔진으로 생성)")
            put("year_rows", ys.rowCount); put("year_total_km", ys.totalKm); put("year_business_use_km", ys.businessUseKm)
            put("year_commute_km", ys.commuteKm); put("year_business_km", ys.businessKm); put("year_personal_km", ys.personalKm)
            put("year_ratio_pct", "%.1f".format(ys.businessUseRatio!! * 100)); put("year_gap_rows", ys.gapRowCount)
            put("year_auto_classified", ys.autoCount); put("year_voice_classified", ys.voiceCount); put("year_unclassified", ys.unclassifiedCount)
            put("year_first_odometer_km", core.trips.value.first().startOdometerKm); put("year_first_date", "2026-01-02")
            put("october_rows", ms.rowCount); put("october_total_km", ms.totalKm)
            put("october_ratio_pct", "%.1f".format(ms.businessUseRatio!! * 100))
            put("today_start_km", today[0].startOdometerKm); put("today_end_km", today[0].endOdometerKm)
            put("today_distance_km", today[0].distanceKm); put("today_toll_estimate_krw", today[0].routeTollEstimateKrw ?: -1)
            put("today_purpose", today[0].purpose.label); put("today_classified_by", today[0].classifiedBy ?: "")
            put("ledger_entries", check.entryCount); put("ledger_code", check.code)
            put("tax_annual_cost_krw", core.settings.value.annualCarCostKrw); put("tax_depreciation_krw", core.settings.value.annualDepreciationKrw)
            put("tax_rate_pct", tax.ratePct)
            put("tax_without_logbook_deductible_krw", tax.withoutLogbook.deductibleKrw); put("tax_without_logbook_ratio_pct", "%.1f".format(tax.withoutLogbook.ratio * 100))
            put("tax_with_logbook_deductible_krw", tax.withLogbook.deductibleKrw); put("tax_with_logbook_ratio_pct", "%.1f".format(tax.withLogbook.ratio * 100))
            put("tax_extra_deductible_krw", tax.extraDeductibleKrw); put("tax_effect_krw", tax.taxEffectKrw)
            put("year_rows_in_xlsx", yearBook.rows); put("october_rows_in_xlsx", monthBook.rows)
            put("sample_example_row", LogbookExport.dateWithWeekday(TripLogbook.localDate(today[0].startEpochMs)))
        }
        File(dir, "sample_summary.json").writeText(summary.toString())
        assertEquals(487, yearBook.rows)
    }
}
