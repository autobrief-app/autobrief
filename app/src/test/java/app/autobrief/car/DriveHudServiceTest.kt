package app.autobrief.car

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.service.DriveHud
import app.autobrief.car.service.VehicleMonitorService
import app.autobrief.car.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

/**
 * The status pill of the real monitor service: a system overlay window that appears when a trip is recorded behind
 * another app, follows the distance, hides while the app's own screen shows or the permission is gone, and goes when the
 * trip ends. The notification says the same line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class DriveHudServiceTest {
    @get:Rule val stalePleosBinds = RefuseStalePleosBinds()

    private val app get() = RuntimeEnvironment.getApplication() as AutoBriefApp

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The overlay windows the app holds now (other windows, like an activity's, are not the pill). */
    private fun pills(): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java)).views
            .filter { (it.layoutParams as? WindowManager.LayoutParams)?.title == DriveHud.WINDOW_TITLE }

    private fun texts(pill: View): List<String> = (pill as ViewGroup).let { g ->
        (0 until g.childCount).mapNotNull { (g.getChildAt(it) as? TextView)?.text?.toString() }
    }

    private fun hudLog(): List<String> = ShadowLog.getLogsForTag(AutoBriefApp.LOG_TAG).map { it.msg }.filter { it.startsWith("HUD_") }

    private fun notificationText(): String =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(1).extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun thePillShowsWhileATripIsRecordedBehindAnotherAppFollowsTheDistanceAndGoesWhenTheTripEnds() {
        shadowOf(app).apply {
            RefuseStalePleosBinds.PLEOS_BIND_ACTIONS.forEach(::declareActionUnbindable)
            setUnbindServiceCallsOnServiceDisconnected(false)
        }
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        val core = app.loadedCore()
        runMainLooperUntil("the monitor to take the core") { core.naviControl != null }
        val parked = app.getString(R.string.monitor_text)
        assertEquals(parked, notificationText())
        assertTrue("no window before a trip", pills().isEmpty())

        // a trip opens: the car leaves P
        core.onVehicle(VehicleEvent.Connection(true))
        core.onVehicle(VehicleEvent.Odometer(45_200.0))
        core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        idle()
        assertNotNull("a trip is being recorded", core.openTrip.value)
        val pill = pills().single()
        assertEquals(listOf("운행 기록 중", "+0.0 km"), texts(pill))
        assertEquals(listOf("HUD_SHOWN km=0.0"), hudLog())
        assertEquals("운행 기록 중 · +0.0 km", notificationText())

        // the window: an application overlay as large as the pill, top end, 16dp in, never focusable; the pill is at least
        // 56dp high and its text at least 24sp
        val lp = pill.layoutParams as WindowManager.LayoutParams
        val density = app.resources.displayMetrics.density
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, lp.type)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, lp.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, lp.height)
        assertEquals(Gravity.TOP or Gravity.END, lp.gravity)
        assertEquals((16 * density).toInt(), lp.x)
        assertEquals((16 * density).toInt(), lp.y)
        assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals("static: no window animation", 0, lp.windowAnimations)
        assertTrue(pill.minimumHeight >= (56 * density).toInt())
        val floorPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, app.resources.displayMetrics)
        (pill as ViewGroup).let { g -> (0 until g.childCount).mapNotNull { g.getChildAt(it) as? TextView } }
            .forEach { assertTrue("${it.text} is ${it.textSize}px", it.textSize >= floorPx) }

        // the distance changes: the same window says the new figure
        core.onVehicle(VehicleEvent.Odometer(45_212.0))
        idle()
        assertSame(pill, pills().single())
        assertEquals(listOf("운행 기록 중", "+12.0 km"), texts(pill))
        assertEquals("운행 기록 중 · +12.0 km", notificationText())
        assertEquals("an update is no transition", listOf("HUD_SHOWN km=0.0"), hudLog())

        // a tap opens the app
        pill.performClick()
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)

        // the app's own screen shows: no pill over it; behind another app again: the pill is back
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        idle()
        assertTrue(pills().isEmpty())
        assertEquals("HUD_HIDDEN reason=app_visible", hudLog().last())
        screen.pause().stop()
        idle()
        assertEquals(listOf("운행 기록 중", "+12.0 km"), texts(pills().single()))
        assertEquals("HUD_SHOWN km=12.0", hudLog().last())

        // the permission is taken back while driving: seen at the monitor's next tick, and given again likewise
        ShadowSettings.setCanDrawOverlays(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(pills().isEmpty())
        assertEquals("HUD_HIDDEN reason=no_permission", hudLog().last())
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, pills().size)

        // parked: the trip is closed, the pill goes, the notification says the parked line again
        core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        idle()
        assertNull(core.openTrip.value)
        assertTrue(pills().isEmpty())
        assertEquals("HUD_HIDDEN reason=trip_ended", hudLog().last())
        assertEquals(parked, notificationText())
        assertEquals(
            listOf(
                "HUD_SHOWN km=0.0", "HUD_HIDDEN reason=app_visible", "HUD_SHOWN km=12.0", "HUD_HIDDEN reason=no_permission",
                "HUD_SHOWN km=12.0", "HUD_HIDDEN reason=trip_ended",
            ),
            hudLog(),
        )
        screen.destroy()
        controller.destroy()
    }

    @Test fun theServiceTakesItsWindowWithIt() {
        shadowOf(app).apply {
            RefuseStalePleosBinds.PLEOS_BIND_ACTIONS.forEach(::declareActionUnbindable)
            setUnbindServiceCallsOnServiceDisconnected(false)
        }
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        val core = app.loadedCore()
        runMainLooperUntil("the monitor to take the core") { core.naviControl != null }
        core.onVehicle(VehicleEvent.Connection(true))
        core.onVehicle(VehicleEvent.Odometer(45_200.0))
        core.onVehicle(VehicleEvent.GearChanged(Gear.PARK))
        core.onVehicle(VehicleEvent.GearChanged(Gear.DRIVE))
        idle()
        assertEquals(1, pills().size)
        controller.destroy()
        assertTrue(pills().isEmpty())
    }

    @Test fun withoutThePermissionNoWindowIsAskedForAndItIsSaidOncePerTrip() {
        val windows = CountingWindows()
        val lines = mutableListOf<String>()
        val hud = DriveHud(windows.context(app), lines::add) { false }
        repeat(3) { hud.update(tripInProgress = true, distanceKm = it.toDouble(), family = false, appVisible = false) }
        assertEquals(0, windows.adds)
        assertEquals(listOf("HUD_HIDDEN reason=no_permission"), lines)
        assertFalse(hud.isShown)
    }

    @Test fun aWindowAndroidRefusesIsSaidOnceAndNotAskedForAgainUntilTheNextTrip() {
        for (refusal in listOf<RuntimeException>(WindowManager.BadTokenException("permission denied for window type 2038"), SecurityException("not allowed"))) {
            val windows = CountingWindows(refusal)
            val lines = mutableListOf<String>()
            val hud = DriveHud(windows.context(app), lines::add) { true }
            repeat(4) { hud.update(tripInProgress = true, distanceKm = it.toDouble(), family = false, appVisible = false) }
            assertEquals("asked once, not in a loop", 1, windows.adds)
            assertEquals(listOf("HUD_HIDDEN reason=no_permission error=${refusal::class.java.simpleName}"), lines)
            assertFalse(hud.isShown)
            // the trip ends without a second line; the next trip asks once more
            hud.update(tripInProgress = false, distanceKm = 0.0, family = false, appVisible = false)
            assertEquals(1, lines.size)
            hud.update(tripInProgress = true, distanceKm = 0.0, family = false, appVisible = false)
            assertEquals(2, windows.adds)
        }
    }

    @Test fun aFamilyDriveShowsTheFamilyWording() {
        val windows = CountingWindows()
        val hud = DriveHud(windows.context(app), {}) { true }
        hud.update(tripInProgress = true, distanceKm = 3.2, family = true, appVisible = false)
        assertEquals(listOf("가족 운행 중 (위치 기록 안 함)", "+3.2 km"), texts(windows.added.single()))
    }

    /** A window manager that counts what it is asked to add, and refuses it with [refusal] when given one. */
    private class CountingWindows(private val refusal: RuntimeException? = null) : WindowManager {
        var adds = 0
        val added = mutableListOf<View>()

        fun context(base: Context): Context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) this@CountingWindows else super.getSystemService(name)
        }

        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            adds++
            refusal?.let { throw it }
            added += view
        }

        override fun removeView(view: View) { added -= view }
        override fun removeViewImmediate(view: View) { added -= view }
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) = Unit
        @Deprecated("the platform's") override fun getDefaultDisplay(): Display = throw UnsupportedOperationException()
    }
}
