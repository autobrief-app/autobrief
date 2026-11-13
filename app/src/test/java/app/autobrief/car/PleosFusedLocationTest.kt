package app.autobrief.car

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.PlatformFix
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.ui.MainActivity
import app.autobrief.car.vehicle.PleosFusedLocationSource
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
import java.time.Duration

/**
 * The car's own position through the Pleos FusedLocation SDK: asked for once, used only with the grant and only where
 * the FusedLocation Service is installed. Robolectric has no such service, so a fake client stands in for the SDK's
 * calls; the source is driven directly (VehicleMonitorService starts it in onCreate and on every start command, stops
 * it in onDestroy): creating the service here would leave the Pleos SDKs retrying their binds into later tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class PleosFusedLocationTest {
    @get:Rule val drain = DrainMainLooper()

    private val app get() = RuntimeEnvironment.getApplication() as AutoBriefApp

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun grant() = shadowOf(app).grantPermissions(PleosFusedLocationSource.PERMISSION)

    /** The app's core: its stored logbook is read off the main thread at start. */
    private val core by lazy { app.loadedCore() }

    /** Stands in for the FusedLocation Service: records the SDK calls and pushes fixes to the registered listener. */
    private class FakeClient(private val supported: Boolean = true) : PleosFusedLocationSource.FusedClient {
        val calls = mutableListOf<String>()
        var listener: FusedLocationListener? = null
        override fun isSupported() = supported.also { calls += "isSupported" }
        override fun initialize() { calls += "initialize" }
        override fun register(listener: FusedLocationListener) { calls += "register"; this.listener = listener }
        override fun unregister(listener: FusedLocationListener) { calls += "unregister"; if (this.listener === listener) this.listener = null }
        override fun lastKnown(listener: FusedLocationListener) { calls += "lastKnown" }
        override fun release() { calls += "release" }
        fun push(point: GeoPoint, epochMs: Long = System.currentTimeMillis()) =
            listener?.onChanged(LocationData(point.lat, point.lon, 0.0, 0f, 5f, epochMs))
    }

    private val clients = mutableListOf<FakeClient>()

    private fun source(supported: Boolean = true, emit: ((PlatformFix) -> Unit)? = null): PleosFusedLocationSource {
        val connect: (Context) -> PleosFusedLocationSource.FusedClient = { FakeClient(supported).also { clients += it } }
        return PleosFusedLocationSource(app, connect, emit ?: { fix -> core.onPlatformFix(fix) })
    }

    @Test fun withoutTheGrantNothingIsAskedForAndTheAppWorksAsBefore() {
        val gps = source()
        assertFalse(gps.start())
        assertTrue("no SDK instance without the grant", clients.isEmpty())
        assertEquals(AutoBriefCore.PlaceResult.NO_LOCATION, core.registerPlaceHere("집", PlaceKind.HOME))
    }

    @Test fun withTheGrantFusedFixesReachTheLogbookUntilItStops() {
        grant()
        val gps = source()
        assertTrue(gps.start())
        val sdk = clients.single()
        assertEquals(listOf("isSupported", "initialize", "register", "lastKnown"), sdk.calls)
        sdk.push(SampleHistory.HOME.point)
        idle()
        assertEquals(AutoBriefCore.PlaceResult.SAVED, core.registerPlaceHere("집", PlaceKind.HOME))
        assertEquals(SampleHistory.HOME.point, core.settings.value.places.single().point)
        gps.stop()
        assertEquals(listOf("unregister", "release"), sdk.calls.takeLast(2))
        assertFalse(gps.running)
    }

    @Test fun whereTheServiceIsNotInstalledNothingIsInitialised() {
        grant()
        val gps = source(supported = false)
        assertFalse(gps.start())
        assertEquals(listOf("isSupported"), clients.single().calls)
    }

    @Test fun aGrantGivenLaterTakesEffectOnTheNextStartAndOnlyOnce() {
        val gps = source()
        assertFalse(gps.start())
        grant()
        assertTrue("the service calls start() again on its next start command", gps.start())
        assertTrue("and only once", gps.start())
        assertEquals(1, clients.size)
        gps.stop()
    }

    @Test fun atMostOneFixEveryFiveSecondsIsPassedOn() {
        grant()
        val got = mutableListOf<PlatformFix>()
        val gps = source { got += it }
        assertTrue(gps.start())
        val sdk = clients.single()
        sdk.push(SampleHistory.HOME.point, epochMs = 1_000L)
        sdk.push(SampleHistory.HOME.point, epochMs = 2_000L)
        idle()
        assertEquals("the second fix within 5 s is dropped", listOf(1_000L), got.map { it.epochMs })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PleosFusedLocationSource.INTERVAL_MS))
        sdk.push(SampleHistory.HOME.point, epochMs = 7_000L)
        idle()
        assertEquals(listOf(1_000L, 7_000L), got.map { it.epochMs })
        assertNull("the SDK's confidence has no documented unit: no accuracy is claimed", got.last().accuracyM)
        sdk.listener?.onChanged(null)
        idle()
        assertEquals("a null location is never a position", 2, got.size)
        gps.stop()
    }

    @Test fun familyDriveReleasesTheSdkDropsLateFixesAndResumesOnANewInstance() {
        grant()
        val got = mutableListOf<PlatformFix>()
        val gps = source { got += it }
        assertTrue(gps.start())
        val first = clients.single()
        val late = first.listener!!
        gps.setPaused(true) // the core's locationPaused (가족 운행), applied by the monitor
        assertEquals(listOf("unregister", "release"), first.calls.takeLast(2))
        late.onChanged(LocationData(SampleHistory.HOME.point.lat, SampleHistory.HOME.point.lon, 0.0, 0f, 5f, 1_000L))
        idle()
        assertTrue("a fix arriving after the pause is not used", got.isEmpty())
        assertFalse("the service's next start command asks for nothing", gps.start())
        assertEquals(1, clients.size)
        gps.setPaused(false) // the family trip closed
        assertTrue(gps.running)
        assertEquals("a released SDK instance is never reused", 2, clients.size)
        assertEquals(listOf("isSupported", "initialize", "register", "lastKnown"), clients.last().calls)
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
        assertEquals(listOf(PleosFusedLocationSource.PERMISSION), asked.requestedPermissions.toList())
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

    @Test fun theManifestAsksForPleosFusedLocationOnlyAndNoAndroidLocation() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("<uses-permission android:name=\"${PleosFusedLocationSource.PERMISSION}\" />" in manifest)
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "FOREGROUND_SERVICE_LOCATION")
            .forEach { assertTrue(it, it !in manifest) }
        assertTrue("android:foregroundServiceType=\"specialUse\"" in manifest)
    }
}
