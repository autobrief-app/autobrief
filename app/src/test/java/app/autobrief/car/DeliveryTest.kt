package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.notify.WebhookSender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.concurrent.thread

/** What reaches the company address, in which order, and what happens when it does not answer. */
class DeliveryTest {
    private val hook = "http://10.0.2.2:8787/hook"

    /** A fake endpoint: records what was posted where; [answer] decides the result. */
    private class Endpoint(var answer: (String, String) -> Boolean = { _, _ -> true }) {
        val posts = mutableListOf<Triple<String, String, String>>() // url, key, body
        fun post(url: String, body: String, key: String): Pair<Boolean, String?> {
            posts += Triple(url, key, body)
            return if (answer(url, key)) true to null else false to "HTTP 503"
        }
    }

    private fun seqOf(body: String) = Json.parseToJsonElement(body).jsonObject["entry"]?.jsonObject?.get("seq")?.jsonPrimitive?.long

    @Test fun ledgerEntriesGoOutInSeqOrderEvenWhenTheCarClockWentBack() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.drive(100.0, 10.0, null, null)
        h.now -= 6 * 3_600_000L // the head unit's clock is set back six hours
        h.drive(110.0, 10.0, null, null)
        val created = h.core.outbox.value.map { it.createdEpochMs }
        assertTrue("queued with a clock that went back", created.last() < created.first())
        val endpoint = Endpoint()
        WebhookSender(h.core, { h.now }, { h.now }, endpoint::post).flush()
        assertEquals(listOf(1L, 2L), endpoint.posts.map { seqOf(it.third) })
        assertTrue(h.core.outbox.value.all { it.deliveredEpochMs != null })
    }

    @Test fun aLedgerEntryThatKeepsFailingIsRetriedWithBackoffAndNeverDropped() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.drive(100.0, 10.0, null, null)
        h.drive(110.0, 10.0, null, null)
        val endpoint = Endpoint { _, _ -> false }
        val sender = WebhookSender(h.core, { h.now }, { h.now }, endpoint::post)
        sender.flush()
        assertEquals("the first failure ends the round: entry 2 never overtakes entry 1", 1, endpoint.posts.size)
        sender.flush()
        assertEquals("waits out its backoff", 1, endpoint.posts.size)
        repeat(80) { h.advance(WebhookSender.BACKOFF_MAX_MS); sender.flush() }
        val head = h.core.outbox.value.first()
        assertEquals(81, head.attempts)
        assertEquals("never dropped, no matter how many attempts", null, head.deliveredEpochMs)
        assertEquals(2, h.core.outbox.value.count { it.deliveredEpochMs == null })
        endpoint.answer = { _, _ -> true }
        h.advance(WebhookSender.BACKOFF_MAX_MS)
        sender.flush()
        assertTrue(h.core.outbox.value.all { it.deliveredEpochMs != null })
        assertEquals("then in order", listOf(1L, 2L), endpoint.posts.takeLast(2).map { seqOf(it.third) })
    }

    @Test fun anArchiveThatKeepsFailingIsGivenUpSoTheWaitingCountMatchesWhatWillArrive() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        h.drive(100.0, 10.0, null, null)
        h.core.exportNow(app.autobrief.car.domain.TaxPeriods.month(2026, 10), businessYear = false)
        val endpoint = Endpoint { _, key -> !key.startsWith("archive-") } // the workbook is refused, entries pass
        val sender = WebhookSender(h.core, { h.now }, { h.now }, endpoint::post)
        repeat(WebhookSender.MAX_ARCHIVE_ATTEMPTS + 5) { sender.flush(); h.advance(WebhookSender.BACKOFF_MAX_MS) }
        assertEquals(WebhookSender.MAX_ARCHIVE_ATTEMPTS, endpoint.posts.count { it.second.startsWith("archive-") })
        assertTrue("the given-up workbook no longer counts as 대기", h.core.outbox.value.none { it.kind == "archive" && it.deliveredEpochMs == null })
        assertTrue(h.core.outbox.value.filter { it.kind == "ledger" }.all { it.deliveredEpochMs != null })
    }

    @Test fun aRoundStopsAtItsTimeCapAndSaysMoreIsDue() {
        val h = Harness()
        h.core.setDemoModeNow(true, null)
        h.core.loadSampleHistoryNow(hook)
        var mono = 0L
        val endpoint = Endpoint { _, _ -> mono += 1_000; true } // every post takes a second
        val sender = WebhookSender(h.core, { h.now }, { mono }, endpoint::post)
        assertTrue("more is due after the cap", sender.flush(maxMs = 5_000))
        assertEquals(5, endpoint.posts.size)
        assertEquals((1L..5L).toList(), endpoint.posts.map { seqOf(it.third) })
    }

    @Test fun aSpaceSwitchMidRoundNeverSendsDemoEntriesToTheLiveAddress() {
        val live = "https://live.invalid/hook"
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = live))
        h.drive(100.0, 10.0, null, null)
        h.core.setDemoModeNow(true, null)
        h.core.loadSampleHistoryNow(hook) // the demo space's own address
        val endpoint = Endpoint()
        endpoint.answer = { _, _ ->
            if (endpoint.posts.size == 3) h.core.setDemoModeNow(false, null) // the presenter switches back to LIVE
            true
        }
        WebhookSender(h.core, { h.now }, { h.now }, endpoint::post).flush()
        val demoPosts = endpoint.posts.filter { it.second.startsWith("ledger-demo-") }
        assertTrue(demoPosts.isNotEmpty())
        assertTrue("demo entries only ever go to the demo address", demoPosts.all { it.first == hook })
        assertEquals("the round ended with the switch", 3, endpoint.posts.size)
        // the results still land in the demo space's queue, not in the live one
        val demoOutbox = h.store.data.getValue(Space.DEMO).outbox
        assertEquals(3, demoOutbox.count { it.deliveredEpochMs != null })
        assertTrue(h.store.data.getValue(Space.LIVE).outbox.none { it.key.startsWith("ledger-demo-") })
    }

    @Test fun concurrentQueueingAndDeliveryResultsLeaveTheSavedQueueEqualToTheOneInMemory() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook), startAt = Harness.at(LocalDate.of(2026, 10, 23), 9, 0))
        h.core.setDemoModeNow(true, null)
        h.core.loadSampleHistoryNow(hook)
        val keys = h.core.outbox.value.map(OutboxItem::key)
        val marker = thread {
            keys.chunked(10).forEach { batch -> h.core.markDeliveries(batch.map { AutoBriefCore.Delivery(it, true, null) }) }
        }
        (1..10).forEach { m -> h.core.exportNow(app.autobrief.car.domain.TaxPeriods.month(2026, m), businessYear = false) }
        marker.join()
        assertEquals(h.core.outbox.value, h.store.data.getValue(Space.DEMO).outbox)
        assertFalse(h.core.outbox.value.isEmpty())
        assertEquals(SampleHistory.END_ODOMETER_KM.toDouble(), h.core.trips.value.last().endOdometerKm, 0.0)
    }
}
