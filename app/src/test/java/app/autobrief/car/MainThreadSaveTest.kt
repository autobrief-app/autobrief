package app.autobrief.car

import android.os.Looper
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.LedgerEntry
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * A tap that sets or corrects a trip's purpose, saves the settings or answers Gleo returns at once on a head unit that
 * writes its storage slowly. In rehearsal 40486795890 the 비업무 tap and the 정정 저장 tap each rewrote the ledger, the
 * outbox and the trips file on the main thread (about 7 s on a loaded two-core emulator) and Android closed the app
 * twice. Here every save takes [SlowSaves.SAVE_MS] once the trip is recorded: the call, made on the main thread with the
 * app's own threading, returns within [CALL_BUDGET_MS], shows its result at once, and the files are written afterwards,
 * never on the main thread, so that a restart reads the same logbook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainThreadSaveTest {
    @get:Rule val stalePleosBinds = RefuseStalePleosBinds()

    private val background = AutoBriefCore.Background.android()
    private val store = SlowSaves()
    private lateinit var h: Harness
    private lateinit var tripId: String

    @Before fun recordATrip() {
        assertTrue("runs on the main thread", Looper.myLooper() == Looper.getMainLooper())
        h = Harness(settings = AppSettings(archiveWebhookUrl = HOOK), background = background, store = store)
        h.drive(1_000.0, 12.0, null, null)
        tripId = h.core.trips.value.single().id
        assertTrue("the recorded trip written", h.core.flushWrites(10_000))
        store.slow = true
    }

    @After fun stop() {
        store.slow = false
        background.scope.cancel()
    }

    @Test fun thePurposeTapReturnsAtOnceAndIsWrittenAfterwards() {
        val (result, ms) = timed { h.core.classifyTrip(tripId, TripPurpose.PERSONAL, null, "", ClassifiedBy.SCREEN) }
        assertEquals(AutoBriefCore.ClassifyResult.OK, result)
        assertTrue("비업무 took $ms ms on the main thread", ms < CALL_BUDGET_MS)
        assertEquals("shown at once", TripPurpose.PERSONAL, h.core.trips.value.single().purpose)
        assertEquals(LedgerKind.CLASSIFY, h.core.ledgerEntries.last().kind)
        waitFor("the purpose written") { store.stored(Space.LIVE).trips.single().purpose == TripPurpose.PERSONAL && store.stored(Space.LIVE).outbox.size == 2 }
        assertNoSaveOnTheMainThread()
        restartReads(TripPurpose.PERSONAL, amended = false, entries = 2)
    }

    @Test fun theCorrectionWithAReasonReturnsAtOnceAndIsWrittenAfterwards() {
        store.slow = false
        h.core.classifyTrip(tripId, TripPurpose.PERSONAL, null, "", ClassifiedBy.SCREEN)
        waitFor("the first purpose written") { store.stored(Space.LIVE).trips.single().purpose == TripPurpose.PERSONAL }
        store.slow = true
        val (result, ms) = timed { h.core.classifyTrip(tripId, TripPurpose.BUSINESS, null, "", ClassifiedBy.SCREEN, reason = "거래처 방문") }
        assertEquals(AutoBriefCore.ClassifyResult.OK, result)
        assertTrue("정정 저장 took $ms ms on the main thread", ms < CALL_BUDGET_MS)
        assertTrue("shown at once", h.core.trips.value.single().let { it.purpose == TripPurpose.BUSINESS && it.amended })
        waitFor("the correction written") { store.stored(Space.LIVE).trips.single().amended && store.stored(Space.LIVE).ledger.size == 3 }
        assertNoSaveOnTheMainThread()
        restartReads(TripPurpose.BUSINESS, amended = true, entries = 3)
    }

    @Test fun aGleoPurposeRequestReturnsAtOnce() {
        val (reply, ms) = timed { h.core.gleo(GleoContract.LOG_TRIP_PURPOSE, GleoContract.parseParams("""{"purpose":"PERSONAL"}""")) }
        assertEquals(reply.message, "success", reply.status)
        assertTrue("LOG_TRIP_PURPOSE took $ms ms on the main thread", ms < CALL_BUDGET_MS)
        waitFor("the purpose written") { store.stored(Space.LIVE).trips.single().purpose == TripPurpose.PERSONAL }
        assertNoSaveOnTheMainThread()
        restartReads(TripPurpose.PERSONAL, amended = false, entries = 2)
    }

    @Test fun savingTheSettingsReturnsAtOnce() {
        val (result, ms) = timed { h.core.saveSettings(h.core.settings.value.copy(entityName = "가상 상사"), null, null) }
        assertEquals(AutoBriefCore.SettingsResult.SAVED, result)
        assertTrue("저장 took $ms ms on the main thread", ms < CALL_BUDGET_MS)
        assertEquals("가상 상사", h.core.settings.value.entityName)
        waitFor("the settings written") { store.stored(Space.LIVE).settings.entityName == "가상 상사" }
        assertNoSaveOnTheMainThread()
    }

    @Test fun switchingToTheDemoSpaceAndLoadingTheSampleKeepTheMainThreadFree() {
        var result: AutoBriefCore.SettingsResult? = null
        var rows = 0
        // as 가상 예시로 둘러보기 does: started on the core's scope from the main thread, finished by messages posted to it
        val (_, ms) = timed { h.core.launch { result = setDemoMode(true, null); rows = loadSampleHistory() } }
        assertTrue("시연 모드 took $ms ms on the main thread", ms < CALL_BUDGET_MS)
        val longest = runMainLooperUntil("the sample in use", 120_000) { rows > 0 }
        assertTrue("a main-thread message took $longest ms", longest < CALL_BUDGET_MS)
        assertEquals(AutoBriefCore.SettingsResult.SAVED, result)
        assertTrue(rows > 400)
        assertEquals(Space.DEMO, h.core.activeSpace.value)
        assertNoSaveOnTheMainThread()
        store.slow = false
        val again = AutoBriefCore(store)
        assertEquals("a restart opens the demo space", Space.DEMO, again.activeSpace.value)
        assertEquals(rows, again.trips.value.size)
        assertTrue(again.integrity.value.problems.toString(), again.integrity.value.ok)
    }

    private fun assertNoSaveOnTheMainThread() {
        val main = Looper.getMainLooper().thread.name
        assertTrue("saved on the main thread: ${store.calls}", store.calls.isNotEmpty() && store.calls.none { it.endsWith("@$main") })
    }

    /** A new core over the same store (the app started again) reads the purpose, and the chain checks out. */
    private fun restartReads(purpose: TripPurpose, amended: Boolean, entries: Int) {
        store.slow = false
        val again = AutoBriefCore(store)
        val row = again.trips.value.single()
        assertEquals(purpose, row.purpose)
        assertEquals(amended, row.amended)
        assertEquals(entries, again.ledgerEntries.size)
        assertTrue(again.integrity.value.problems.toString(), again.integrity.value.ok)
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - started) / 1_000_000
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 60_000L * 1_000_000
        while (!check()) {
            if (System.nanoTime() > until) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    companion object {
        /** Well under Android's 5 s input limit, and far under one slow save. */
        const val CALL_BUDGET_MS = 200L
        const val HOOK = "https://receiver.example/hook"
    }
}

/**
 * [MemoryStore] whose saves and reads of a space take [SAVE_MS] each once [slow] is on: a whole-file rewrite of a year of
 * trips on a loaded head unit. Each slow call is recorded with its thread, in the order the calls ran.
 */
class SlowSaves : MemoryStore() {
    @Volatile var slow = false

    /** "<what>@<thread>" for every save or read made while [slow], in order. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun took(what: String) {
        if (!slow) return
        calls += "$what@${Thread.currentThread().name.substringBefore(" @")}"
        Thread.sleep(SAVE_MS)
    }

    /** What is stored now, read without the delay: the test's own look, not the app's. */
    fun stored(space: Space): AutoBriefCore.SpaceData = super.load(space)

    override fun setActiveSpace(space: Space) { took("space"); super.setActiveSpace(space) }
    override fun load(space: Space): AutoBriefCore.SpaceData { took("load"); return super.load(space) }
    override fun saveSettings(space: Space, settings: AppSettings) { took("settings"); super.saveSettings(space, settings) }
    override fun saveTrips(space: Space, trips: List<TripRecord>) { took("trips"); super.saveTrips(space, trips) }
    override fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) { took("ledger"); super.saveLedger(space, chainId, entries) }
    override fun saveOutbox(space: Space, items: List<OutboxItem>) { took("outbox"); super.saveOutbox(space, items) }
    override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) { took("open"); super.saveOpenTrip(space, trip) }

    companion object {
        const val SAVE_MS = 1_500L
    }
}
