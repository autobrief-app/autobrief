package app.autobrief.car

import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.Ledger
import app.autobrief.car.domain.LedgerKind
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** Every ledger is one chain with its own random id: a demo reset starts a new copy at the receiver, not a forgery. */
class ChainIdentityTest {
    private val hook = "http://10.0.2.2:8787/hook"

    private fun payloadChain(json: String): String? = Json.parseToJsonElement(json).jsonObject["chainId"]?.jsonPrimitive?.content
    private fun payloadSpace(json: String): String? = Json.parseToJsonElement(json).jsonObject["space"]?.jsonPrimitive?.content

    @Test fun aDemoResetStartsANewChainWithItsOwnDeliveryKeys() {
        val h = Harness()
        h.core.setDemoMode(true, null)
        h.core.loadSampleHistory(hook)
        val first = h.core.ledgerChainId
        assertNotNull(first)
        val firstKeys = h.core.outbox.value.map { it.key }.toSet()
        assertTrue(firstKeys.all { it.startsWith("ledger-demo-$first-") })
        assertTrue(h.core.outbox.value.all { payloadChain(it.payloadJson) == first })
        // the receiver tells a demo restart (silent, labelled 시연) from a live one (a warning) by this field
        assertTrue(h.core.outbox.value.all { payloadSpace(it.payloadJson) == "demo" })

        h.core.resetDemo()
        h.core.loadSampleHistory(hook) // the same sample again: seq 1.. again, but a different chain
        val second = h.core.ledgerChainId
        assertNotEquals(first, second)
        val secondKeys = h.core.outbox.value.map { it.key }.toSet()
        assertTrue("no delivery key repeats across the reset", secondKeys.none { it in firstKeys })
        assertTrue(h.core.outbox.value.all { payloadChain(it.payloadJson) == second })
    }

    @Test fun theChainIdStartsWithTheFirstEntryAndIsKeptWithTheLedger() {
        val h = Harness(AppSettings(plateNumber = "00가 0000", archiveWebhookUrl = hook))
        assertNull("no ledger, no chain yet", h.core.ledgerChainId)
        h.drive(100.0, 12.0, null, null)
        val id = h.core.ledgerChainId!!
        assertEquals(id, h.store.data.getValue(Space.LIVE).chainId)
        assertEquals(listOf("ledger-live-$id-1"), h.core.outbox.value.map { it.key })
        assertEquals("live", payloadSpace(h.core.outbox.value.single().payloadJson))
        val again = AutoBriefCore(h.store, clock = { h.now })
        assertEquals(id, again.ledgerChainId)
        h.drive(112.0, 5.0, null, null)
        assertEquals(id, h.core.ledgerChainId)
        assertEquals("ledger-live-$id-2", h.core.outbox.value.last().key)
    }

    @Test fun aLedgerWrittenBeforeChainIdsKeepsTheIdentityTheReceiverAlreadyHas() {
        val (rows, ledger) = SampleHistory.build(LocalDate.of(2026, 2, 1))
        val store = MemoryStore()
        store.data[Space.LIVE] = AutoBriefCore.SpaceData(settings = AppSettings(plateNumber = "00가 0000"), trips = rows, ledger = ledger.all)
        val core = AutoBriefCore(store, clock = { Harness.at(LocalDate.of(2026, 2, 1), 9, 0) })
        assertNull(core.ledgerChainId)
        core.saveSettings(core.settings.value.copy(archiveWebhookUrl = hook), null, null)
        val first = core.outbox.value.first()
        assertEquals("ledger-live-1", first.key)
        assertNull(payloadChain(first.payloadJson))
    }

    @Test fun theLedgerFileKeepsTheChainIdAndStillReadsTheOldFormat() {
        val dir = Files.createTempDirectory("autobrief-chain").toFile()
        try {
            val files = JsonFiles(dir)
            val (_, ledger) = SampleHistory.build(LocalDate.of(2026, 1, 10))
            files.saveLedger(Space.LIVE, "0123456789abcdef", ledger.all)
            val back = files.load(Space.LIVE)
            assertEquals("0123456789abcdef", back.chainId)
            assertEquals(ledger.all, back.ledger)
            // a ledger.json from 2.0.0 is a bare list of entries
            File(dir, "live/ledger.json").writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(app.autobrief.car.domain.LedgerEntry.serializer()), ledger.all))
            val old = files.load(Space.LIVE)
            assertNull(old.chainId)
            assertEquals(ledger.all, old.ledger)
            assertTrue(Ledger.check(old.ledger, emptyList()).problems.none { "해시" in it })
            assertFalse(old.ledger.isEmpty())
            assertEquals(LedgerKind.TRIP, old.ledger.first().kind)
        } finally {
            dir.deleteRecursively()
        }
    }
}
