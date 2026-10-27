package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TaxRate
import app.autobrief.car.model.TripPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The core end to end on a fake clock: vehicle and navigation events in, rows, ledger, files and replies out. */
class CoreFlowTest {
    private val user = CarUser("김예시", "영업팀")
    private val base = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000", users = listOf(user))

    @Test fun homeToOfficeIsWrittenAsACommuteTheMomentTheCarParks() {
        val h = Harness(base)
        h.bookmarks()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.COMMUTE, row.purpose)
        assertEquals(ClassifiedBy.AUTO, row.classifiedBy)
        assertEquals("집→회사", row.autoReason)
        assertEquals(user, row.user)
        assertEquals(listOf(LedgerKind.TRIP, LedgerKind.CLASSIFY), h.core.ledgerFor(row.id).map { it.kind })
        assertTrue(h.core.integrity.value.ok)
        assertTrue(h.logs.any { it.startsWith("TRIP_CLOSED") && "start=45200.0 end=45218.0" in it && "purpose=COMMUTE" in it })
        assertEquals(row.id, h.core.lastClosedId.value)
    }

    @Test fun debugBuildsLogEveryPositionAsThePlaceLabelOnly() {
        val h = Harness(base.copy(places = listOf(SampleHistory.CLIENT)), detailLogs = true)
        h.bookmarks()
        h.at(SampleHistory.HOME.point)
        h.at(GeoPoint(37.4127, 127.0947)) // between places: no label
        h.at(SampleHistory.CLIENT.point)
        assertEquals(
            listOf("POSITION place=집", "POSITION place=-", "POSITION place=가상상사"),
            h.logs.filter { it.startsWith("POSITION") },
        )
        assertFalse("no coordinates in the log", h.logs.any { "37." in it || "127." in it })
    }

    @Test fun releaseLogsCarryNoPlaceNames() {
        val h = Harness(base.copy(places = listOf(SampleHistory.CLIENT)))
        h.bookmarks()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point) // 집→회사
        h.drive(45_218.0, 11.0, SampleHistory.OFFICE.point, SampleHistory.CLIENT.point) // 거래처 도착
        // arrival position after P: the rule runs later and logs TRIP_CLASSIFIED
        h.at(SampleHistory.CLIENT.point)
        h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE); h.advance(20 * 60_000); h.odo(45_240.0)
        h.gear(Gear.PARK)
        h.advance(2_000)
        h.at(SampleHistory.OFFICE.point) // 가상상사→회사
        val purposes = h.core.trips.value.map { it.purpose }
        assertEquals(listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.BUSINESS), purposes)
        val names = listOf("집", "회사", "가상상사", "우리집")
        val leaks = h.logs.filter { line -> names.any { it in line } }
        assertTrue("place names in release logs: $leaks", leaks.isEmpty())
        assertTrue(h.logs.none { it.startsWith("POSITION") })
        // the verdict fields stay: the end-to-end check reads purpose= on TRIP_CLOSED
        assertTrue(h.logs.toString(), h.logs.any { it.startsWith("TRIP_CLOSED") && "purpose=COMMUTE by=${ClassifiedBy.AUTO} reason=hidden" in it })
        assertTrue(h.logs.toString(), h.logs.any { it.startsWith("TRIP_CLASSIFIED") && "purpose=BUSINESS by=${ClassifiedBy.AUTO} reason=hidden" in it })
    }

    @Test fun arrivalPositionThatComesAfterParkingStillClassifies() {
        val h = Harness(base)
        h.bookmarks()
        h.at(SampleHistory.OFFICE.point)
        h.odo(100.0); h.gear(Gear.PARK); h.advance(60_000); h.gear(Gear.DRIVE)
        h.advance(30 * 60_000); h.odo(111.0)
        h.gear(Gear.PARK) // no fresh position yet: the row is written, the rule waits
        assertEquals(TripPurpose.UNCLASSIFIED, h.core.trips.value.single().purpose)
        assertTrue(h.locationRequests > 0)
        h.advance(2_000)
        h.at(SampleHistory.CLIENT.point)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.UNCLASSIFIED, row.purpose) // 회사→가상상사 needs the client registered in the app
        assertEquals("회사", row.startPlace)
        // with the client registered, the same arrival is business
        val h2 = Harness(base.copy(places = listOf(SampleHistory.CLIENT)))
        h2.bookmarks()
        h2.at(SampleHistory.OFFICE.point)
        h2.odo(100.0); h2.gear(Gear.PARK); h2.advance(60_000); h2.gear(Gear.DRIVE); h2.advance(30 * 60_000); h2.odo(111.0)
        h2.gear(Gear.PARK)
        h2.advance(2_000)
        h2.at(SampleHistory.CLIENT.point)
        assertEquals(TripPurpose.BUSINESS, h2.core.trips.value.single().purpose)
        assertTrue(h2.core.integrity.value.ok)
    }

    @Test fun withoutAnyArrivalPositionTheRuleTimesOutAndTheDriverIsAsked() {
        val h = Harness(base)
        h.bookmarks()
        h.at(SampleHistory.HOME.point)
        h.odo(0.0); h.gear(Gear.PARK); h.advance(1_000); h.gear(Gear.DRIVE); h.advance(40 * 60_000); h.odo(18.0)
        h.gear(Gear.PARK)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1)
        h.core.tick()
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.UNCLASSIFIED, row.purpose)
        assertEquals("집", row.startPlace)
        assertEquals(row.id, h.core.lastClosedId.value)
    }

    @Test fun correctingAPurposeNeedsAReasonAndLeavesAnAmendEntry() {
        val h = Harness(base)
        h.bookmarks()
        h.drive(0.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        val id = h.core.trips.value.single().id
        assertEquals(AutoBriefCore.ClassifyResult.REASON_REQUIRED, h.core.classifyTrip(id, TripPurpose.PERSONAL, null, "", ClassifiedBy.SCREEN))
        assertEquals(AutoBriefCore.ClassifyResult.OK, h.core.classifyTrip(id, TripPurpose.PERSONAL, null, "병원", ClassifiedBy.SCREEN, "개인 용무를 겸했어요"))
        val row = h.core.trips.value.single()
        assertTrue(row.amended)
        assertEquals(LedgerKind.AMEND, h.core.ledgerFor(id).last().kind)
        assertTrue(h.core.integrity.value.ok)
    }

    @Test fun voiceLabelsOnlyTheTripThatJustEnded() {
        val h = Harness(base)
        h.drive(0.0, 12.0, null, null)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1); h.core.tick()
        val reply = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"BUSINESS","memo":"가상고객사 미팅"}"""))
        assertEquals("success", reply.status)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.BUSINESS, row.purpose)
        assertEquals(ClassifiedBy.VOICE, row.classifiedBy)
        assertEquals("가상고객사 미팅", row.memo)
        h.advance(AutoBriefCore.VOICE_CLASSIFY_WINDOW_MS + 1)
        assertEquals("fail", h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"PERSONAL"}""")).status)
        assertEquals("asking", h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("{}")).status)
    }

    @Test fun voiceCannotRewriteARecordedPurpose() {
        val h = Harness(base)
        h.bookmarks()
        h.drive(0.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        val reply = h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"PERSONAL"}"""))
        assertEquals("fail", reply.status)
        val row = h.core.trips.value.single()
        assertEquals(TripPurpose.COMMUTE, row.purpose)
        assertEquals(listOf(LedgerKind.TRIP, LedgerKind.CLASSIFY), h.core.ledgerFor(row.id).map { it.kind })
    }

    @Test fun voiceSendIsRateLimited() {
        val h = Harness(base)
        h.drive(0.0, 12.0, null, null)
        assertEquals("success", h.core.gleo(GleoContract.SEND_LOGBOOK, GleoContract.parseParams("{}")).status)
        assertEquals("fail", h.core.gleo(GleoContract.SEND_LOGBOOK, GleoContract.parseParams("{}")).status)
        h.advance(AutoBriefCore.SEND_COOLDOWN_MS + 1)
        assertEquals("success", h.core.gleo(GleoContract.SEND_LOGBOOK, GleoContract.parseParams("{}")).status)
    }

    @Test fun wrongAdminPinsLockEntryAndThePersistedCounterSurvivesARestart() {
        val h = Harness(base)
        h.core.saveSettings(h.core.settings.value, "4321", null)
        repeat(4) { assertEquals(AutoBriefCore.SettingsResult.PIN_REQUIRED, h.core.setDemoMode(true, "0000")) }
        assertEquals(AutoBriefCore.SettingsResult.PIN_LOCKED, h.core.setDemoMode(true, "0000"))
        // even the right PIN is refused while locked, and a fresh core on the same storage stays locked
        assertEquals(AutoBriefCore.SettingsResult.PIN_LOCKED, h.core.setDemoMode(true, "4321"))
        val again = AutoBriefCore(h.store, clock = { h.now })
        assertEquals(AutoBriefCore.SettingsResult.PIN_LOCKED, again.setDemoMode(true, "4321"))
        h.advance(AutoBriefCore.PIN_LOCK_MS + 1)
        assertEquals(AutoBriefCore.SettingsResult.SAVED, again.setDemoMode(true, "4321"))
    }

    @Test fun statusAndSendRepliesAreEnglishAndCarryNoPlaces() {
        val h = Harness(base.copy(archiveWebhookUrl = "https://example.invalid/hook"))
        h.bookmarks()
        h.drive(0.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        h.drive(18.0, 10.0, null, null)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1); h.core.tick()
        val status = h.core.gleo(GleoContract.GET_LOGBOOK_STATUS, GleoContract.parseParams("{}"))
        assertEquals("This month: 2 trips, 28 km, business use 64.3 percent. 1 trips still need a purpose.", status.message)
        val send = h.core.gleo(GleoContract.SEND_LOGBOOK, GleoContract.parseParams("""{"period":"THIS_MONTH"}"""))
        assertEquals("success", send.status)
        assertTrue(h.files.keys.any { it.startsWith("live/운행기록부_2026-10-01_2026-10-31") && it.endsWith(".xlsx") })
        val archive = h.core.outbox.value.single { it.kind == "archive" }
        assertTrue("\"verificationCode\"" in archive.payloadJson)
        listOf(status.message, send.message).forEach { assertFalse(it.contains("집") || it.contains("회사")) }
        // every ledger entry was queued for the company endpoint, in order
        val ledgerItems = h.core.outbox.value.filter { it.kind == "ledger" }
        assertEquals(h.core.integrity.value.entryCount, ledgerItems.size)
    }

    @Test fun firstWebhookAddressBackfillsTheWholeLedger() {
        val h = Harness(base)
        h.bookmarks()
        h.drive(0.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        assertTrue(h.core.outbox.value.isEmpty())
        assertEquals(AutoBriefCore.SettingsResult.INVALID_URL, h.core.saveSettings(h.core.settings.value.copy(archiveWebhookUrl = "http://example.com/hook"), null, null))
        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.saveSettings(h.core.settings.value.copy(archiveWebhookUrl = "http://10.0.2.2:8787/hook"), null, null))
        val chain = h.core.ledgerChainId // delivery keys name the ledger's chain (a demo reset starts a new one)
        assertEquals(listOf("ledger-live-$chain-1", "ledger-live-$chain-2"), h.core.outbox.value.map { it.key })
    }

    @Test fun adminPinGuardsAddressPinAndDemoSwitch() {
        val h = Harness(base)
        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.saveSettings(h.core.settings.value, "4321", null))
        assertTrue(h.core.adminPinSet)
        assertEquals(AutoBriefCore.SettingsResult.PIN_REQUIRED, h.core.saveSettings(h.core.settings.value.copy(archiveWebhookUrl = "https://a.invalid/x"), null, "0000"))
        assertEquals(AutoBriefCore.SettingsResult.PIN_REQUIRED, h.core.setDemoMode(true, null))
        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.setDemoMode(true, "4321"))
        assertEquals(Space.DEMO, h.core.activeSpace.value)
        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.setDemoMode(false, null)) // leaving never needs the PIN
    }

    @Test fun demoSpaceNeverTouchesTheRealLogbookAndTheDistanceShowsUpAsAGap() {
        val h = Harness(base, startAt = Harness.at(LocalDate.of(2026, 12, 3), 9, 0))
        h.drive(45_000.0, 10.0, null, null)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1); h.core.tick()
        val liveBefore = h.store.data.getValue(Space.LIVE).trips
        assertEquals(1, liveBefore.size)

        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.setDemoMode(true, null))
        val n = h.core.loadSampleHistory()
        assertTrue(n > 500)
        assertTrue(h.logs.any { it == "DEMO_READY trips=$n" })
        assertTrue(h.core.integrity.value.ok)
        assertEquals(SampleHistory.END_ODOMETER_KM.toDouble(), h.core.trips.value.last().endOdometerKm, 0.0)
        // demo drive (the car really moves on the console)
        h.bookmarks()
        h.drive(45_200.0, 18.0, SampleHistory.HOME.point, SampleHistory.OFFICE.point)
        assertEquals(TripPurpose.COMMUTE, h.core.trips.value.last().purpose)
        assertEquals(liveBefore, h.store.data.getValue(Space.LIVE).trips)

        assertEquals(AutoBriefCore.SettingsResult.SAVED, h.core.setDemoMode(false, null))
        assertEquals(1, h.core.trips.value.size)
        // next real drive: the 208 km the odometer moved meanwhile is recorded as a non-business gap first
        h.advance(60_000)
        h.gear(Gear.DRIVE)
        val gap = h.core.trips.value.single { it.purpose == TripPurpose.UNLOGGED_GAP }
        assertEquals(45_010.0, gap.startOdometerKm, 0.0)
        assertEquals(45_218.0, gap.endOdometerKm, 0.0)
        assertTrue(h.core.integrity.value.ok)
    }

    @Test fun taxMeterTakesTheRateTableOfTheBusinessYearsStart() {
        val costs = base.copy(annualCarCostKrw = 19_000_000, annualDepreciationKrw = 11_500_000, taxRate = TaxRate.CORP_22, fiscalYearStartMonth = 7)
        // 2026-03-10 lies in the business year 2025-07-01 ~ 2026-06-30, which began before 2026: 20.9%
        val h = Harness(costs, startAt = Harness.at(LocalDate.of(2026, 3, 10), 9, 0))
        h.drive(0.0, 10.0, null, null)
        assertEquals(20.9, h.core.taxBenefit()!!.ratePct, 0.0)
        // from 2026-07-01 the business year starts in 2026: 22.0%
        h.now = Harness.at(LocalDate.of(2026, 7, 10), 9, 0)
        h.drive(10.0, 10.0, null, null)
        assertEquals(22.0, h.core.taxBenefit()!!.ratePct, 0.0)
    }

    @Test fun demoSwitchIsRefusedMidTrip() {
        val h = Harness(base)
        h.odo(10.0); h.gear(Gear.PARK); h.gear(Gear.DRIVE)
        assertEquals(AutoBriefCore.SettingsResult.TRIP_IN_PROGRESS, h.core.setDemoMode(true, null))
    }

    @Test fun firstEventOfANewMonthClosesThePreviousMonthOnce() {
        val h = Harness(base, startAt = Harness.at(LocalDate.of(2026, 10, 30), 9, 0))
        h.drive(0.0, 20.0, null, null)
        h.advance(AutoBriefCore.AUTO_WAIT_MS + 1); h.core.tick()
        assertNull(h.core.notice.value) // October closes in November, not now
        h.now = Harness.at(LocalDate.of(2026, 11, 2), 8, 0)
        h.core.tick()
        val notice = h.core.notice.value
        assertNotNull(notice)
        assertTrue(notice!!.text.startsWith("10월 운행기록부를 만들었습니다: 1건"))
        assertTrue(h.files.keys.any { "운행기록부_2026-10-01_2026-10-31.xlsx" in it })
        assertEquals("2026-10", h.core.settings.value.lastMonthlyCloseYm)
        val written = h.files.size
        h.core.dismissNotice(); h.core.tick()
        assertEquals(written, h.files.size)
        assertNull(h.core.notice.value)
    }

    @Test fun placeRegistrationUsesTheCurrentNavigationPosition() {
        val h = Harness(base)
        assertEquals(AutoBriefCore.PlaceResult.NO_LOCATION, h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT))
        h.at(SampleHistory.CLIENT.point)
        assertEquals(AutoBriefCore.PlaceResult.SAVED, h.core.registerPlaceHere("가상상사", PlaceKind.CLIENT))
        assertEquals(AutoBriefCore.PlaceResult.INVALID, h.core.registerPlaceHere("  ", PlaceKind.CLIENT))
        assertEquals(listOf("가상상사"), h.core.settings.value.places.map { it.label })
        assertTrue(h.core.allPlaces().any { it.label == "가상상사" })
    }

    /** The demo backfill queues ~1,000 ledger entries: one outbox write, not one per entry (that took minutes on the emulator). */
    @Test fun backfillWritesTheOutboxOnce() {
        val h = Harness()
        check(h.core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED)
        val before = h.store.outboxSaves
        h.core.loadSampleHistory("http://10.0.2.2:8787/hook")
        assertEquals(h.core.integrity.value.entryCount, h.core.outbox.value.size)
        assertTrue("outbox written ${h.store.outboxSaves - before} times", h.store.outboxSaves - before <= 1)
    }

    @Test fun deliveryResultsAreAppliedWithOneWritePerBatch() {
        val h = Harness()
        h.core.setDemoMode(true, null)
        h.core.loadSampleHistory("http://10.0.2.2:8787/hook")
        val keys = h.core.outbox.value.map { it.key }
        val before = h.store.outboxSaves
        h.core.markDeliveries(keys.take(50).map { AutoBriefCore.Delivery(it, true, null) } + AutoBriefCore.Delivery(keys[50], false, "HTTP 503"))
        assertEquals(1, h.store.outboxSaves - before)
        val byKey = h.core.outbox.value.associateBy { it.key }
        assertTrue(keys.take(50).all { byKey.getValue(it).deliveredEpochMs != null })
        assertNull(byKey.getValue(keys[50]).deliveredEpochMs)
        assertEquals("HTTP 503", byKey.getValue(keys[50]).lastError)
        assertEquals(1, byKey.getValue(keys[50]).attempts)
    }
}
