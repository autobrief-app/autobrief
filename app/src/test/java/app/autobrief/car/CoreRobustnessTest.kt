package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.NaviBookmark
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The core under the conditions a real car and a live demo produce: shuffled gears, restarts, odd sensor values. */
class CoreRobustnessTest {
    private val user = CarUser("김예시", "영업팀")
    private val base = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000", users = listOf(user))

    @Test fun gearShuffleWithoutMovingLeavesNoOpenTripAndKeepsTheLastTripCard() {
        val h = Harness(base)
        h.drive(100.0, 12.0, null, null)
        val id = h.core.trips.value.single().id
        assertEquals(id, h.core.lastClosedId.value)
        h.advance(60_000)
        h.gear(Gear.DRIVE) // D, P again without moving: not a trip
        assertNotNull(h.core.openTrip.value)
        h.advance(10_000)
        h.gear(Gear.PARK)
        assertNull("Home must not say a trip is being recorded while parked", h.core.openTrip.value)
        assertEquals("the last trip's card stays", id, h.core.lastClosedId.value)
        assertEquals(1, h.core.trips.value.size)
    }

    @Test fun theNavigationDestinationIsNeverStored() {
        val h = Harness(base)
        h.core.onNavi(NaviEvent.RouteStarted("비밀동 123번지 가상병원", 1_800))
        h.drive(100.0, 12.0, null, null)
        h.core.onNavi(NaviEvent.Destination("비밀동 123번지 가상병원", 0, 0, 1_800))
        val stored = h.store.data.getValue(Space.LIVE)
        val text = Json.encodeToString(ListSerializer(TripRecord.serializer()), stored.trips) +
            stored.ledger.joinToString { it.body } + stored.outbox.joinToString { it.payloadJson }
        assertFalse("the destination text is kept nowhere", text.contains("비밀동"))
        assertEquals("the toll estimate still is", 1_800, stored.trips.single().routeTollEstimateKrw)
    }

    /** The app's process is replaced while the car keeps going: a new core on the same storage, events from scratch. */
    private fun restart(h: Harness): AutoBriefCore = AutoBriefCore(h.store, clock = { h.now }, log = { h.logs += it })

