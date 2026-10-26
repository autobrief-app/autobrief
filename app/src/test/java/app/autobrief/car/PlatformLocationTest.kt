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
import app.autobrief.car.service.VehicleMonitorService
import app.autobrief.car.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** The platform GPS fallback on Android: asked for once, used only while the monitor runs and only with the grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class PlatformLocationTest {
    private val app get() = RuntimeEnvironment.getApplication() as AutoBriefApp
    private val lm get() = app.getSystemService(LocationManager::class.java)

    @Before fun noPleosServices() {
        // like a plain AAOS image: binding to the Pleos vehicle and navigation services fails (see ServiceSmokeTest)
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        shadow.declareActionUnbindable("ai.pleos.playground.vehicle.VEHICLE_START")
        shadow.declareActionUnbindable("ai.pleos.playground.intent.action.NAVI_START")
        shadow.setUnbindServiceCallsOnServiceDisconnected(false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test fun withoutTheGrantNoLocationIsAskedForAndTheAppWorksAsBefore() {
        val service = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        idle()
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        service.destroy()
    }

    @Test fun withTheGrantGnssFixesReachTheLogbookEveryFiveSecondsWhileTheServiceRuns() {
        grant()
        val service = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        idle()
        val request = shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).single()
        assertEquals(5_000L, request.intervalMillis)
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
        assertEquals(AutoBriefCore.PlaceResult.SAVED, app.core.registerPlaceHere("집", PlaceKind.HOME))
        assertEquals(SampleHistory.HOME.point, app.core.settings.value.places.single().point)
        service.destroy()
        assertTrue("stops with the service", shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }

    @Test fun aGrantGivenAfterTheServiceStartedTakesEffectOnTheNextStart() {
        val service = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        idle()
        assertTrue(shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        grant()
        service.startCommand(0, 2) // MainActivity starts the service again once the driver allowed it
        idle()
        assertEquals(1, shadowOf(lm).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)
        service.destroy()
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
