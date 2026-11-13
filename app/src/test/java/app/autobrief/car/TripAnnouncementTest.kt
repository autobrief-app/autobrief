package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.TripAnnouncement
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the car speaks when a parked trip's purpose is settled: once per trip, with the purpose the logbook wrote,
 * the distance the logbook prints, and never a place name.
 */
class TripAnnouncementTest {
    private val user = CarUser("김기현", "영업팀")
    private val base = AppSettings(entityName = "한성물산 주식회사", plateNumber = "00가 0000", users = listOf(user))

    private fun harness(): Pair<Harness, MutableList<String>> {
        val h = Harness(base)
        val said = mutableListOf<String>()
        h.core.speechControl = AutoBriefCore.SpeechControl { said += it }
        h.bookmarks()
        return h to said
    }

    @Test fun anAutomaticCommuteIsSpokenWithItsPurposeAndDistance() {
        val (h, said) = harness()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        assertEquals(TripPurpose.COMMUTE, h.core.trips.value.single().purpose)
        assertEquals(listOf("출퇴근으로 기록했습니다. 18킬로미터입니다."), said)
    }

    @Test fun aTripToAnUnknownPlaceAsksForThePurposeAndIsNotCalledBusiness() {
        val (h, said) = harness()
        h.drive(45_200.0, 7.4, SampleHistory.OFFICE.point, GeoPoint(37.5665, 126.9780))
        assertEquals(TripPurpose.UNCLASSIFIED, h.core.trips.value.single().purpose)
        assertEquals(listOf("7.4킬로미터 운행했습니다. 목적을 골라 주세요."), said)
    }

    @Test fun aFamilyDriveIsSpokenAsPersonalWithoutAnyPlace() {
        val (h, said) = harness()
        h.odo(45_200.0); h.gear(Gear.PARK)
        h.core.startFamilyDrive()
        h.advance(60_000); h.gear(Gear.DRIVE); h.advance(20 * 60_000); h.odo(45_212.0); h.gear(Gear.PARK)
        assertTrue(h.core.trips.value.single().family)
        assertEquals(listOf("가족 운행을 비업무로 기록했습니다. 12킬로미터이고, 위치는 읽지 않았습니다."), said)
    }

    @Test fun noLineEverNamesAPlace() {
        val (h, said) = harness()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        h.drive(45_218.0, 11.0, SampleHistory.OFFICE.point, SampleHistory.CLIENT.point)
        assertEquals(2, said.size)
        val labels = h.core.trips.value.flatMap { listOfNotNull(it.startPlace, it.endPlace) }.toSet()
        assertTrue("the trips do carry place labels", labels.isNotEmpty())
        labels.forEach { label -> assertFalse(label, said.any { label in it }) }
    }

    @Test fun anUnloggedGapIsNotAnnounced() {
        val gap = TripRecord("GAP-1", 1_000, 2_000, 45_000.0, 45_010.0, TripPurpose.UNLOGGED_GAP, null, "")
        assertNull(TripAnnouncement.text(gap))
    }

    @Test fun withoutASpeakerTheLogbookWorksAsBefore() {
        val h = Harness(base)
        h.bookmarks()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        assertEquals(TripPurpose.COMMUTE, h.core.trips.value.single().purpose)
    }
}
