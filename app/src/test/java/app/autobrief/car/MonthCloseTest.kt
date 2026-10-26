package app.autobrief.car

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The automatic month close: once per month, after the workbook is really written, never across an open trip. */
class MonthCloseTest {
    private val base = AppSettings(entityName = "가상예시 주식회사", plateNumber = "00가 0000")
    private var now = Harness.at(LocalDate.of(2026, 10, 30), 9, 0)
    private var failWrites = false
    private val written = mutableListOf<String>()
    private val store = MemoryStore().also { it.saveSettings(Space.LIVE, base) }
    private val core = AutoBriefCore(store, { _, name, _ ->
        if (failWrites) throw IOException("No space left on device")
        written += name
        name
    }, { now })

    private fun drive(fromKm: Double, km: Double, at: Long) {
        now = at
        core.onVehicle(VehicleEvent.Odometer(fromKm)); core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        now += 60_000; core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        now += 20 * 60_000; core.onVehicle(VehicleEvent.Odometer(fromKm + km)); core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
    }

    @Test fun aMonthIsMarkedClosedOnlyAfterItsWorkbookIsWritten() {
        drive(100.0, 20.0, Harness.at(LocalDate.of(2026, 10, 30), 9, 0))
        now = Harness.at(LocalDate.of(2026, 11, 2), 8, 0)
        failWrites = true
        core.tick()
        assertEquals("", core.settings.value.lastMonthlyCloseYm)
        assertNull(core.notice.value)
        failWrites = false
        now += AutoBriefCore.MONTH_CLOSE_RETRY_MS
        core.tick()
        assertEquals("2026-10", core.settings.value.lastMonthlyCloseYm)
        assertTrue(written.any { it == "운행기록부_2026-10-01_2026-10-31.xlsx" })
        assertNotNull(core.notice.value)
    }

    @Test fun theCloseWaitsForATripThatIsStillOpen() {
        drive(100.0, 20.0, Harness.at(LocalDate.of(2026, 10, 30), 9, 0))
        now = Harness.at(LocalDate.of(2026, 10, 31), 23, 50)
        core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE)) // a drive across midnight into November
        now = Harness.at(LocalDate.of(2026, 11, 1), 0, 5)
        core.tick()
        assertEquals("", core.settings.value.lastMonthlyCloseYm)
        now = Harness.at(LocalDate.of(2026, 11, 1), 0, 30)
        core.onVehicle(VehicleEvent.Odometer(135.0)); core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        assertEquals("2026-10", core.settings.value.lastMonthlyCloseYm)
        assertTrue(core.notice.value!!.text, core.notice.value!!.text.startsWith("10월 운행기록부를 만들었습니다: 2건"))
    }

    @Test fun everyMonthSinceTheLastCloseIsClosedInOrder() {
        // trips in August and September, but the last close the settings remember is July (the app missed two month ends)
        val history = Harness(base, startAt = Harness.at(LocalDate.of(2026, 8, 10), 9, 0))
        history.drive(100.0, 10.0, null, null)
        history.now = Harness.at(LocalDate.of(2026, 9, 14), 9, 0)
        history.drive(110.0, 10.0, null, null)
        val live = history.store.data.getValue(Space.LIVE)
        store.data[Space.LIVE] = live.copy(settings = base.copy(lastMonthlyCloseYm = "2026-07"))
        now = Harness.at(LocalDate.of(2026, 10, 2), 8, 0)
        val c = AutoBriefCore(store, { _, name, _ -> written += name; name }, { now })
        c.tick()
        assertEquals(listOf("운행기록부_2026-08-01_2026-08-31.xlsx", "운행기록부_2026-09-01_2026-09-30.xlsx"), written.filter { it.endsWith(".xlsx") })
        assertEquals("2026-09", c.settings.value.lastMonthlyCloseYm)
        assertTrue(c.notice.value!!.text.startsWith("9월"))
    }
}
