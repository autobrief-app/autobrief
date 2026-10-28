package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.LogbookExport
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.PlatformFix
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가족 운행: for one drive the app reads no location at all and writes only the distance, as 비업무. It ends by itself when
 * that drive ends; positions that still arrive meanwhile change nothing.
 */
class FamilyDriveTest {
    private val user = CarUser("김예시", "영업팀")
    private val base = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000", users = listOf(user))
    private val family = GleoContract.parseParams("""{"purpose":"FAMILY"}""")

    private fun Harness.gps(p: GeoPoint) = core.onPlatformFix(PlatformFix(p, now, 8f))

    /** Both position sources report [p]: navigation and the car's own GNSS. */
    private fun Harness.here(p: GeoPoint) {
        at(p)
        gps(p)
    }

    /** How many times the place rules ran (the core's seam around AutoClassifier). */
    private fun Harness.countPlaceRules(): () -> Int {
        var n = 0
        val real = core.placeRules
        core.placeRules = { start, end, places -> n++; real(start, end, places) }
        return { n }
    }

    private fun body(h: Harness, tripId: String, kind: String) =
        Json.parseToJsonElement(h.core.ledgerFor(tripId).single { it.kind == kind }.body).jsonObject

    @Test fun anArmedDriveIsWrittenAsPersonalWithNoPlacesNoRulesAndNoPositionAsked() {
        val h = Harness(base, detailLogs = true)
        h.bookmarks()
        val rules = h.countPlaceRules()
        h.here(SampleHistory.HOME.point)
        h.odo(45_200.0); h.gear(Gear.PARK)
        assertEquals(AutoBriefCore.FamilyResult.ARMED, h.core.startFamilyDrive()) // 다음 운행은 가족 운행
        assertTrue(h.core.settings.value.familyNext)
        assertTrue(h.core.locationPaused.value)
        assertNull("the position held when it turned on is cleared", h.core.positionSource.value)
        val asked = h.locationRequests
        val logged = h.logs.size

        h.here(SampleHistory.HOME.point) // still arriving: dropped
        h.advance(60_000); h.gear(Gear.DRIVE)
        h.core.onNavi(NaviEvent.RouteStarted("가상 목적지", 1_800)) // a route on the map: its toll estimate is not kept either
        val open = h.core.openTrip.value!!
        assertTrue(open.family)
        assertNull(open.startPlace)
        assertTrue(h.store.data.getValue(Space.LIVE).openTrip!!.family)
        h.advance(30 * 60_000)
        h.odo(45_212.0)
        h.here(SampleHistory.OFFICE.point)
        h.core.tick()
        assertNull(h.core.positionSource.value)
        assertEquals(AutoBriefCore.PlaceResult.NO_LOCATION, h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT))
        assertEquals("no position asked while it is on", asked, h.locationRequests)
        assertTrue("no position logged: ${h.logs.drop(logged)}", h.logs.drop(logged).none { it.startsWith("POSITION") })

