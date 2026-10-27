package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.LedgerEntry
import app.autobrief.car.domain.StoreWriter
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The order the logbook's files are written in ([StoreWriter]): saves waiting together are written once each, ledger
 * first; a task runs between what was handed over before and after it; a failed write holds everything behind it; and
 * what the core builds on that: an entry goes out only once it is written, a kill between two files restarts into an
 * intact chain, and a switch to a space gets the changes made to its stored queue meanwhile.
 */
class OrderedWritesTest {
    private fun thread(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    private val coreThread = thread("core")
    private val storeThread = thread("store")
    private val scope = CoroutineScope(SupervisorJob() + coreThread.asCoroutineDispatcher())
    private val background = AutoBriefCore.Background(scope, Dispatchers.Unconfined, Dispatchers.Unconfined, storeThread)
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val stores = mutableListOf<Recording>()

    @After fun stop() {
        stores.forEach { it.letGo() }
        scope.cancel()
        listOf(coreThread, storeThread).forEach { it.shutdownNow() }
    }

    private fun <T> onCore(block: () -> T): T = coreThread.submit<T>(block).get(20, TimeUnit.SECONDS)

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!check()) {
            if (System.nanoTime() > until) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun recording() = Recording().also { stores += it }

    // ------------------------------------------------------------------ the writer

    @Test fun savesWaitingTogetherAreWrittenOnceEachLedgerFirst() {
        val store = recording()
        val w = StoreWriter(store, storeThread, logs::add)
        val release = store.holdNext()
        w.saveSettings(Space.LIVE, AppSettings(entityName = "A"))
        waitFor("the first write held") { store.holding }
        w.saveTrips(Space.LIVE, trips(1))
        w.saveOutbox(Space.LIVE, items(1))
        w.saveLedger(Space.LIVE, "c", entries(1))
        w.saveTrips(Space.LIVE, trips(2))
        w.saveLedger(Space.LIVE, "c", entries(2))
        w.saveOpenTrip(Space.LIVE, null)
        w.saveSettings(Space.LIVE, AppSettings(entityName = "B"))
        assertTrue("every save returned while the writer was busy", store.holding && store.writes.isEmpty())
        release.countDown()
        assertTrue(w.flush(10_000))
        assertEquals(
            listOf("settings LIVE A", "ledger LIVE 2", "trips LIVE 2", "outbox LIVE 1", "open LIVE none", "settings LIVE B"),
            store.writes.toList(),
        )
    }

    @Test fun aTaskRunsAfterWhatWasHandedOverBeforeItAndBeforeWhatCameAfter() {
        val store = recording()
        val w = StoreWriter(store, storeThread, logs::add)
        val release = store.holdNext()
        w.saveSettings(Space.LIVE, AppSettings(entityName = "A"))
        waitFor("the first write held") { store.holding }
        w.saveTrips(Space.LIVE, trips(1))
        val read = w.read { it.load(Space.LIVE).trips.size }
        w.saveTrips(Space.LIVE, trips(2))
        release.countDown()
        assertEquals(1, kotlinx.coroutines.runBlocking { read.await() })
        assertTrue(w.flush(10_000))
        assertEquals(listOf("settings LIVE A", "trips LIVE 1", "trips LIVE 2"), store.writes.toList())
    }

    @Test fun aFailedWriteIsTriedAgainAndNothingBehindItIsWrittenFirst() {
        val store = recording()
        val w = StoreWriter(store, storeThread, logs::add)
        val release = store.holdNext()
        w.saveSettings(Space.LIVE, AppSettings(entityName = "A"))
        waitFor("the first write held") { store.holding }
        store.failNext = "ledger"
        w.saveTrips(Space.LIVE, trips(1))
        w.saveLedger(Space.LIVE, "c", entries(1))
        release.countDown()
        assertTrue(w.flush(10_000))
        assertEquals(listOf("settings LIVE A", "ledger LIVE 1", "trips LIVE 1"), store.writes.toList())
        assertEquals(listOf("STORE_WRITE_FAILED error=IOException"), logs.toList())
    }

    @Test fun aFlushWaitsForWhatWasHandedOverUpToItsTimeout() {
        val store = recording()
        val w = StoreWriter(store, storeThread, logs::add)
        val release = store.holdNext()
        w.saveTrips(Space.LIVE, trips(1))
        waitFor("the write held") { store.holding }
        assertFalse(w.flush(100))
        release.countDown()
        assertTrue(w.flush(10_000))
        assertEquals(listOf("trips LIVE 1"), store.writes.toList())
    }

    @Test fun withoutAThreadASaveIsWrittenBeforeItReturnsAndAFailureIsThrownThere() {
        val store = recording()
        val w = StoreWriter(store, null, logs::add)
        w.saveTrips(Space.LIVE, trips(1))
        assertEquals(listOf("trips LIVE 1"), store.writes.toList())
        store.failNext = "ledger"
        assertThrows(IOException::class.java) { w.saveLedger(Space.LIVE, "c", entries(1)) }
        w.saveOutbox(Space.LIVE, items(1))
        assertEquals("the failed ledger first, then what came after", listOf("trips LIVE 1", "ledger LIVE 1", "outbox LIVE 1"), store.writes.toList())
    }

    // ------------------------------------------------------------------ the core on it

    @Test fun anEntryGoesOutOnlyOnceTheQueueHoldingItIsWrittenAfterTheLedger() {
        val store = recording()
        val h = onCore { Harness(settings = AppSettings(archiveWebhookUrl = HOOK), background = background, store = store) }
        val release = store.holdNext()
        onCore { h.drive(1_000.0, 12.0, null, null) }
        waitFor("a write held") { store.holding }
        assertEquals("queued", 1, onCore { h.core.outbox.value.size })
        assertTrue("not sent before it is written", h.core.outboxSnapshot().items.isEmpty())
        assertEquals(0, h.core.sendable.value)
        release.countDown()
        assertTrue(h.core.flushWrites(10_000))
        assertEquals(1, h.core.outboxSnapshot().items.size)
        assertEquals(1, h.core.sendable.value)
        val files = store.writes.map { it.substringBefore(' ') }
        assertTrue("the ledger before the queue: $files", files.lastIndexOf("ledger") < files.lastIndexOf("outbox"))
    }

    @Test fun aKillBetweenTheLedgerAndTheTripsFileRestartsWithThePurposeAndAnIntactChain() {
        val store = recording()
        val h = onCore { Harness(background = background, store = store) }
        onCore { h.drive(1_000.0, 12.0, null, null) }
        assertTrue(h.core.flushWrites(10_000))
        val id = onCore { h.core.trips.value.single().id }
        // the writer is busy (a settings write held), so the purpose's ledger and trips saves wait together
        val release = store.holdNext()
        onCore { h.core.saveSettings(h.core.settings.value.copy(entityName = "A"), null, null) }
        waitFor("a write held") { store.holding }
        onCore { h.core.classifyTrip(id, TripPurpose.PERSONAL, null, "", ClassifiedBy.SCREEN) }
        store.stallAfter(2) // the held settings and the first file of the purpose are written, then the process is gone
        release.countDown()
        assertTrue("the second file stopped", store.stalled.await(10, TimeUnit.SECONDS))
        val restarted = AutoBriefCore(store.copy())
        assertTrue(restarted.integrity.value.problems.toString(), restarted.integrity.value.ok)
        assertEquals("rebuilt from the chain", TripPurpose.PERSONAL, restarted.trips.value.single().purpose)
        assertEquals("written before the kill", "ledger LIVE 2", store.writes.last())
    }

    @Test fun aSwitchToASpaceGetsTheDeliveriesMarkedForItWhileItWasRead() {
        val store = recording()
        val h = onCore { Harness(settings = AppSettings(archiveWebhookUrl = HOOK), background = background, store = store) }
        onCore {
            h.drive(1_000.0, 5.0, null, null)
            h.advance(60_000)
            h.drive(1_005.0, 7.0, null, null)
        }
        val (first, second) = onCore { h.core.outbox.value.map { it.key } }
        assertEquals(AutoBriefCore.SettingsResult.SAVED, onCore { h.core.setDemoModeNow(true, null) })
        val release = store.holdNext()
        onCore { h.core.saveSettings(h.core.settings.value.copy(entityName = "시연"), null, null) }
        waitFor("a demo write held") { store.holding }
        // the sender's round for the live space ends after the switch: its results go to the stored live queue
        h.core.markDeliveries(Space.LIVE, listOf(AutoBriefCore.Delivery(first, true, null)))
        onCore { h.core.launch { setDemoMode(false, null) } }
        onCore { } // the switch has asked for the live space to be read
        h.core.markDeliveries(Space.LIVE, listOf(AutoBriefCore.Delivery(second, true, null)))
        release.countDown()
        waitFor("back in the live space") { h.core.activeSpace.value == Space.LIVE }
        assertTrue(h.core.flushWrites(10_000))
        assertEquals("delivered, as the switch shows it", listOf(true, true), onCore { h.core.outbox.value.map { it.deliveredEpochMs != null } })
        assertEquals("delivered, as stored", listOf(true, true), store.copy().load(Space.LIVE).outbox.map { it.deliveredEpochMs != null })
    }

    private fun entries(n: Int) = List(n) { LedgerEntry(it + 1L, 0, "TRIP", "t$it", "{}", "", "") }
    private fun trips(n: Int) = List(n) { TripRecord("t$it", 0, 0, 0.0, 0.0, TripPurpose.UNCLASSIFIED, null, "") }
    private fun items(n: Int) = List(n) { OutboxItem("k$it", "ledger", "{}", 0) }

    /**
     * Records each write as "<file> <space> <size or value>" in the order written. [holdNext] holds the next write until
     * released, [failNext] makes the next write of one file throw once, and [stallAfter] lets n writes through and stops
     * the next one for good (the process was killed in the middle of a batch).
     */
    private class Recording : MemoryStore() {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile private var hold: CountDownLatch? = null
        @Volatile var holding = false
            private set
        @Volatile var failNext: String? = null
        @Volatile private var passBeforeStall = -1
        val stalled = CountDownLatch(1)
        private val gone = CountDownLatch(1)

        fun holdNext(): CountDownLatch = CountDownLatch(1).also { hold = it }
        fun stallAfter(n: Int) { passBeforeStall = n }
        fun letGo() = gone.countDown()

        /** What is stored now, as a store of its own (the files a restart finds). */
        fun copy(): MemoryStore = synchronized(this) { MemoryStore(active).also { it.data.putAll(data); it.pins.putAll(pins) } }

        private fun write(file: String, detail: String) {
            hold?.let { latch ->
                hold = null
                holding = true
                latch.await(20, TimeUnit.SECONDS)
                holding = false
            }
            if (passBeforeStall == 0) {
                stalled.countDown()
                gone.await()
                throw IOException("the process is gone")
            }
            if (passBeforeStall > 0) passBeforeStall--
            if (failNext == file) {
                failNext = null
                throw IOException("disk full")
            }
            writes += "$file $detail"
        }

        override fun setActiveSpace(space: Space) { write("space", "$space"); super.setActiveSpace(space) }
        override fun saveSettings(space: Space, settings: AppSettings) { write("settings", "$space ${settings.entityName}"); super.saveSettings(space, settings) }
        override fun saveTrips(space: Space, trips: List<TripRecord>) { write("trips", "$space ${trips.size}"); super.saveTrips(space, trips) }
        override fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) { write("ledger", "$space ${entries.size}"); super.saveLedger(space, chainId, entries) }
        override fun saveOutbox(space: Space, items: List<OutboxItem>) { write("outbox", "$space ${items.size}"); super.saveOutbox(space, items) }
        override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) { write("open", "$space ${if (trip == null) "none" else "trip"}"); super.saveOpenTrip(space, trip) }
    }

    companion object {
        const val HOOK = "https://receiver.example/hook"
    }
}
