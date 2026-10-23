package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.CustodyMessages
import app.autobrief.car.domain.CustodySession
import app.autobrief.car.domain.DisplayMasking
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SleepCareDetector
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.TripLogbook
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.SeatState
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0): Long =
    ZonedDateTime.of(y, m, d, h, min, 0, 0, KST).toInstant().toEpochMilli()

private fun TripLogbook.drive(startMs: Long, startKm: Double, endMs: Long, endKm: Double) {
    onOdometer(startKm); onGear(Gear.DRIVE, startMs)
    onOdometer(endKm); onGear(Gear.PARK, endMs)
}

class TripLogbookTest {
    @Test fun tripOpensOnDriveClosesOnParkAndStaysUnclassified() {
        val book = TripLogbook()
        book.drive(at(2026, 10, 23, 10), 45200.0, at(2026, 10, 23, 11), 45218.0)
        val trip = book.records.single()
        assertEquals(18.0, trip.distanceKm, 1e-9)
        assertEquals(TripPurpose.UNCLASSIFIED, trip.purpose)
        assertFalse(trip.purpose.countsAsBusiness)
    }

    @Test fun odometerJumpBecomesANonBusinessGapRow() {
        val book = TripLogbook()
        book.drive(at(2026, 3, 13), 1000.0, at(2026, 3, 13, 10), 1010.0)
        book.drive(at(2026, 3, 16), 1037.0, at(2026, 3, 16, 10), 1050.0)
        val rows = book.records
        assertEquals(3, rows.size)
        assertEquals(TripPurpose.UNLOGGED_GAP, rows[1].purpose)
        assertEquals(27.0, rows[1].distanceKm, 1e-9)
        assertEquals(0, TripLogbook.summarize(rows).continuityBreaks)
    }

    @Test fun parkingManoeuvreWithoutDistanceIsNotARow() {
        val book = TripLogbook()
        book.drive(at(2026, 5, 1), 2000.0, at(2026, 5, 1, 9, 2), 2000.0)
        assertTrue(book.records.isEmpty())
    }

    @Test fun periodsFollowTheBusinessYearAndHalfReallyFilters() {
        val book = TripLogbook()
        book.drive(at(2026, 3, 10), 100.0, at(2026, 3, 10, 10), 110.0)
        book.drive(at(2026, 10, 10), 110.0, at(2026, 10, 10, 10), 130.0)
        assertEquals(1, book.recordsIn(TaxPeriods.half(2026, 1, 1)).size)
        assertEquals(1, book.recordsIn(TaxPeriods.half(2026, 1, 2)).size)
        assertEquals(2, book.recordsIn(TaxPeriods.businessYear(2026, 1)).size)
        // a business year starting in April: 2026-03-10 belongs to business year 2025
        assertEquals(1, book.recordsIn(TaxPeriods.businessYear(2025, 4)).size)
        assertEquals(LocalDate.of(2027, 3, 31), TaxPeriods.businessYear(2026, 4).endInclusive)
    }

    @Test fun ratioCountsOnlyCommuteAndBusiness() {
        val book = TripLogbook()
        book.drive(at(2026, 6, 1), 0.0, at(2026, 6, 1, 10), 30.0)
        book.drive(at(2026, 6, 2), 30.0, at(2026, 6, 2, 10), 50.0)
        book.drive(at(2026, 6, 3), 50.0, at(2026, 6, 3, 10), 100.0)
        val (a, b, _) = book.records
        book.classify(a.id, TripPurpose.COMMUTE, null, "", "화면")
        book.classify(b.id, TripPurpose.PERSONAL, null, "", "화면")
        val s = TripLogbook.summarize(book.records)
        assertEquals(100.0, s.totalKm, 1e-9)
        assertEquals(30.0, s.businessUseKm, 1e-9)
        assertEquals(0.30, s.businessUseRatio!!, 1e-9)
        assertEquals(1, s.unclassifiedCount)
    }
}

class LogbookExportTest {
    private val settings = AppSettings(entityName = "가상예시 주식회사", businessNumber = "000-00-00000", vehicleModel = "가상 차량", plateNumber = "00가 0000")

    @Test fun sheetHasTheOfficialColumnsAndTotals() {
        val book = TripLogbook()
        book.drive(at(2026, 10, 23, 10), 45200.0, at(2026, 10, 23, 11), 45218.0)
        book.classify(book.records.single().id, TripPurpose.BUSINESS, CarUser("김예시", "영업팀"), "거래처 미팅", "음성")
        val sheet = LogbookExport.build(book.records, settings, TaxPeriods.businessYear(2026, 1))
        assertEquals(listOf("③사용일자(요일)", "④사용자 부서", "④사용자 성명", "⑤주행 전 계기판의 거리(㎞)", "⑥주행 후 계기판의 거리(㎞)", "⑦주행거리(㎞)", "⑧출·퇴근용(㎞)", "⑨일반 업무용(㎞)", "⑩비고"), sheet.columns)
        val row = sheet.rows.single()
        assertEquals("2026-10-23(금)", row[0])
        assertEquals(18.0, row[7])
        assertEquals("100.0%", sheet.footer[2][1])
    }

