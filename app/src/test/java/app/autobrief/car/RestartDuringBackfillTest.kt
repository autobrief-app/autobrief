package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.LedgerEntry
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.Gear
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripRecord
import app.autobrief.car.notify.WebhookSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The sending copies of a whole ledger (the sample loaded with an address, or an address set for the first time) are
 * built off the core's thread after the address is saved. In rehearsal 40475583018 Android closed the app 2 s after
 * DEMO_READY, before they were queued, and nothing queued them again: the receiver showed 중간 기록 누락 (순번 1~977).
 * Here a run of the app is killed at that moment and a second run starts on the same storage: the receiver gets every
 * entry, in seq order, the ones recorded after the restart last.
 */
class RestartDuringBackfillTest {
    /** The car's storage: what a killed run leaves behind. */
    private val disk = MemoryStore()
    private val runs = mutableListOf<AppRun>()

    @After fun stopRuns() = runs.forEach { it.kill() }

    /** One run of the app: its own core, work and I/O threads over [disk]. [kill] stops them at once, mid-work. */
    private inner class AppRun(startAt: Long) {
        private fun thread(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
        private val coreThread = thread("core")
        private val workThread = thread("work")
        private val ioThread = thread("io")
        private val scope = CoroutineScope(SupervisorJob() + coreThread.asCoroutineDispatcher())

        @Volatile private var dead = false
        @Volatile var now = startAt
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** The storage as this run sees it: nothing it does after its kill reaches the disk. */
        private val store = object : AutoBriefCore.Store by disk {
            override fun setActiveSpace(space: Space) = alive { disk.setActiveSpace(space) }
            override fun saveSettings(space: Space, settings: AppSettings) = alive { disk.saveSettings(space, settings) }
            override fun saveTrips(space: Space, trips: List<TripRecord>) = alive { disk.saveTrips(space, trips) }
            override fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) = alive { disk.saveLedger(space, chainId, entries) }
            override fun saveOutbox(space: Space, items: List<OutboxItem>) = alive { disk.saveOutbox(space, items) }
            override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) = alive { disk.saveOpenTrip(space, trip) }
            override fun wipe(space: Space) = alive { disk.wipe(space) }
            private fun alive(write: () -> Unit) {
                if (!dead) write()
            }
        }

        val core: AutoBriefCore = onCore {
            AutoBriefCore(
                store,
                clock = { now },
                log = { logs += it },
                background = AutoBriefCore.Background(scope, workThread.asCoroutineDispatcher(), ioThread.asCoroutineDispatcher()),
            )
        }

        init {
            runs += this
        }

        fun <T> onCore(block: () -> T): T = coreThread.submit<T>(block).get(10, TimeUnit.SECONDS)

        /** Holds the work thread: what the core hands it (the sending copies) waits until the gate opens. */
        fun holdWork(): CountDownLatch = CountDownLatch(1).also { gate -> workThread.execute { gate.await(30, TimeUnit.SECONDS) } }

        /** Park, drive [km] from [fromKm], park: one TRIP entry (no place rule applies without positions). */
        fun drive(fromKm: Double, km: Double) = onCore {
            core.onVehicle(VehicleEvent.Odometer(fromKm))
            core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
            now += 60_000
            core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
            now += 20 * 60_000
            core.onVehicle(VehicleEvent.Odometer(fromKm + km))
            core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
            now += 60_000
        }

        /** True once the queue the sender would work from holds [n] ledger entries; false after 5 s. */
        fun ledgerQueued(n: Int): Boolean {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < until) {
                if (onCore { core.outboxSnapshot().items.count { it.kind == "ledger" } } == n) return true
                Thread.sleep(10)
            }
            return false
        }

        /** Sends everything due to a receiver that takes every request; returns the seqs it got, in arrival order. */
        fun deliverAll(): List<Long> {
            val got = mutableListOf<Long>()
            val sender = WebhookSender(core, now = { now }) { _, body, _ ->
                got += Json.parseToJsonElement(body).jsonObject.getValue("entry").jsonObject.getValue("seq").jsonPrimitive.long
                true to null
            }
            while (sender.flush()) Unit
            return got
        }

        fun kill() {
            dead = true
            scope.cancel()
            listOf(coreThread, workThread, ioThread).forEach { it.shutdownNow() }
        }
    }

    @Test fun theSampleLoadKilledBeforeItsSendingCopiesWereQueuedSendsEveryEntryInOrderAfterARestart() {
        val first = AppRun(DAY)
        val gate = first.holdWork()
        first.onCore {
            check(first.core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED)
            first.core.loadSampleHistory(HOOK)
        }
        assertTrue(first.logs.any { it.startsWith("DEMO_READY trips=") })
        val sample = first.onCore { first.core.ledgerEntries.size }
        assertTrue("killed before any sending copy was queued", disk.load(Space.DEMO).outbox.isEmpty())
        first.kill()
        gate.countDown()

        val second = AppRun(DAY + 3_600_000)
        second.drive(SampleHistory.END_ODOMETER_KM.toDouble(), 12.0) // recorded after the restart: it goes out last
        second.ledgerQueued(sample + 1)
        assertEquals((1L..sample + 1L).toList(), second.deliverAll())
    }

    @Test fun anAddressSetForTheFirstTimeAndKilledBeforeTheCopiesWereQueuedSendsTheWholeLedgerInOrderAfterARestart() {
        val first = AppRun(DAY)
        first.drive(1_000.0, 5.0)
        first.drive(1_005.0, 7.0)
        val recorded = first.onCore { first.core.ledgerEntries.size }
        val gate = first.holdWork()
        val saved = first.onCore { first.core.saveSettings(first.core.settings.value.copy(archiveWebhookUrl = HOOK), null, null) }
        assertEquals(AutoBriefCore.SettingsResult.SAVED, saved)
        first.kill()
        gate.countDown()

        val second = AppRun(DAY + 3_600_000)
        second.drive(1_012.0, 3.0)
        second.ledgerQueued(recorded + 1)
        assertEquals((1L..recorded + 1L).toList(), second.deliverAll())
    }

    private companion object {
        val DAY = Harness.at(LocalDate.of(2026, 10, 23), 9, 0)
        const val HOOK = "https://receiver.example/hook"
    }
}
