package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.SettingsResult
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.Ledger
import app.autobrief.car.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admin PIN: how it is stored, what counts as a wrong guess, and a lockout the car's clock cannot shorten. */
class AdminPinTest {
    private var wall = Harness.at(java.time.LocalDate.of(2026, 10, 23), 8, 0)
    private var mono = 1_000L

    private fun core(store: MemoryStore) = AutoBriefCore(store, clock = { wall }, monotonic = { mono })

    @Test fun aMissingPinIsAskedForAndNeverCountsAsAWrongGuess() {
        val store = MemoryStore()
        val c = core(store)
        assertEquals(SettingsResult.SAVED, c.saveSettings(c.settings.value, "4321", null))
        // the tour button on Home passes no PIN: tapping it any number of times must not lock the admin out
        repeat(12) { assertEquals(SettingsResult.PIN_REQUIRED, c.setDemoMode(true, null)) }
        assertEquals(SettingsResult.PIN_REQUIRED, c.setDemoMode(true, ""))
        assertEquals(0, c.settings.value.adminPinFailures)
        assertEquals(SettingsResult.SAVED, c.setDemoMode(true, "4321"))
    }

    @Test fun thePinIsStoredAsPbkdf2WithARandomSalt() {
        val a = core(MemoryStore()).also { it.saveSettings(it.settings.value, "4321", null) }.settings.value
        val b = core(MemoryStore()).also { it.saveSettings(it.settings.value, "4321", null) }.settings.value
        assertTrue(a.adminPinHash, a.adminPinHash.startsWith("pbkdf2-sha256:"))
        assertNotEquals("a fresh random salt per PIN", a.adminPinSalt, b.adminPinSalt)
        assertNotEquals(a.adminPinHash, b.adminPinHash)
        assertTrue("16 random bytes", a.adminPinSalt.length >= 32)
        assertFalse(a.adminPinHash.contains(Ledger.sha256(a.adminPinSalt + "4321")))
    }

    @Test fun anOldShaPinStillWorksAndIsRehashedOnTheNextCorrectEntry() {
        val store = MemoryStore()
        store.saveSettings(Space.LIVE, AppSettings(adminPinSalt = "18f1a2b3c4d", adminPinHash = Ledger.sha256("18f1a2b3c4d" + "4321")))
        val c = core(store)
        assertEquals(SettingsResult.PIN_REQUIRED, c.setDemoMode(true, "0000"))
        assertEquals(SettingsResult.SAVED, c.setDemoMode(true, "4321"))
        val live = store.data.getValue(Space.LIVE).settings
        assertTrue(live.adminPinHash, live.adminPinHash.startsWith("pbkdf2-sha256:"))
        assertNotEquals("18f1a2b3c4d", live.adminPinSalt)
        assertEquals(0, live.adminPinFailures)
        // the migrated PIN keeps working, also after a restart
        assertEquals(SettingsResult.SAVED, c.setDemoMode(false, null))
        assertEquals(SettingsResult.SAVED, core(store).setDemoMode(true, "4321"))
    }

    @Test fun theLockoutRunsOnTheMonotonicClockSoSettingTheCarClockForwardDoesNotEndIt() {
        val store = MemoryStore()
        val c = core(store)
        c.saveSettings(c.settings.value, "4321", null)
        repeat(4) { assertEquals(SettingsResult.PIN_REQUIRED, c.setDemoMode(true, "0000")) }
        assertEquals(SettingsResult.PIN_LOCKED, c.setDemoMode(true, "0000"))
        wall += 24 * 3_600_000L // the clock is moved a day ahead; no time passed
        assertEquals(SettingsResult.PIN_LOCKED, c.setDemoMode(true, "4321"))
        mono += AutoBriefCore.PIN_LOCK_MS + 1
        assertEquals(SettingsResult.SAVED, c.setDemoMode(true, "4321"))
    }

    @Test fun aRestartKeepsTheLockButAClockSetBackCannotStretchItBeyondTheLongestLock() {
        val store = MemoryStore()
        val c = core(store)
        c.saveSettings(c.settings.value, "4321", null)
        repeat(5) { c.setDemoMode(true, "0000") }
        assertEquals(SettingsResult.PIN_LOCKED, core(store).setDemoMode(true, "4321"))
        wall -= 30L * 24 * 3_600_000L // a restart with the clock a month behind
        val again = core(store)
        assertEquals(SettingsResult.PIN_LOCKED, again.setDemoMode(true, "4321"))
        mono += AutoBriefCore.PIN_LOCK_MAX_MS + 1
        assertEquals(SettingsResult.SAVED, again.setDemoMode(true, "4321"))
    }
}
