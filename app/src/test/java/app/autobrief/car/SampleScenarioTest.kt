package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.VehicleControl
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.Gear
import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Builds the FICTIONAL sample that the proposal, the demo videos and the attached xlsx all quote, by
 * replaying gear/odometer/navigation events through the real engine (no hand-written rows). Every
 * number shown elsewhere comes from build/sample/sample_summary.json written here.
 */
class SampleScenarioTest {

    private var now = 0L
    private fun t(d: LocalDate, h: Int, m: Int) = ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, KST).toInstant().toEpochMilli()

    private val sales = CarUser("김예시", "영업팀")
    private val planning = CarUser("이샘플", "기획팀")
    private val clients = listOf("가상고객사 A 미팅", "가상고객사 B 납품 협의", "가상고객사 C 현장 점검", "협력사 교육 참석", "가상고객사 D 계약 협의")
    private val holidays = setOf(
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02", "2026-05-05", "2026-05-25",
        "2026-06-03", "2026-08-17", "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09",
    ).map(LocalDate::parse).toSet()

    private class Trip(val start: Long, val end: Long, val km: Int, val purpose: TripPurpose, val user: CarUser, val memo: String)

    private fun plannedTrips(): List<Trip> {
        val out = mutableListOf<Trip>()
        var d = LocalDate.of(2026, 1, 2)
        while (d.isBefore(LocalDate.of(2026, 10, 23))) {
            val n = d.dayOfYear
            val weekday = d.dayOfWeek.value <= 5 && d !in holidays
            if (weekday) {
                val user = if ((d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)) % 2 == 0) sales else planning
                val commute = 14 + n % 5
                out += Trip(t(d, 8, 10), t(d, 8, 44), commute, TripPurpose.COMMUTE, user, "출근")
                if (n % 3 == 0) out += Trip(t(d, 14, 0), t(d, 15, 5), 20 + (n * 7) % 37, TripPurpose.BUSINESS, user, clients[n % clients.size])
                out += Trip(t(d, 18, 30), t(d, 19, 4), commute, TripPurpose.COMMUTE, user, "퇴근")
            } else if (d.dayOfWeek == DayOfWeek.SATURDAY && n % 2 == 0) {
                out += Trip(t(d, 11, 0), t(d, 11, 40), 12 + n % 23, TripPurpose.PERSONAL, sales, "")
            }
            d = d.plusDays(1)
        }
        return out
    }

    @Test fun buildFictionalSample() {
        val settings = AppSettings(
            entityName = "가상예시 주식회사", businessNumber = "000-00-00000",
            vehicleModel = "그랜저 하이브리드 (가상 예시 차량)", plateNumber = "00가 0000",
            users = listOf(sales, planning), defaultUserIndex = 0,
            familyWebhookUrl = "https://example.invalid/family", archiveWebhookUrl = "https://example.invalid/archive",
        )
        val core = AutoBriefCore(initialSettings = settings, clock = { now })
        val dimmed = mutableListOf<Int>()
        core.control = object : VehicleControl {
            override fun readAmbientBrightness(onResult: (Int?) -> Unit) = onResult(100)
            override fun setAmbientBrightness(value: Int, onResult: (Boolean) -> Unit) { dimmed += value; onResult(true) }
        }

        // history up to 2026-10-22, with one stretch the app missed (2026-03-14, 27 km)
        val trips = plannedTrips()
        val gapBefore = trips.indexOfFirst { it.start > t(LocalDate.of(2026, 3, 14), 0, 0) }
        val gapKm = 27
        val historyKm = trips.sumOf { it.km } + gapKm
        var odo = 45_200 - historyKm // the chain ends at 45,200 km where today's demo starts
        val firstOdo = odo
        trips.forEachIndexed { i, trip ->
            if (i == gapBefore) odo += gapKm
            now = trip.start; core.onVehicle(VehicleEvent.Odometer(odo.toDouble())); core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
            odo += trip.km
            now = trip.end; core.onVehicle(VehicleEvent.Odometer(odo.toDouble())); core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
            val pending = core.trips.value.last { it.purpose == TripPurpose.UNCLASSIFIED }
            core.classifyTrip(pending.id, trip.purpose, trip.user, trip.memo, "화면")
        }
        assertEquals(45_200, odo)

        // Mode 1 today: 45,200 -> 45,218 km, purpose given by voice through the Gleo function
        val today = LocalDate.of(2026, 10, 23)
        now = t(today, 10, 20)
        core.onNavi(NaviEvent.RouteStarted("가상고객사 판교 사옥", 1_800))
        core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now = t(today, 10, 58)
        core.onVehicle(VehicleEvent.Odometer(45_218.0)); core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val voice = core.handleGleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"BUSINESS","memo":"가상고객사 미팅"}"""))
        assertEquals("success", voice.status)
        core.onNavi(NaviEvent.RouteEnded)
        val todayTrip = core.trips.value.last()

        // Mode 2: rear-right passenger reclines with the reading light off for 60 s
        now = t(today, 15, 0)
        core.onVehicle(VehicleEvent.AmbientBounds(0..100))
        core.onVehicle(VehicleEvent.BackrestBounds(SeatPos.values().associateWith { 0..100 }))
        core.onVehicle(VehicleEvent.SeatOccupancy(SeatPos.ROW2_RIGHT, true))
        core.onVehicle(VehicleEvent.SeatBelt(SeatPos.ROW2_RIGHT, true))
        core.onVehicle(VehicleEvent.ReadingLight(SeatPos.ROW2_RIGHT, false))
        core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_RIGHT, 28))
        now += 60_000; core.tick()
        val sleep = core.sleep.value
        assertEquals(setOf(SeatPos.ROW2_RIGHT), sleep.sleeping)
        val rearReclinePct = Math.round(core.vehicle.value.seats.getValue(SeatPos.ROW2_RIGHT).reclineRatio!! * 100).toInt()
        val rearHint = sleep.driverHint!!
        val gleoModeWhileAsleep = core.gleoMode()
        core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW2_RIGHT, 95)) // sits up again

        // Mode 2 again, front passenger (the short-form footage shows a front-seat sleeper)
        now = t(today, 15, 30)
        core.onVehicle(VehicleEvent.SeatOccupancy(SeatPos.ROW1_RIGHT, true))
        core.onVehicle(VehicleEvent.SeatBelt(SeatPos.ROW1_RIGHT, true))
        core.onVehicle(VehicleEvent.ReadingLight(SeatPos.ROW1_RIGHT, false))
        core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW1_RIGHT, 35))
        now += 60_000; core.tick()
        val front = core.sleep.value
        assertEquals(setOf(SeatPos.ROW1_RIGHT), front.sleeping)
        val frontReclinePct = Math.round(core.vehicle.value.seats.getValue(SeatPos.ROW1_RIGHT).reclineRatio!! * 100).toInt()
        val frontHint = front.driverHint!!
        core.onVehicle(VehicleEvent.Backrest(SeatPos.ROW1_RIGHT, 95))

        // Mode 3: designated driver 22:40 -> 23:06, 45,218 -> 45,229 km, one hard brake
        core.setCustodyPin("2580")
        now = t(today, 22, 40)
        core.onNavi(NaviEvent.Destination("우리집: 한빛마을 3단지 104동 1802호", null, 11_000, 1_560, null))
        val session = core.startCustody(driverConsented = true, notifyFamily = true)!!
        core.onNavi(NaviEvent.SpeedLimit(60))
        core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        // calm acceleration (+6 km/h per second), one hard brake 52 -> 34 km/h, calm stop
        listOf(0.0, 6.0, 12.0, 18.0, 24.0, 30.0, 36.0, 42.0, 48.0, 52.0, 34.0, 40.0, 46.0, 52.0, 56.0, 48.0, 40.0, 32.0, 24.0, 16.0, 8.0, 0.0).forEachIndexed { i, v ->
            now = t(today, 22, 41) + i * 1_000L
            core.onVehicle(VehicleEvent.Speed(v))
        }
        now = t(today, 23, 6)
        core.onNavi(NaviEvent.Destination("우리집: 한빛마을 3단지 104동 1802호", null, 80, 30, null))
        core.onVehicle(VehicleEvent.DoorsLocked(true))
        core.onVehicle(VehicleEvent.Odometer(45_229.0))
        core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val report = core.reports.value.single()
        assertEquals(CustodyEndReason.ARRIVED, report.endReason)
        assertEquals(0, report.harshAccelEvents)
        assertEquals(1, report.harshBrakeEvents)
        assertEquals(0, report.overspeedEpisodes)
        val driven = core.trips.value.last()
        core.classifyTrip(driven.id, TripPurpose.PERSONAL, sales, "저녁 모임 후 귀가", "화면")

        // exports through the real writer
        val dir = File("build/sample").apply { mkdirs() }
        val banner = "가상 예시 데이터: 실제 차량, 법인, 인물이 아닙니다 (오토브리프 1.6.0 기록 엔진으로 생성)"
        val year = TaxPeriods.businessYear(2026, 1)
        val october = TaxPeriods.month(2026, 10)
        val yearSheet = core.exportSheet(year, banner)
        val monthSheet = core.exportSheet(october, banner, businessYear = false)
        File(dir, "운행기록부_2026_사업연도_가상예시.xlsx").writeBytes(LogbookExport.toXlsx(yearSheet))
        File(dir, "운행기록부_2026_사업연도_가상예시.csv").writeBytes(LogbookExport.toCsv(yearSheet))
        File(dir, "운행기록부_2026-10_가상예시.xlsx").writeBytes(LogbookExport.toXlsx(monthSheet))
        val ys = core.summary(year)
        val ms = core.summary(october)
        // the numbers the proposal, the figures and the videos quote
        assertEquals(0, ys.unclassifiedCount)
        assertEquals(0, ys.continuityBreaks)
        assertEquals(487, ys.rowCount)
        assertEquals(9636.0, ys.totalKm, 1e-9)
        assertEquals(9117.0, ys.businessUseKm, 1e-9)
        assertEquals("94.6", "%.1f".format(ys.businessUseRatio!! * 100))
        assertEquals(36, ms.rowCount)
        assertEquals(listOf(20, 100, 20, 100), dimmed) // dimmed to 20 %, then back to the level the cabin had (rear, then front)
        assertEquals(72, rearReclinePct)
        assertEquals(65, frontReclinePct)

        val summary = buildJsonObject {
            put("label", banner)
            put("year_rows", ys.rowCount); put("year_total_km", ys.totalKm); put("year_business_use_km", ys.businessUseKm)
            put("year_commute_km", ys.commuteKm); put("year_business_km", ys.businessKm)
            put("year_ratio_pct", "%.1f".format(ys.businessUseRatio!! * 100)); put("year_gap_rows", ys.gapRowCount)
            put("year_first_odometer_km", firstOdo); put("year_first_date", "2026-01-02")
            put("october_rows", ms.rowCount); put("october_total_km", ms.totalKm)
            put("october_ratio_pct", "%.1f".format(ms.businessUseRatio!! * 100))
            put("today_start_km", todayTrip.startOdometerKm); put("today_end_km", todayTrip.endOdometerKm)
            put("today_distance_km", todayTrip.distanceKm); put("today_toll_estimate_krw", todayTrip.routeTollEstimateKrw ?: -1)
            put("today_purpose", todayTrip.purpose.label); put("today_classified_by", todayTrip.classifiedBy ?: "")
            put("sleep_seat", SeatPos.ROW2_RIGHT.label); put("sleep_recline_pct", rearReclinePct); put("sleep_hold_s", 60)
            put("sleep_hint", rearHint)
            put("sleep_front_seat", SeatPos.ROW1_RIGHT.label); put("sleep_front_recline_pct", frontReclinePct); put("sleep_front_hint", frontHint)
            put("sleep_ambient_dim_to", dimmed.firstOrNull() ?: -1); put("gleo_mode_while_asleep", gleoModeWhileAsleep)
            put("custody_destination", session.destinationLabel ?: ""); put("custody_start", "22:40"); put("custody_end", "23:06")
            put("custody_distance_km", report.distanceKm ?: -1.0); put("custody_overspeed", report.overspeedEpisodes)
            put("custody_harsh_accel", report.harshAccelEvents); put("custody_harsh_brake", report.harshBrakeEvents)
            put("custody_max_speed_kph", report.maxSpeedKph); put("custody_doors_locked", report.doorsLocked == true)
            put("outbox_queued", core.outbox.value.size); put("outbox_delivered", core.outbox.value.count { it.deliveredEpochMs != null })
            put("custody_messages", core.outbox.value.joinToString(" | ") { it.payloadJson })
        }
        File(dir, "sample_summary.json").writeText(summary.toString())
    }
}
