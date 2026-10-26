package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.PlatformFix
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the car is: NaviHelper's position when it is fresh, else the car's own GNSS (LocationManager). On the Pleos
 * Connect emulator NaviHelper answers every request at once with a position that never moves (CI run 40278245470:
 * 37.4127, 127.0947, 2.5 km from the demo office), while the platform GNSS follows the injected fixes.
 */
class PositionTest {
    private val user = CarUser("김예시", "영업팀")
    private val base = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000", users = listOf(user))
    private val frozenNavi = GeoPoint(37.4127212, 127.094681)

    private fun Harness.gps(p: GeoPoint, ageMs: Long = 0, accuracyM: Float? = 8f) = core.onPlatformFix(PlatformFix(p, now - ageMs, accuracyM))

    /** 100 m east of [p]. */
    private fun near(p: GeoPoint) = GeoPoint(p.lat, p.lon + 0.00113)

    @Test fun aFreshNavigationPositionWins() {
        val h = Harness(base)
        h.at(SampleHistory.CLIENT.point)
        h.gps(near(SampleHistory.CLIENT.point)) // the same place seen by both, 100 m apart
        assertEquals(AutoBriefCore.PlaceResult.SAVED, h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT))
        assertEquals(SampleHistory.CLIENT.point, h.core.settings.value.places.single().point)
    }

    @Test fun aStaleNavigationPositionFallsBackToThePlatformOne() {
        val h = Harness(base)
        h.at(SampleHistory.CLIENT.point)
        h.advance(AutoBriefCore.NAVI_PREFERRED_MS + 1)
        h.gps(near(SampleHistory.CLIENT.point))
        h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT)
        assertEquals(near(SampleHistory.CLIENT.point), h.core.settings.value.places.single().point)
    }

    @Test fun withoutTheGrantTheAppWorksFromNavigationAsBefore() {
        val h = Harness(base)
        h.at(SampleHistory.CLIENT.point)
        h.advance(AutoBriefCore.NAVI_PREFERRED_MS + 1) // older than 30 s, still within the 2 min a place may use
        h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT)
        assertEquals(SampleHistory.CLIENT.point, h.core.settings.value.places.single().point)
    }

    @Test fun aNavigationPositionThatDoesNotFollowTheCarLosesToAFreshGnssFix() {
        val h = Harness(base, detailLogs = true)
        h.bookmarks()
        h.gps(SampleHistory.HOME.point)
        h.at(frozenNavi) // answered a moment ago, but 16 km from where the GNSS puts the car
        h.odo(45_200.0); h.gear(Gear.PARK); h.advance(60_000)
        h.gps(SampleHistory.HOME.point) // the platform reports every 5 s
        h.at(frozenNavi)
        h.gear(Gear.DRIVE)
        assertEquals("집", h.core.openTrip.value!!.startPlace)
        h.advance(20 * 60_000)
        h.gps(SampleHistory.OFFICE.point)
        h.at(frozenNavi)
        h.odo(45_218.0)
        h.gear(Gear.PARK)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.COMMUTE, row.purpose)
        assertEquals(ClassifiedBy.AUTO, row.classifiedBy)
        assertEquals("집→회사", row.autoReason)
        assertTrue(h.logs.toString(), h.logs.contains("POSITION place=회사 source=gps"))
    }

    @Test fun anArrivalFixAfterParkingResolvesThePendingPlaceRules() {
        val h = Harness(base)
        h.bookmarks()
        h.gps(SampleHistory.OFFICE.point)
        h.odo(100.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        h.advance(30 * 60_000); h.odo(111.0)
        h.gear(Gear.PARK) // the last fix is half an hour old: the rules wait for a fresh one
        assertEquals(TripPurpose.UNCLASSIFIED, h.core.trips.value.single().purpose)
        h.advance(3_000)
        h.gps(SampleHistory.HOME.point)
        assertEquals(TripPurpose.COMMUTE, h.core.trips.value.single().purpose)
    }

    @Test fun withGnssOnlyAClientArrivalAfterParkingStillClassifies() {
        val h = Harness(base.copy(places = listOf(SampleHistory.CLIENT)))
        // no position at departure, no navigation at all; the GNSS has a fix, but the last one before P is 40 s old
        h.odo(100.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        h.advance(20 * 60_000)
        h.gps(near(SampleHistory.CLIENT.point))
        h.advance(40_000); h.odo(111.0)
        h.gear(Gear.PARK)
        h.advance(4_000)
        h.gps(SampleHistory.CLIENT.point) // the next fix: the place rules were waiting for it
        assertEquals(TripPurpose.BUSINESS, h.core.trips.value.single().purpose)
    }

    @Test fun aCoarseOrImpossibleFixIsNotAPosition() {
        val h = Harness(base)
        h.gps(SampleHistory.CLIENT.point, accuracyM = 900f) // a network-only estimate cannot tell places apart
        h.gps(GeoPoint(0.0, 0.0))
        h.gps(GeoPoint(Double.NaN, 127.0))
        assertEquals(AutoBriefCore.PlaceResult.NO_LOCATION, h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT))
    }

    @Test fun releaseLogsNameNoPlaceAndNoSource() {
        val h = Harness(base.copy(places = listOf(SampleHistory.CLIENT)))
        h.bookmarks()
        h.gps(SampleHistory.HOME.point)
        h.drive(45_200.0, 18.0, null, null)
        h.gps(SampleHistory.OFFICE.point)
        assertTrue(h.logs.none { it.startsWith("POSITION") || "gps" in it || "집" in it || "회사" in it })
    }

    @Test fun noPositionIsEverStored() {
        val h = Harness(base.copy(archiveWebhookUrl = "https://example.invalid/hook"), detailLogs = true)
        h.bookmarks()
        val home = GeoPoint(37.544411, 127.037422)
        val office = GeoPoint(37.394711, 127.111222)
        h.gps(home)
        h.odo(45_200.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        h.advance(20 * 60_000); h.gps(office); h.odo(45_218.0); h.gear(Gear.PARK)
        h.core.exportWorkbook(app.autobrief.car.domain.TaxPeriods.month(2026, 10), businessYear = false)
        assertEquals(TripPurpose.COMMUTE, h.core.trips.value.single().purpose)
        val stored = h.store.data.values.joinToString("\n") { d ->
            Json.encodeToString(ListSerializer(TripRecord.serializer()), d.trips) + d.ledger.joinToString { it.body } +
                d.outbox.joinToString { it.payloadJson } + d.settings.toString() + d.openTrip.toString()
        } + h.files.values.joinToString { it.toString(Charsets.ISO_8859_1) } + h.logs
        listOf("37.5444", "127.0374", "37.3947", "127.1112", "37.544411", "127.111222").forEach { assertFalse(it, it in stored) }
        assertTrue(h.store.data.getValue(Space.LIVE).settings.places.isEmpty())
    }
}