    @Test fun xlsxIsAValidPackageAndCsvHasABom() {
        val book = TripLogbook()
        book.drive(at(2026, 1, 5), 10.0, at(2026, 1, 5, 10), 25.0)
        val sheet = LogbookExport.build(book.records, settings, TaxPeriods.month(2026, 1), banner = "가상 예시")
        val names = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(LogbookExport.toXlsx(sheet))).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(names.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")))
        assertTrue(names.getValue("xl/worksheets/sheet1.xml").contains("업무용승용차 운행기록부"))
        assertTrue(names.getValue("xl/worksheets/sheet1.xml").contains("미분류")) // unclassified row stays visible
        val csv = LogbookExport.toCsv(sheet)
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), csv.take(3))
    }
}

class SleepCareTest {
    private val reclined = SeatState(occupied = true, belted = true, backrestPosition = 28, backrestMin = 0, backrestMax = 100, readingLightOn = false)

    @Test fun asleepOnlyAfterTheHoldTime() {
        val d = SleepCareDetector()
        assertTrue(d.update(mapOf(SeatPos.ROW2_RIGHT to reclined), 0).sleeping.isEmpty())
        assertTrue(d.update(mapOf(SeatPos.ROW2_RIGHT to reclined), 59_000).sleeping.isEmpty())
        val st = d.update(mapOf(SeatPos.ROW2_RIGHT to reclined), 60_000)
        assertEquals(setOf(SeatPos.ROW2_RIGHT), st.sleeping)
        assertTrue(st.quietMode)
        assertTrue(st.driverHint!!.contains("후석 취침 모드"))
    }

    @Test fun readingLightOrUprightWakesAndBagWithoutBeltNeverSleeps() {
        val d = SleepCareDetector()
        d.update(mapOf(SeatPos.ROW1_RIGHT to reclined), 0)
        assertEquals(setOf(SeatPos.ROW1_RIGHT), d.update(mapOf(SeatPos.ROW1_RIGHT to reclined), 61_000).sleeping)
        assertTrue(d.update(mapOf(SeatPos.ROW1_RIGHT to reclined.copy(readingLightOn = true)), 62_000).sleeping.isEmpty())
        val bag = SleepCareDetector()
        bag.update(mapOf(SeatPos.ROW1_RIGHT to reclined.copy(belted = false)), 0)
        assertTrue(bag.update(mapOf(SeatPos.ROW1_RIGHT to reclined.copy(belted = false)), 120_000).sleeping.isEmpty())
    }

    @Test fun frontPassengerHintDoesNotPointToRearSleepMode() {
        val d = SleepCareDetector()
        d.update(mapOf(SeatPos.ROW1_RIGHT to reclined), 0)
        val hint = d.update(mapOf(SeatPos.ROW1_RIGHT to reclined), 60_000).driverHint!!
        assertFalse(hint.contains("후석 취침 모드"))
    }
}

class CustodySessionTest {
    private fun session() = CustodySession("S", 0, 100.0, "가상 목적지", driverConsented = true)

    @Test fun aSustainedHardAccelerationIsOneEvent() {
        val s = session()
        s.onSpeed(10.0, 0)
        s.onSpeed(22.0, 1_000) // +12 km/h/s from 10 km/h
        s.onSpeed(34.0, 2_000) // same burst, within the merge window
        s.onSpeed(44.0, 3_000)
        assertEquals(1, s.harshAccelEvents)
        s.onSpeed(44.0, 10_000)
        s.onSpeed(53.0, 11_000) // a second, separate burst (+9 km/h/s above 20 km/h)
        assertEquals(2, s.harshAccelEvents)
    }

    @Test fun overspeedCountsEpisodesAgainstLimitPlus20() {
        val s = session()
        s.onSpeedLimit(60)
        listOf(70.0, 81.0, 85.0, 83.0, 75.0, 59.0, 82.0).forEachIndexed { i, v -> s.onSpeed(v, i * 1_000L) }
        assertEquals(2, s.overspeedEpisodes)
    }

    @Test fun parkAwayFromDestinationIsAStopNotArrival() {
        val s = session()
        assertNull(s.onGear(Gear.DRIVE, 0, 100.0, 9_000, null))
        assertNull(s.onGear(Gear.PARK, 1_000, 104.0, 4_200, null))
        assertEquals(1, s.intermediateStops)
        s.onGear(Gear.DRIVE, 2_000, 104.0, 4_200, null)
        val r = s.onGear(Gear.PARK, 3_000, 111.0, 120, true)!!
        assertEquals(CustodyEndReason.ARRIVED, r.endReason)
        assertEquals(11.0, r.distanceKm!!, 1e-9)
    }

    @Test fun earlyEndNeverClaimsArrival() {
        val r = session().endEarly(5_000, 103.0, null)
        val text = CustodyMessages.end(r, "22:50")
        assertTrue(text.contains("도착 전에 종료"))
        assertFalse(text.contains("도착해 주차"))
    }
}

