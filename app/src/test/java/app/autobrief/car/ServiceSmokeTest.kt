package app.autobrief.car

import android.os.Looper
import app.autobrief.car.service.VehicleMonitorService
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The monitor service on API 34: startForeground with the special-use type, SDK binding attempts with
 * no Pleos vehicle service present, and the collectors — none of it may crash the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class ServiceSmokeTest {
    @Test fun monitorServiceStartsWithoutAVehicleService() {
        // like a plain AAOS image: the Pleos vehicle and navigation services are not installed, so binding fails
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.declareActionUnbindable("ai.pleos.playground.vehicle.VEHICLE_START")
        app.declareActionUnbindable("ai.pleos.playground.intent.action.NAVI_START")
        // a real device does not call onServiceDisconnected for a connection that never came up
        app.setUnbindServiceCallsOnServiceDisconnected(false)
        val controller = Robolectric.buildService(VehicleMonitorService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        controller.destroy()
    }
}
