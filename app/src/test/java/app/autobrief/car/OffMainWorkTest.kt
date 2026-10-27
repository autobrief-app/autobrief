package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.AutoBriefCore.WorkbookRequest
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.domain.TaxPeriods
import app.autobrief.car.model.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The core's slow work leaves its thread. A stand-in main thread ("core") owns the core; workbooks and the sending
 * copies of a ledger are built on "wb-work" and written on "wb-io". Each test holds the work thread with a gate: while it
 * is closed nothing can be built, so what the core thread still does meanwhile is checked without timing.
 */
class OffMainWorkTest {
    private fun thread(name: String): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    private val coreThread = thread("core")
    private val workThread = thread("wb-work")
    private val ioThread = thread("wb-io")
    private val scope = CoroutineScope(SupervisorJob() + coreThread.asCoroutineDispatcher())
    private val background = AutoBriefCore.Background(scope, workThread.asCoroutineDispatcher(), ioThread.asCoroutineDispatcher())

    /** The thread that wrote each exported file, in order (without the " @coroutine#n" that coroutine debug mode appends). */
    private val writers: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After fun stop() {
        scope.cancel()
        listOf(coreThread, workThread, ioThread).forEach { it.shutdownNow() }
    }

    /** Runs [block] on the core thread and returns its value: it fails when that thread stays busy for 10 s. */
    private fun <T> onCore(block: () -> T): T = coreThread.submit<T>(block).get(10, TimeUnit.SECONDS)

