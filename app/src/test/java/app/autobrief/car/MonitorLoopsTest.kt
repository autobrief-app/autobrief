package app.autobrief.car

import app.autobrief.car.service.MonitorLoops
import app.autobrief.car.service.guarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** The monitor service's loops on real threads: sending never holds up the ticks, and a failure never ends a loop. */
class MonitorLoopsTest {
    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun loops(scope: CoroutineScope, onTick: (Int) -> Unit, flush: () -> Boolean, errors: MutableList<String> = mutableListOf()) =
        MonitorLoops(scope, Dispatchers.IO, tickMs = 10, flushEveryMs = 60_000, onError = { what, e -> errors += "$what: ${e.message}" }, onTick = onTick, flush = flush)

    @Test fun ticksGoOnWhileASendRoundHangsOnADeadEndpoint() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ticks = AtomicInteger()
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val l = loops(scope, { ticks.incrementAndGet() }, { flushing.countDown(); release.await(); false })
            l.start()
            l.requestFlush()
            waitUntil("the round to start") { flushing.count == 0L }
            val before = ticks.get()
            delay(300)
            assertTrue("ticks while the round hung: ${ticks.get() - before}", ticks.get() - before >= 10)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun aRoundCutAtItsCapIsFollowedAtOnceAndRequestsCoalesce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rounds = AtomicInteger()
        try {
            // the first three rounds stop at their cap with more due; then the queue is empty
            val l = loops(scope, {}, { rounds.incrementAndGet() < 4 })
            l.start()
            repeat(5) { l.requestFlush() }
            waitUntil("four rounds") { rounds.get() >= 4 }
            delay(100)
            assertTrue("three capped rounds, one that emptied the queue, at most one for the coalesced requests: ${rounds.get()}", rounds.get() in 4..5)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aTickOrRoundThatThrowsIsLoggedAndTheLoopsGoOn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ticks = AtomicInteger()
        val rounds = AtomicInteger()
        val errors = CopyOnWriteArrayList<String>()
        try {
            val l = loops(scope, { n -> ticks.incrementAndGet(); if (n == 2) error("bad event") }, { if (rounds.incrementAndGet() == 1) error("disk full") else false }, errors)
            l.start()
            l.requestFlush()
            waitUntil("ticks after the failing one") { ticks.get() >= 6 }
            l.requestFlush()
            waitUntil("a second round") { rounds.get() >= 2 }
            assertTrue(errors.toString(), errors.contains("tick: bad event") && errors.contains("flush: disk full"))
        } finally {
            scope.cancel()
        }
    }

    @Test fun guardedLogsAndSwallowsAnException() {
        val errors = mutableListOf<String>()
        assertNull(guarded("vehicle event", { what, e -> errors += "$what: ${e.message}" }) { error("NaN") })
        assertEquals(42, guarded("x", { _, _ -> }) { 42 })
        assertEquals(listOf("vehicle event: NaN"), errors)
    }
}