class DisplayMaskingTest {
    @Test fun masksUnitsButNotDistrictsLinesOrBranches() {
        assertEquals("서울 강남구 역삼1동 테헤란로 152", DisplayMasking.mask("서울 강남구 역삼1동 테헤란로 152"))
        assertEquals("강남역 2호선 11번 출구", DisplayMasking.mask("강남역 2호선 11번 출구"))
        assertEquals("스타벅스 신림3호점", DisplayMasking.mask("스타벅스 신림3호점"))
        assertEquals("한빛마을 3단지 동·호수 비공개", DisplayMasking.mask("우리집: 한빛마을 3단지 104동 1802호"))
    }
}

class CoreAndGleoTest {
    private var now = at(2026, 10, 23, 10)
    private fun core(settings: AppSettings = AppSettings()) = AutoBriefCore(initialSettings = settings, clock = { now })

    @Test fun emptyStartDoesNotThrow() {
        val c = core()
        val year = TaxPeriods.businessYear(2026, 1)
        assertEquals(0, c.summary(year).rowCount)
        assertTrue(c.exportSheet(year).rows.isEmpty())
        assertEquals("asking", c.handleGleo(GleoContract.LOG_TRIP_PURPOSE, JsonObject(mapOf())).status)
    }

    @Test fun voicePurposeClassifiesTheLatestTrip() {
        val c = core(AppSettings(users = listOf(CarUser("김예시", "영업팀"))))
        assertEquals("fail", c.handleGleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"BUSINESS"}""")).status)
        c.onVehicle(VehicleEvent.Odometer(45200.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now += 38 * 60_000
        c.onVehicle(VehicleEvent.Odometer(45218.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val reply = c.handleGleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("\"{\\\"purpose\\\":\\\"BUSINESS\\\",\\\"memo\\\":\\\"거래처 미팅\\\"}\""))
        assertEquals("success", reply.status)
        val trip = c.trips.value.single()
        assertEquals(TripPurpose.BUSINESS, trip.purpose)
        assertEquals("음성", trip.classifiedBy)
        assertEquals("김예시", trip.user!!.name)
        assertEquals("asking", c.handleGleo(GleoContract.LOG_TRIP_PURPOSE, JsonObject(mapOf())).status)
    }

    @Test fun responseFollowsTheCallbackSchema() {
        val json = Json.parseToJsonElement(GleoContract.response("SET_SLEEP_CARE", AutoBriefCore.GleoReply("success", "Passenger sleep care is on."), "beep")).jsonObject
        assertEquals("SET_SLEEP_CARE", json["functionName"]!!.jsonPrimitive.content)
        assertEquals("success", json["status"]!!.jsonPrimitive.content)
        assertEquals("beep", json["mode"]!!.jsonPrimitive.content)
    }

    @Test fun custodyNeedsConsentAndEarlyEndIsReportedAsEarly() {
        val c = core(AppSettings(familyWebhookUrl = "https://example.invalid/family"))
        assertNull(c.startCustody(driverConsented = false, notifyFamily = true))
        c.setCustodyPin("2580")
        c.onNavi(NaviEvent.Destination("우리집: 한빛마을 3단지 104동 1802호", null, 11_000, 1_500, null))
        assertNotNull(c.startCustody(driverConsented = true, notifyFamily = true))
        assertEquals("한빛마을 3단지 동·호수 비공개", c.custody.value!!.destinationLabel)
        assertEquals(AutoBriefCore.EndResult.WRONG_PIN, c.endCustodyEarly("0000"))
        assertEquals(AutoBriefCore.EndResult.ENDED, c.endCustodyEarly("2580"))
        val texts = c.outbox.value.map { it.payloadJson }
        assertEquals(2, texts.size)
        assertTrue(texts.last().contains("도착 전에 종료"))
        assertTrue(c.outbox.value.all { it.deliveredEpochMs == null }) // nothing is "sent" until a webhook answers 2xx
    }
}

class SchemaTest {
    @Test fun schemaFollowsTheGleoAppSchemaGuide() {
        val schema = Json.parseToJsonElement(File("src/main/assets/schema.json").readText()).jsonObject
        val calls = schema["actionCalls"]!!.jsonArray
        assertTrue(calls.size <= 3)
        val names = calls.map { call ->
            val o = call.jsonObject
            assertEquals("function", o["type"]!!.jsonPrimitive.content)
            assertNull("component must sit inside function", o["component"])
            val f = o["function"]!!.jsonObject
            assertTrue(f["component"]!!.jsonPrimitive.content in setOf("activity", "service"))
            val name = f["name"]!!.jsonPrimitive.content
            assertTrue(name.matches(Regex("[A-Z]+(_[A-Z]+)*")))
            name
        }.toSet()
        assertEquals(GleoContract.FUNCTIONS, names)
        assertEquals("app.autobrief.car", schema["packageName"]!!.jsonPrimitive.content)
    }
}