    @Test fun anOpenTripSurvivesARestartAndClosesAtTheNextPark() {
        val h = Harness(base)
        h.bookmarks()
        h.at(SampleHistory.HOME.point)
        h.odo(45_200.0); h.gear(Gear.PARK); h.advance(60_000)
        h.core.onNavi(NaviEvent.RouteStarted(null, 2_400))
        h.gear(Gear.DRIVE)
        val started = h.core.openTrip.value!!
        assertEquals("집", started.startPlace)
        h.advance(10 * 60_000)

        val again = restart(h)
        assertEquals("the trip shows as open right after the restart", started, again.openTrip.value)
        again.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE)) // still driving
        again.onVehicle(VehicleEvent.Odometer(45_210.0))
        assertTrue(again.trips.value.isEmpty())
        h.advance(20 * 60_000)
        // navigation's bookmarks arrive again some time after the restart
        again.onNavi(
            NaviEvent.Bookmarks(
                listOf(NaviBookmark(PlaceKind.HOME, "우리집", SampleHistory.HOME.point), NaviBookmark(PlaceKind.OFFICE, "회사", SampleHistory.OFFICE.point)),
            ),
        )
        again.onVehicle(VehicleEvent.Odometer(45_218.0))
        again.onNavi(NaviEvent.Location(SampleHistory.OFFICE.point))
        again.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        val row = again.trips.value.single()
        assertEquals(started.startEpochMs, row.startEpochMs)
        assertEquals(45_200.0, row.startOdometerKm, 0.0)
        assertEquals(45_218.0, row.endOdometerKm, 0.0)
        assertEquals(2_400, row.routeTollEstimateKrw)
        assertEquals("the start place survived as its label: 집→회사", TripPurpose.COMMUTE, row.purpose)
        assertTrue(again.integrity.value.ok)
        assertNull(again.openTrip.value)
        assertNull(h.store.data.getValue(Space.LIVE).openTrip)
    }

    @Test fun aRestartFoundInParkClosesTheOpenTripAtOnce() {
        for (gearFirst in listOf(true, false)) {
            val h = Harness(base)
            h.odo(100.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
            h.advance(30 * 60_000)
            val again = restart(h)
            if (gearFirst) {
                again.onVehicle(VehicleEvent.GearChanged(Gear.PARK)) // the odometer is not known yet
                again.onVehicle(VehicleEvent.Odometer(109.0))
            } else {
                again.onVehicle(VehicleEvent.Odometer(109.0))
                again.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
            }
            val row = again.trips.value.single()
            assertEquals(100.0, row.startOdometerKm, 0.0)
            assertEquals(109.0, row.endOdometerKm, 0.0)
            assertNull(again.openTrip.value)
            assertTrue(again.integrity.value.ok)
        }
    }

    /** Stores everything, except that once armed the trips save never happens: the app dying between the two writes. */
    private class DiesBeforeTheTripsSave(private val inner: MemoryStore) : AutoBriefCore.Store by inner {
        var armed = false
        override fun saveTrips(space: Space, trips: List<TripRecord>) {
            check(!armed) { "the app died here" }
            inner.saveTrips(space, trips)
        }
    }

    @Test fun aTripInTheLedgerButNotInTheTripsFileIsRebuiltAtStart() {
        val h = Harness(base)
        val dying = DiesBeforeTheTripsSave(h.store)
        val c = AutoBriefCore(dying, clock = { h.now })
        c.onVehicle(VehicleEvent.Odometer(100.0)); c.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        h.advance(60_000); c.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        h.advance(30 * 60_000); c.onVehicle(VehicleEvent.Odometer(112.0))
        dying.armed = true
        assertThrows(IllegalStateException::class.java) { c.onVehicle(VehicleEvent.GearChanged(Gear.PARK)) }
        val stored = h.store.data.getValue(Space.LIVE)
        assertTrue("the row never reached the trips file", stored.trips.isEmpty())
        assertEquals(listOf(LedgerKind.TRIP), stored.ledger.map { it.kind })

        val again = AutoBriefCore(h.store, clock = { h.now })
        val row = again.trips.value.single()
        assertEquals(100.0, row.startOdometerKm, 0.0)
        assertEquals(112.0, row.endOdometerKm, 0.0)
        assertTrue(again.integrity.value.problems.toString(), again.integrity.value.ok)
        assertEquals("written back", listOf(row), h.store.data.getValue(Space.LIVE).trips)
    }

    @Test fun aPurposeInTheLedgerButNotInTheTripsFileIsRestoredAtStart() {
        val h = Harness(base)
        h.drive(100.0, 12.0, null, null)
        val id = h.core.trips.value.single().id
        val dying = DiesBeforeTheTripsSave(h.store)
        val c = AutoBriefCore(dying, clock = { h.now })
        dying.armed = true
        assertThrows(IllegalStateException::class.java) { c.classifyTrip(id, TripPurpose.BUSINESS, user, "가상고객사 미팅", ClassifiedBy.SCREEN) }
        assertEquals(TripPurpose.UNCLASSIFIED, h.store.data.getValue(Space.LIVE).trips.single().purpose)
        val again = AutoBriefCore(h.store, clock = { h.now })
        val row = again.trips.value.single()
        assertEquals(TripPurpose.BUSINESS, row.purpose)
        assertEquals("가상고객사 미팅", row.memo)
        assertEquals(ClassifiedBy.SCREEN, row.classifiedBy)
        assertTrue(again.integrity.value.ok)
        // an amendment lost the same way comes back too
        dying.armed = false
        val c2 = AutoBriefCore(dying, clock = { h.now })
        dying.armed = true
        assertThrows(IllegalStateException::class.java) { c2.classifyTrip(id, TripPurpose.PERSONAL, user, "", ClassifiedBy.SCREEN, "개인 용무였습니다") }
        val third = AutoBriefCore(h.store, clock = { h.now })
        assertEquals(TripPurpose.PERSONAL, third.trips.value.single().purpose)
        assertTrue(third.trips.value.single().amended)
        assertTrue(third.integrity.value.ok)
    }

    @Test fun aRowEditedInTheFileIsNotHealedButReported() {
        val h = Harness(base)
        h.drive(100.0, 12.0, null, null)
        val row = h.core.trips.value.single()
        h.core.classifyTrip(row.id, TripPurpose.PERSONAL, user, "", ClassifiedBy.SCREEN)
        // someone turns the personal trip into a business one in the stored file: no ledger state ever said so
        val data = h.store.data.getValue(Space.LIVE)
        h.store.data[Space.LIVE] = data.copy(trips = data.trips.map { it.copy(purpose = TripPurpose.BUSINESS) })
        val again = AutoBriefCore(h.store, clock = { h.now })
        assertEquals(TripPurpose.BUSINESS, again.trips.value.single().purpose)
        assertFalse(again.integrity.value.ok)
    }

    @Test fun aTripThatClosedJustBeforeACrashIsNotClosedTwice() {
        val h = Harness(base)
        h.odo(100.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        val leftOpen = h.store.data.getValue(Space.LIVE).openTrip
        assertNotNull("persisted when it opened", leftOpen)
        h.advance(30 * 60_000); h.odo(112.0); h.gear(Gear.PARK)
        // the process died after the row was written but before the open trip was cleared
        h.store.data[Space.LIVE] = h.store.data.getValue(Space.LIVE).copy(openTrip = leftOpen)
        val again = restart(h)
        assertNull(again.openTrip.value)
        again.onVehicle(VehicleEvent.Odometer(112.0))
        again.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertEquals(1, again.trips.value.size)
        assertTrue(again.integrity.value.ok)
    }
}
