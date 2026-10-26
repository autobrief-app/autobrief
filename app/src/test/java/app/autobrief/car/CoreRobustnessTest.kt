package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.Gear
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
