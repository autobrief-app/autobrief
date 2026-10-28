package app.autobrief.car

import android.content.Intent
import app.autobrief.car.debug.DemoReceiver
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.FuelBasis
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The demo kit sets 내 차 mode and a fuel price for a scripted scene (`--es cmd fuel`), in the demo space only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class DemoFuelCommandTest {
    @get:Rule val drain = DrainMainLooper()

    private val app get() = RuntimeEnvironment.getApplication() as AutoBriefApp

    private fun fuel(vararg extras: Pair<String, String>) =
        DemoReceiver().onReceive(app, Intent("app.autobrief.car.debug.DEMO").putExtra("cmd", "fuel").apply { extras.forEach { (k, v) -> putExtra(k, v) } })

    @Test fun theFuelCommandSetsOwnCarModeAndThePriceInTheDemoSpaceOnly() {
        val core = app.loadedCore()
        fuel("mode" to "own", "price" to "1650")
        runMainLooperUntil("the command to run") { true }
        assertEquals("refused outside the demo space", CarUse.COMPANY, core.settings.value.carUse)

        check(core.setDemoModeNow(true, null) == AutoBriefCore.SettingsResult.SAVED)
        fuel("mode" to "own", "price" to "1650", "basis" to "commute", "kmpl" to "12.1")
        runMainLooperUntil("the settings to change") { core.settings.value.carUse == CarUse.OWN }
        val s = core.settings.value
        assertEquals(1_650, s.fuelPriceKrwPerLiter)
        assertEquals(FuelBasis.WITH_COMMUTE, s.fuelBasis)
        assertEquals(12.1, s.manualKmPerLiter, 0.0)
        fuel("mode" to "company")
        runMainLooperUntil("company mode again") { core.settings.value.carUse == CarUse.COMPANY }
        assertEquals("what was not named stays", 1_650, core.settings.value.fuelPriceKrwPerLiter)
    }
}
