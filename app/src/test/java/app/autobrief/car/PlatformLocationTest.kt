package app.autobrief.car

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.ui.MainActivity
import app.autobrief.car.vehicle.PlatformLocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * The platform GPS fallback on Android: asked for once, used only with the grant. The source is driven directly
 * (VehicleMonitorService starts it in onCreate and on every start command, stops it in onDestroy): creating the
 * service here would leave the Pleos SDK retrying its bind into later Robolectric tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class PlatformLocationTest {
    @get:Rule val drain = DrainMainLooper()

    private val app get() = RuntimeEnvironment.getApplication() as AutoBriefApp
    private val lm get() = app.getSystemService(LocationManager::class.java)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** The app's core: its stored logbook is read off the main thread at start. */
    private val core by lazy { app.loadedCore() }

    private fun source(): PlatformLocationSource {
        val c = core
        return PlatformLocationSource(app) { fix -> c.onPlatformFix(fix) }
    }

    @Test fun withoutTheGrantNothingIsRequestedAndTheAppWorksAsBefore() {
        val gps = source()
        assertFalse(gps.start())
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertEquals(AutoBriefCore.PlaceResult.NO_LOCATION, core.registerPlaceHere("집", PlaceKind.HOME))
    }

    @Test fun withTheGrantGnssFixesReachTheLogbookEveryFiveSecondsUntilItStops() {
        grant()
        val gps = source()
        assertTrue(gps.start())
        assertEquals(5_000L, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        shadowOf(lm).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = SampleHistory.HOME.point.lat
                longitude = SampleHistory.HOME.point.lon
                accuracy = 8f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
        idle()
        assertEquals(AutoBriefCore.PlaceResult.SAVED, core.registerPlaceHere("집", PlaceKind.HOME))
        assertEquals(SampleHistory.HOME.point, core.settings.value.places.single().point)
        gps.stop()
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }

    @Test fun aGrantGivenLaterTakesEffectOnTheNextStart() {
        val gps = source()
        assertFalse(gps.start())
        grant()
        assertTrue("the service calls start() again on its next start command", gps.start())
        assertTrue("and only once", gps.start())
        assertEquals(1, shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)
        gps.stop()
    }

    @Test fun familyDrivePausesTheUpdatesAndAStartCommandMeanwhileDoesNotRestartThem() {
        grant()
        val gps = source()
        assertTrue(gps.start())
        gps.setPaused(true) // the core's locationPaused (가족 운행), applied by the monitor
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertFalse("the service's next start command asks for nothing", gps.start())
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        gps.setPaused(false) // the family trip closed
        assertTrue(gps.running)
        assertEquals(1, shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)
        gps.stop()
    }

    @Test fun theFirstLaunchExplainsWhyAndAsksOnceOnly() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(
            "내비가 위치를 주지 못할 때 차량 GPS로 집, 회사, 거래처를 알아보기 위해서입니다. 위치는 저장하지 않습니다",
            shadowOf(dialog).message.toString(),
        )
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        val asked = shadowOf(first.get()).lastRequestedPermission
        assertTrue(asked.requestedPermissions.toList().containsAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        assertSame("no second question", dialog, ShadowAlertDialog.getLatestAlertDialog())
        second.pause().stop().destroy()
    }

    @Test fun noQuestionWhenTheGrantIsThereAlready() {
        grant()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        activity.pause().stop().destroy()
    }

    @Test fun theManifestDeclaresLocationForTheMonitorOnly() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.FOREGROUND_SERVICE_LOCATION")
            .forEach { assertTrue(it, "<uses-permission android:name=\"$it\" />" in manifest) }
        assertTrue("android:foregroundServiceType=\"specialUse|location\"" in manifest)
        assertTrue("never in the background without the service" , "ACCESS_BACKGROUND_LOCATION" !in manifest)
    }
}
