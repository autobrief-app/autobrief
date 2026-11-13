package app.autobrief.car

import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LogbookExportTest {
    private val h = DocsSample.build()
    private val rows = h.core.trips.value
    private val entries = h.core.ledgerEntries
    private val october = TaxPeriods.month(2026, 10)
    private val code = h.core.integrity.value.code

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test fun workbookIsWellFormedOoxmlWithTheFormAndTheLedgerSheet() {
        val sheet = LogbookExport.build(rows, SampleHistory.settings(), october, code, businessYear = false)
        val table = LogbookExport.ledgerTable(entries, rows, october, code)
        val parts = unzip(LogbookExport.toXlsx(sheet, listOf(table)))
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"),
            parts.keys,
        )
        val dbf = DocumentBuilderFactory.newInstance()
        parts.values.forEach { xml -> dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())) } // every part parses
        val s1 = parts.getValue("xl/worksheets/sheet1.xml")
        LogbookExport.COLUMNS.forEach { assertTrue(it, it in s1) }
        assertTrue("가상 예시 데이터입니다" in s1)
        assertTrue("검증 코드 $code" in s1)
        assertTrue("⑬업무사용비율(⑫/⑪)" in s1)
        assertTrue("자동 분류(집→회사)" in s1)
        assertTrue("<pageSetup paperSize=\"9\" orientation=\"landscape\"" in s1)
        val s2 = parts.getValue("xl/worksheets/sheet2.xml")
        assertTrue("목적 지정" in s2 && "운행 기록" in s2)
        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"검증 기록\""))
        // the October extract quoted in the proposal: 36 rows
        assertEquals(36, sheet.rows.size)
        assertTrue("음성 지정" in s1)
    }

    @Test fun columnsFollowTheNtsForm() {
        val sheet = LogbookExport.build(rows, SampleHistory.settings(), october, code, businessYear = false)
        val commute = sheet.rows.first { it[6] != null }
        assertEquals(null, commute[7])
        assertEquals(commute[5], commute[6]) // ⑧ = ⑦ for a commute
        val gapFree = sheet.rows.none { (it[8] as String).contains("미기록") }
        assertTrue(gapFree)
        assertEquals("2026-10-01(목)", LogbookExport.dateWithWeekday(LocalDate.of(2026, 10, 1)))
    }

    @Test fun theSampleYearBookIsTheTotalTo20261023() {
        // the sample's clock stops on 2026-10-23, inside the business year 2026
        val sheet = h.core.exportSheet(TaxPeriods.businessYear(2026, 1), businessYear = true)
        assertEquals("⑪2026-10-23 현재 누계(사업연도 진행 중) 총주행 거리(㎞)", sheet.totals.totalLabel)
        assertEquals(9_636.0, sheet.totals.totalKm, 0.0)
        assertEquals(9_117.0, sheet.totals.businessUseKm, 0.0)
    }

    @Test fun aYearStillRunningIsTheTotalToTheDayTheWorkbookIsMade() {
        // the last trip was on 2026-10-23; the workbook is made ten days later, still inside the business year
        val h = Harness()
        h.drive(1_000.0, 12.0, null, null)
        h.advance(10 * 86_400_000L)
        val sheet = h.core.exportSheet(TaxPeriods.businessYear(2026, 1), businessYear = true)
        assertEquals("⑪2026-11-02 현재 누계(사업연도 진행 중) 총주행 거리(㎞)", sheet.totals.totalLabel)
    }

    @Test fun tollEstimateMovesFromRemarksToTheLedgerSheet() {
        val sheet = LogbookExport.build(rows, SampleHistory.settings(), october, code, businessYear = false)
        assertFalse(sheet.rows.any { "통행료" in (it[8] as String) })
        val voiceTrip = rows.single { it.routeTollEstimateKrw != null }
        val tripEntry = LogbookExport.ledgerTable(entries, rows, october, code).rows
            .single { it[2] == "운행 기록" && it[3] == LogbookExport.dateWithWeekday(DocsSample.TODAY) && "45,200.0" in (it[4] as String) }
        assertEquals(1_800, voiceTrip.routeTollEstimateKrw)
        assertEquals("계기판 45,200.0 → 45,218.0 km · 내비 예상 통행료 1,800원", tripEntry[4])
    }

    @Test fun theSampleGapPrintsItsDateRangeAndTheNoteCountsBreaks() {
        val sheet = LogbookExport.build(rows, SampleHistory.settings(), TaxPeriods.businessYear(2026, 1), code)
        val gap = sheet.rows.single { (it[8] as String).startsWith("미기록 구간") }
        assertEquals("2026-03-13(금)", gap[0])
        assertEquals("미기록 구간 2026-03-13(금) ~ 2026-03-16(월)", gap[8])
        assertTrue(sheet.note, "계기판 연속성 끊김 0곳" in sheet.note)
    }

    @Test fun csvNeutralisesFormulaCells() {
        val bad = rows.map { if (it.purpose == TripPurpose.BUSINESS) it.copy(memo = "=HYPERLINK(\"x\")") else it }
        val sheet = LogbookExport.build(bad, SampleHistory.settings(), october, code, businessYear = false)
        val csv = LogbookExport.toCsv(sheet).toString(Charsets.UTF_8)
        assertFalse(csv.lines().any { line -> line.split(",").any { it.startsWith("=") } })
    }
}

class GleoSchemaTest {
    private val schema = Json.parseToJsonElement(File("src/main/assets/schema.json").readText()).jsonObject
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun atMostThreeFunctionsWithComponentInsideFunction() {
        val calls = schema.getValue("actionCalls").jsonArray
        assertTrue(calls.size <= 3)
        val names = calls.map { c ->
            val f = (c as JsonObject).getValue("function").jsonObject
            assertEquals("service", f.getValue("component").jsonPrimitive.content)
            assertTrue(f.getValue("description").jsonPrimitive.content.length > 40)
            f.getValue("name").jsonPrimitive.content
        }.toSet()
        assertEquals(GleoContract.FUNCTIONS, names)
        names.forEach { assertTrue("manifest receiver lists $it", "<action android:name=\"$it\" />" in manifest) }
        assertEquals("app.autobrief.car", schema.getValue("packageName").jsonPrimitive.content)
        assertTrue(schema.getValue("actionCalls") is JsonArray)
    }

    @Test fun gleoCanTurnOnFamilyDriveThroughThePurposeFunction() {
        val logTrip = schema.getValue("actionCalls").jsonArray.map { (it as JsonObject).getValue("function").jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == GleoContract.LOG_TRIP_PURPOSE }
        val purpose = logTrip.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("purpose").jsonObject
        assertEquals(listOf("COMMUTE", "BUSINESS", "PERSONAL", "FAMILY"), purpose.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("FAMILY" in logTrip.getValue("description").jsonPrimitive.content)
    }

    @Test fun manifestAsksOnlyForWhatTheLogbookUses() {
        val perms = Regex("""uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_INFO", "pleos.car.permission.NAVI_ROUTE",
                // vehicle energy telemetry (Vehicle Fuel/EvBattery, normal protection level)
                "pleos.car.permission.CAR_ENERGY",
                "android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.RECEIVE_BOOT_COMPLETED",
                // Pleos FusedLocation: the car's position when navigation has no fresh one (optional, asked once)
                "pleos.car.permission.FUSED_LOCATION",
            ),
            perms,
        )
    }
}
