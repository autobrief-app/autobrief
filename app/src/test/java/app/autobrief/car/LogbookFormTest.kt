package app.autobrief.car

import app.autobrief.car.domain.Ledger
import app.autobrief.car.domain.LedgerKind
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
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** The NTS form on small hand-made logbooks: real-car decimals, labels, layout. */
class LogbookFormTest {
    private val user = CarUser("김기현", "영업팀")
    private val settings = AppSettings(
        entityName = "한성물산 주식회사",
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
        assertTrue(sheet.headerLines.toString(), sheet.headerLines.any { it.startsWith("사업연도: 2026-01-01 ~ 2026-12-31    법인명(상호): 한성물산 주식회사") })
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

    @Test fun aGapWithinOneDayIsPlainAndTheNoteCountsContinuityBreaks() {
        val first = trip("T1", march2, 8, 100.0, 110.0, TripPurpose.COMMUTE)
        val gap = TripRecord("G1", first.endEpochMs, Harness.at(march2, 12, 0), 110.0, 110.3, TripPurpose.UNLOGGED_GAP, null, TripLogbook.GAP_MEMO)
        val second = trip("T2", march2, 12, 110.3, 120.0, TripPurpose.BUSINESS)
        val third = trip("T3", march2, 15, 120.4, 130.0, TripPurpose.BUSINESS) // 0.4 km missing before it: a break
        val sheet = LogbookExport.build(listOf(first, gap, second, third), settings, year2026, "CODE")
        assertEquals("미기록 구간", sheet.rows[1][8])
        assertTrue(sheet.note, "계기판 연속성 끊김 1곳" in sheet.note)
    }

    // ------------------------------------------------------------------ the workbook as written

    private fun parts(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                put(e.name, z.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    private fun dom(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    private fun Document.elements(tag: String): List<Element> = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    /** Text (inline string or cached value), formula and style of one cell. */
    private data class Cell(val text: String?, val formula: String?, val style: Int)

    private fun cells(sheetXml: String): Map<String, Cell> = dom(sheetXml).elements("c").associate { c ->
        fun child(tag: String) = c.getElementsByTagName(tag).item(0)?.textContent
        c.getAttribute("r") to Cell(child("t") ?: child("v"), child("f"), c.getAttribute("s").toInt())
    }

    private fun merges(sheetXml: String): Set<String> = dom(sheetXml).elements("mergeCell").map { it.getAttribute("ref") }.toSet()

    /** The number format a cellXfs entry applies. */
    private fun formatOf(stylesXml: String, xf: Int): String {
        val d = dom(stylesXml)
        val custom = d.elements("numFmt").associate { it.getAttribute("numFmtId").toInt() to it.getAttribute("formatCode") }
        val id = (d.getElementsByTagName("cellXfs").item(0) as Element).getElementsByTagName("xf").item(xf).let { (it as Element).getAttribute("numFmtId").toInt() }
        return custom[id] ?: mapOf(0 to "General", 3 to "#,##0")[id] ?: "built-in $id"
    }

    private val finished = LocalDate.of(2027, 1, 5)

    @Test fun columnHeaderNestsLikeTheFormAndRepeatsOnEveryPage() {
        val sheet = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = finished)
        val wb = parts(LogbookExport.toXlsx(sheet))
        val s1 = wb.getValue("xl/worksheets/sheet1.xml")
        val c = cells(s1)
        val h = sheet.headerLines.size + 2 // title, header lines, then the column header
        assertEquals("③사용일자(요일)", c.getValue("A$h").text)
        assertEquals("④사용자 부서", c.getValue("B$h").text)
        assertEquals("운 행 내 역", c.getValue("D$h").text)
        assertEquals("⑤주행 전 계기판의 거리(㎞)", c.getValue("D${h + 1}").text)
        assertEquals("⑦주행거리(㎞)", c.getValue("F${h + 1}").text)
        assertEquals("업무용 사용거리(㎞)", c.getValue("G${h + 1}").text)
        assertEquals("⑩비고", c.getValue("I${h + 1}").text)
        assertEquals("⑧출·퇴근용(㎞)", c.getValue("G${h + 2}").text)
        assertEquals("⑨일반 업무용(㎞)", c.getValue("H${h + 2}").text)
        val m = merges(s1)
        listOf("A$h:A${h + 2}", "B$h:B${h + 2}", "C$h:C${h + 2}", "D$h:I$h", "D${h + 1}:D${h + 2}", "E${h + 1}:E${h + 2}",
            "F${h + 1}:F${h + 2}", "G${h + 1}:H${h + 1}", "I${h + 1}:I${h + 2}").forEach { assertTrue("$it in $m", it in m) }
        assertTrue(wb.getValue("xl/workbook.xml"), "localSheetId=\"0\">'업무용승용차 운행기록부'!\$$h:\$${h + 2}</definedName>" in wb.getValue("xl/workbook.xml"))
        assertTrue("ySplit=\"${h + 2}\"" in s1)
        assertEquals("2026-03-02(월)", c.getValue("A${h + 3}").text)
    }

    @Test fun totalsSitUnderTheirColumnsAndAddUpThePrintedCells() {
        val sheet = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = finished)
        val wb = parts(LogbookExport.toXlsx(sheet))
        val s1 = wb.getValue("xl/worksheets/sheet1.xml")
        val c = cells(s1)
        val first = sheet.headerLines.size + 5
        val last = first + sheet.rows.size - 1
        val label = last + 1
        val value = last + 2
        assertEquals("⑪사업연도 총주행 거리(㎞)", c.getValue("D$label").text)
        assertEquals("⑫사업연도 업무용 사용거리(㎞)", c.getValue("G$label").text)
        assertEquals("⑬업무사용비율(⑫/⑪)", c.getValue("I$label").text)
        val m = merges(s1)
        listOf("A$label:A$value", "B$label:C$value", "D$label:F$label", "G$label:H$label", "D$value:F$value", "G$value:H$value")
            .forEach { assertTrue("$it in $m", it in m) }
        assertEquals("SUM(F$first:F$last)", c.getValue("D$value").formula)
        assertEquals("SUM(G$first:H$last)", c.getValue("G$value").formula)
        assertEquals("G$value/D$value", c.getValue("I$value").formula)
        // the cached results equal the formulas over the printed cells
        val sevens = (first..last).sumOf { c.getValue("F$it").text!!.toDouble() }
        val business = (first..last).sumOf { r -> listOf("G", "H").sumOf { col -> c.getValue("$col$r").text?.toDouble() ?: 0.0 } }
        assertEquals(30.1, c.getValue("D$value").text!!.toDouble(), 0.0)
        assertEquals(sevens, c.getValue("D$value").text!!.toDouble(), 1e-9)
        assertEquals(20.1, c.getValue("G$value").text!!.toDouble(), 0.0)
        assertEquals(business, c.getValue("G$value").text!!.toDouble(), 1e-9)
        assertEquals(20.1 / 30.1, c.getValue("I$value").text!!.toDouble(), 1e-12)
        // ⑬ is a number shown as 0.0%, not text
        val styles = wb.getValue("xl/styles.xml")
        assertEquals("0.0%", formatOf(styles, c.getValue("I$value").style))
        assertEquals("#,##0.0", formatOf(styles, c.getValue("D$value").style))
    }

    @Test fun printedPagesCarryTheCarThePeriodAndPageNumbers() {
        val running = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = LocalDate.of(2026, 10, 26))
        val ledger = Ledger().also { l -> chainOf1004().forEach { l.append(LedgerKind.TRIP, it.id, Ledger.tripBody(it, null), it.endEpochMs) } }
        val wb = parts(LogbookExport.toXlsx(running, listOf(LogbookExport.ledgerTable(ledger.all, chainOf1004(), year2026, "CODE"))))
        listOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml").forEach { part ->
            val d = dom(wb.getValue(part))
            val top = d.elements("oddHeader").single().textContent
            assertTrue(top, top.startsWith("&L자동차등록번호 00가 0000&R사업연도 2026-01-01 ~ 2026-12-31, 2026-10-26 현재 누계"))
            assertEquals("&C페이지 &P / &N", d.elements("oddFooter").single().textContent)
            assertTrue("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>" in wb.getValue(part))
        }
        // the merged note gets an explicit height for its wrapped lines
        val note = dom(wb.getValue("xl/worksheets/sheet1.xml")).elements("row").last()
        assertTrue(note.textContent.startsWith("작성: 오토브리프"))
        assertEquals("1", note.getAttribute("customHeight"))
        assertTrue(note.getAttribute("ht"), note.getAttribute("ht").toDouble() >= 30.0)
        // an extract names its period on every page; ledger sequence numbers print as whole numbers
        val march = LogbookExport.build(chainOf1004(), settings, TaxPeriods.month(2026, 3), "CODE", businessYear = false)
        assertTrue(dom(parts(LogbookExport.toXlsx(march)).getValue("xl/worksheets/sheet1.xml")).elements("oddHeader").single().textContent
            .endsWith("&R발췌 기간 2026-03-01 ~ 2026-03-31"))
        assertEquals("#,##0", formatOf(wb.getValue("xl/styles.xml"), cells(wb.getValue("xl/worksheets/sheet2.xml")).getValue("A2").style))
    }

    @Test fun csvKeepsOneSimpleHeaderRow() {
        val sheet = LogbookExport.build(chainOf1004(), settings, year2026, "CODE", exportDate = finished)
        val lines = LogbookExport.toCsv(sheet).toString(Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n")
        val header = sheet.headerLines.size
        assertEquals(LogbookExport.COLUMNS.joinToString(","), lines[header])
        assertEquals(1, lines.count { it.startsWith("③사용일자(요일),") })
        assertFalse(lines.any { "운 행 내 역" in it })
        assertEquals("2026-03-02(월),영업팀,김기현,100,110,10,10,,", lines[header + 1])
        assertEquals("⑪사업연도 총주행 거리(㎞),30.1", lines[header + 4])
        assertEquals("⑫사업연도 업무용 사용거리(㎞),20.1", lines[header + 5])
        assertEquals("⑬업무사용비율(⑫/⑪),66.8%", lines[header + 6])
    }
}