        h.gear(Gear.PARK)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.PERSONAL, row.purpose)
        assertTrue(row.family)
        assertEquals("가족 운행", row.memo)
        assertEquals(ClassifiedBy.FAMILY, row.classifiedBy)
        assertEquals(user, row.user)
        assertNull(row.startPlace); assertNull(row.endPlace); assertNull(row.autoReason)
        assertNull("only the distance: no route toll estimate", row.routeTollEstimateKrw)
        assertEquals("the place rules never ran", 0, rules())
        assertEquals(listOf(LedgerKind.TRIP, LedgerKind.CLASSIFY), h.core.ledgerFor(row.id).map { it.kind })
        val trip = body(h, row.id, LedgerKind.TRIP)
        assertEquals("true", trip.getValue("family").toString())
        assertFalse("startPlace" in trip || "endPlace" in trip)
        val classify = body(h, row.id, LedgerKind.CLASSIFY)
        assertFalse("startPlace" in classify || "endPlace" in classify)
        assertTrue(h.core.integrity.value.ok)
        val month = h.core.summary(TaxPeriods.month(2026, 10))
        assertEquals(0, month.unclassifiedCount)
        assertEquals(12.0, month.personalKm, 0.0)
        assertTrue(h.logs.toString(), h.logs.any { it.startsWith("TRIP_CLOSED") && "purpose=PERSONAL" in it && "family=true" in it })

        // it ended with that drive: normal again, and the next trip asks for positions and learns its places
        assertFalse(h.core.settings.value.familyNext)
        assertFalse(h.core.locationPaused.value)
        assertEquals("the P that closed it asks again", asked + 1, h.locationRequests)
        h.here(SampleHistory.OFFICE.point)
        h.advance(60_000); h.gear(Gear.DRIVE)
        assertEquals("회사", h.core.openTrip.value!!.startPlace)
        assertFalse(h.core.openTrip.value!!.family)
    }

    @Test fun voiceTurnsAnOpenTripIntoAFamilyTripAndDropsItsStartPlace() {
        val h = Harness(base, detailLogs = true)
        h.bookmarks()
        h.here(SampleHistory.HOME.point)
        h.odo(45_200.0); h.gear(Gear.PARK); h.advance(60_000)
        h.here(SampleHistory.HOME.point)
        h.gear(Gear.DRIVE)
        assertEquals("집", h.core.openTrip.value!!.startPlace)
        assertEquals("집", h.store.data.getValue(Space.LIVE).openTrip!!.startPlace)

        val reply = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, family)
        assertEquals("success", reply.status)
        assertEquals("Family drive on: no location is read for this drive.", reply.message)
        assertTrue(h.core.openTrip.value!!.family)
        assertNull(h.core.openTrip.value!!.startPlace)
        val saved = h.store.data.getValue(Space.LIVE).openTrip!!
        assertTrue(saved.family)
        assertNull("the start place is not kept for it", saved.startPlace)
        assertNull(h.core.positionSource.value)

        h.advance(20 * 60_000)
        h.here(SampleHistory.OFFICE.point) // home to office would be a commute: not for a family trip
        h.odo(45_218.0)
        h.gear(Gear.PARK)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.PERSONAL, row.purpose)
        assertTrue(row.family)
        assertNull(row.startPlace); assertNull(row.endPlace)
        assertTrue(h.core.ledgerEntries.none { "집" in it.body || "회사" in it.body })
    }

    @Test fun familyNeverAppliesToATripThatAlreadyEnded() {
        val h = Harness(base)
        h.drive(0.0, 12.0, null, null)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1); h.core.tick()
        val reply = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, family)
        assertEquals("fail", reply.status)
        assertEquals("Family drive can only start before or during a drive.", reply.message)
        assertEquals(TripPurpose.UNCLASSIFIED, h.core.trips.value.single().purpose)
        assertFalse(h.core.settings.value.familyNext)
        assertFalse(h.core.locationPaused.value)
        // once that trip is no longer the one just ended, parked with no open trip arms the next drive
        h.advance(AutoBriefCore.VOICE_CLASSIFY_WINDOW_MS + 1)
        val armed = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, family)
        assertEquals("success", armed.status)
        assertEquals("Family drive on: no location is read for the next drive.", armed.message)
        assertTrue(h.core.settings.value.familyNext)
    }

    @Test fun aRestartInTheMiddleOfAFamilyTripKeepsIt() {
        val h = Harness(base)
        h.odo(100.0); h.gear(Gear.PARK)
        h.core.startFamilyDrive()
        h.advance(60_000); h.gear(Gear.DRIVE)
        var asked = 0
        val again = AutoBriefCore(h.store, clock = { h.now }).also { it.naviControl = AutoBriefCore.NaviControl { asked++ } }
        assertTrue(again.openTrip.value!!.family)
        assertTrue(again.locationPaused.value)
        again.onVehicle(VehicleEvent.Connection(true))
        again.onVehicle(VehicleEvent.Odometer(104.0))
        again.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        again.onNavi(NaviEvent.Location(SampleHistory.CLIENT.point))
        h.advance(10 * 60_000)
        again.onVehicle(VehicleEvent.Odometer(112.0))
        assertEquals(0, asked)
        again.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val row = again.trips.value.single()
        assertEquals(TripPurpose.PERSONAL, row.purpose)
        assertTrue(row.family)
        assertEquals(12.0, row.distanceKm, 1e-9)
        assertFalse(again.settings.value.familyNext)
        assertTrue(again.integrity.value.ok)
    }

    @Test fun aGearShuffleDoesNotUseUpTheArmedDriveAndTheSwitchTurnsItOff() {
        val h = Harness(base)
        h.odo(100.0); h.gear(Gear.PARK)
        h.core.startFamilyDrive()
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(5_000); h.gear(Gear.PARK) // moved nowhere: no row
        assertTrue(h.core.trips.value.isEmpty())
        assertTrue(h.core.settings.value.familyNext)
        assertTrue(h.core.locationPaused.value)
        h.core.cancelFamilyDrive()
        assertFalse(h.core.settings.value.familyNext)
        assertFalse(h.core.locationPaused.value)
        h.drive(100.0, 7.0, null, null)
        assertFalse(h.core.trips.value.single().family)
    }

    @Test fun aFamilyTripCanBeCorrectedWithAReasonAndNoPlaceIsInvented() {
        val h = Harness(base)
        h.odo(100.0); h.gear(Gear.PARK)
        h.core.startFamilyDrive()
        h.drive(100.0, 12.0, null, null)
        val id = h.core.trips.value.single().id
        assertEquals(AutoBriefCore.ClassifyResult.REASON_REQUIRED, h.core.classifyTrip(id, TripPurpose.BUSINESS, null, "가족 운행", ClassifiedBy.SCREEN))
        assertEquals(
            AutoBriefCore.ClassifyResult.OK,
            h.core.classifyTrip(id, TripPurpose.BUSINESS, null, "가족 운행", ClassifiedBy.SCREEN, "기타 정정"),
        )
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.BUSINESS, row.purpose)
        assertTrue(row.amended)
        assertTrue(row.family)
        assertNull(row.startPlace); assertNull(row.endPlace)
        assertEquals(LedgerKind.AMEND, h.core.ledgerFor(id).last().kind)
        assertTrue(h.core.integrity.value.ok)
        val sheet = LogbookExport.build(h.core.trips.value, base, TaxPeriods.month(2026, 10), h.core.integrity.value.code, businessYear = false)
        val printed = sheet.rows.single()
        assertEquals(12.0, printed[7])
        assertEquals("위치 기록 안 함, 수정됨", printed[8])
    }

    @Test fun theWorkbookRowIsNonBusinessAndTheLedgerSheetSaysFamily() {
        val h = Harness(base)
        h.odo(100.0); h.gear(Gear.PARK)
        h.core.startFamilyDrive()
        h.drive(100.0, 12.0, null, null)
        val october = TaxPeriods.month(2026, 10)
        val sheet = LogbookExport.build(h.core.trips.value, base, october, h.core.integrity.value.code, businessYear = false)
        val row = sheet.rows.single()
        assertNull("⑧ empty", row[6])
        assertNull("⑨ empty", row[7])
        assertEquals("개인 운행(위치 기록 안 함)", row[8])
        assertEquals(0.0, sheet.totals.businessUseKm, 0.0)
        val ledger = LogbookExport.ledgerTable(h.core.ledgerEntries, h.core.trips.value, october, h.core.integrity.value.code)
        val tripLine = ledger.rows.first { it[2] == "운행 기록" }[4] as String
        assertTrue(tripLine, "family=true" in tripLine)
    }
}