    /** Holds the work thread until the returned gate opens: whatever the core hands it waits behind this. */
    private fun holdWork(): CountDownLatch = CountDownLatch(1).also { gate -> workThread.execute { gate.await(30, TimeUnit.SECONDS) } }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!check()) {
            if (System.nanoTime() > until) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun harness(day: LocalDate, store: MemoryStore = MemoryStore()) = onCore {
        Harness(
            startAt = Harness.at(day, 9, 0),
            background = background,
            onWrite = { writers += Thread.currentThread().name.substringBefore(" @") },
            store = store,
        )
    }

    /**
     * Records which thread writes the queue file and, once armed, holds a write from any thread but the core's at a gate:
     * a whole-file write of a big queue (thousands of sending copies, a workbook in base64) takes seconds on a loaded car.
     */
    private class GatedQueueStore : MemoryStore() {
        private val armed = AtomicBoolean(false)
        private val gate = CountDownLatch(1)
        @Volatile var held = false
            private set
        val queueWriters: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun arm() = armed.set(true)
        fun release() = gate.countDown()

        override fun saveOutbox(space: Space, items: List<OutboxItem>) {
            val name = Thread.currentThread().name.substringBefore(" @")
            queueWriters += name
            if (armed.get() && name != "core") {
                held = true
                gate.await(30, TimeUnit.SECONDS)
            }
            super.saveOutbox(space, items)
        }
    }

    /** Runs [block] on the core thread; taking longer than [ms] means it waited for a lock another thread held. */
    private fun coreAnswers(what: String, ms: Long = 2_000, block: () -> Unit) {
        try {
            coreThread.submit(block).get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("the core thread waited for a background thread's queue write: $what")
        }
    }

    /** The demo space with the labelled sample year (485 rows, 969 ledger entries up to 2026-10-22). */
    private fun sample(hook: String = ""): Harness = harness(LocalDate.of(2026, 10, 23)).also { h ->
        onCore {
            check(h.core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED)
            h.core.loadSampleHistory(hook)
        }
        if (hook.isNotEmpty()) waitFor("the sample ledger in the outbox") { onCore { h.core.outbox.value.size } == onCore { h.core.ledgerEntries.size } }
    }

    @Test fun theReportScreensWorkbookIsMadeOffTheCoreThreadWhichStaysFree() {
        val h = sample()
        val year = TaxPeriods.businessYear(2026, 1)
        val gate = holdWork()
        assertTrue(onCore { h.core.requestWorkbook(year, businessYear = true, send = false) })
        // the request returned at once; with the work thread held nothing is built or written, and the core thread answers
        Thread.sleep(300)
        assertTrue(onCore { h.core.workbook.value } is WorkbookRequest.Making)
        assertFalse("one workbook at a time", onCore { h.core.requestWorkbook(year, businessYear = true, send = false) })
        assertTrue("nothing written while the work thread is held: $writers", writers.isEmpty())
        gate.countDown()
        waitFor("the workbook") { onCore { h.core.workbook.value } is WorkbookRequest.Made }
        val made = onCore { h.core.workbook.value } as WorkbookRequest.Made
        assertEquals("운행기록부_2026-01-01_2026-12-31.xlsx", made.result.fileName)
        assertTrue(made.result.rows > 400)
        assertEquals("the .xlsx and the .csv, both on the I/O thread", listOf("wb-io", "wb-io"), writers.toList())
        assertTrue(h.logs.any { it.startsWith("EXPORT file=운행기록부_2026-01-01_2026-12-31.xlsx rows=") })
    }

    @Test fun theMonthCloseMakesItsWorkbookOffTheCoreThread() {
        val h = harness(LocalDate.of(2026, 9, 30))
        onCore { h.drive(1_000.0, 12.0, null, null) } // a September trip; September is not over yet
        assertTrue(writers.isEmpty())
        val gate = holdWork()
        onCore {
            h.now = Harness.at(LocalDate.of(2026, 10, 1), 9, 0)
            h.core.tick() // October: September's workbook is due
        }
        Thread.sleep(300)
        assertNull("not closed while its workbook cannot be built", onCore { h.core.notice.value })
        assertEquals("", onCore { h.core.settings.value.lastMonthlyCloseYm })
        onCore { h.core.tick() } // a tick meanwhile starts no second close
        gate.countDown()
        waitFor("September closed") { onCore { h.core.settings.value.lastMonthlyCloseYm } == "2026-09" }
        assertEquals(listOf("wb-io", "wb-io"), writers.toList())
        assertTrue(onCore { h.core.notice.value }!!.text.startsWith("9월 운행기록부를 만들었습니다: 1건"))
    }

    @Test fun aGleoSendRepliesAfterItsWorkbookIsWrittenWhileTheCoreThreadStaysFree() {
        val h = sample()
        val gate = holdWork()
        val reply = onCore { scope.async { h.core.handleGleo(GleoContract.SEND_LOGBOOK, GleoContract.parseParams("""{"period":"BUSINESS_YEAR"}""")) } }
        Thread.sleep(300)
        assertFalse("the reply waits for the workbook", reply.isCompleted)
        assertEquals(Space.DEMO, onCore { h.core.activeSpace.value }) // the core thread answers meanwhile
        gate.countDown()
        val r = runBlocking { withTimeout(20_000) { reply.await() } }
        assertEquals(r.message, "success", r.status)
        assertEquals(listOf("wb-io", "wb-io"), writers.toList())
    }

    @Test fun aBackfillIsBuiltOffTheCoreThreadAndNoNewerEntryGoesOutBeforeIt() {
        val h = harness(LocalDate.of(2026, 10, 23))
        onCore {
            h.drive(1_000.0, 5.0, null, null)
            h.advance(60_000)
            h.drive(1_005.0, 7.0, null, null)
        }
        val recorded = onCore { h.core.ledgerEntries.size }
        val gate = holdWork()
        val url = "https://receiver.example/hook"
        assertEquals(
            AutoBriefCore.SettingsResult.SAVED,
            onCore { h.core.saveSettings(h.core.settings.value.copy(archiveWebhookUrl = url), null, null) },
        )
        // the backfill waits for the work thread; a trip recorded now is queued at once, but held back from the sender
        onCore {
            h.advance(60_000)
            h.drive(1_012.0, 3.0, null, null)
        }
        val newer = onCore { h.core.outbox.value }
        assertTrue(newer.isNotEmpty() && newer.all { it.kind == "ledger" && it.seq!! > recorded })
        assertTrue("held while the backfill is built", onCore { h.core.outboxSnapshot() }.items.none { it.kind == "ledger" })
        gate.countDown()
        val all = onCore { h.core.ledgerEntries.size }
        waitFor("the backfill in the outbox") { onCore { h.core.outboxSnapshot() }.items.size == all }
        assertEquals((1L..all).toList(), onCore { h.core.outboxSnapshot() }.items.map { it.seq!! }.sorted())
    }

    @Test fun aWorkbookFinishedAfterASpaceSwitchIsQueuedInItsOwnSpace() {
        val h = sample("https://receiver.example/hook")
        val gate = holdWork()
        assertTrue(onCore { h.core.requestWorkbook(TaxPeriods.month(2026, 10), businessYear = false, send = true) })
        assertEquals(AutoBriefCore.SettingsResult.SAVED, onCore { h.core.setDemoMode(false, null) }) // back to LIVE meanwhile
        gate.countDown()
        waitFor("the workbook") { onCore { h.core.workbook.value } is WorkbookRequest.Made }
        assertTrue("queued in the demo space", h.store.load(Space.DEMO).outbox.any { it.kind == "archive" })
        assertTrue("not in the live queue", onCore { h.core.outbox.value }.none { it.kind == "archive" })
        assertTrue(h.files.keys.all { it.startsWith("demo/") })
    }

    @Test fun theSampleLedgersSendingCopiesAreQueuedOnTheCoreThread() {
        val store = GatedQueueStore().also { it.arm() }
        val h = harness(LocalDate.of(2026, 10, 23), store)
        try {
            onCore {
                check(h.core.setDemoMode(true, null) == AutoBriefCore.SettingsResult.SAVED)
                h.core.loadSampleHistory("https://receiver.example/hook") // 969 sending copies: the backfill
            }
            waitFor("the sample ledger queued, or its queue write held") {
                store.held || onCore { h.core.outbox.value.size } == onCore { h.core.ledgerEntries.size }
            }
            coreAnswers("a trip recorded while the sample ledger is queued") { h.drive(90_000.0, 4.0, null, null) }
        } finally {
            store.release()
        }
        waitFor("the whole ledger in the outbox") { onCore { h.core.outboxSnapshot() }.items.size == onCore { h.core.ledgerEntries.size } }
        assertEquals("the queue file is written on the core thread only", setOf("core"), store.queueWriters.toSet())
    }

    @Test fun aTripRecordedWhileTheMonthClosesWorkbookIsQueuedDoesNotWaitForTheQueueWrite() {
        val store = GatedQueueStore()
        val h = harness(LocalDate.of(2026, 9, 30), store)
        onCore {
            h.drive(1_000.0, 12.0, null, null) // a September trip
            check(h.core.saveSettings(h.core.settings.value.copy(archiveWebhookUrl = "https://receiver.example/hook"), null, null) == AutoBriefCore.SettingsResult.SAVED)
        }
        waitFor("the backfill in the outbox") { onCore { h.core.outboxSnapshot() }.items.size == onCore { h.core.ledgerEntries.size } }
        store.arm()
        try {
            onCore {
                h.now = Harness.at(LocalDate.of(2026, 10, 1), 9, 0)
                h.core.tick() // October: September's workbook is made and queued for the address
            }
            waitFor("September's workbook queued, or its queue write held") {
                store.held || onCore { h.core.outbox.value.any { it.kind == "archive" } }
            }
            coreAnswers("a trip recorded while the month's workbook is queued") {
                h.advance(60_000)
                h.drive(1_012.0, 3.0, null, null)
            }
        } finally {
            store.release()
        }
        waitFor("September closed") { onCore { h.core.settings.value.lastMonthlyCloseYm } == "2026-09" }
        assertTrue(onCore { h.core.outbox.value.any { it.kind == "archive" } })
        assertEquals("the queue file is written on the core thread only", setOf("core"), store.queueWriters.toSet())
    }
}
