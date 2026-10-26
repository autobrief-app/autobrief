package app.autobrief.car

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.gleo.GleoActionReceiver
import app.autobrief.car.service.BootReceiver
import app.autobrief.car.service.VehicleMonitorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** What starts the monitor without the screen: boot, an app update, a Gleo request; a refused start never crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutoBriefApp::class)
class ReceiversTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun bootAndAnAppUpdateBothStartTheMonitor() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BootReceiver().onReceive(app, Intent(action))
            val started = shadowOf(app).nextStartedService
            assertEquals(action, VehicleMonitorService::class.java.name, started?.component?.className)
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("<action android:name=\"android.intent.action.MY_PACKAGE_REPLACED\" />" in manifest)
    }

    @Test fun aRefusedServiceStartDoesNotCrashTheGleoReceiverAndGleoStillGetsItsReply() {
        // Android 12+ refuses a foreground-service start from the background with ForegroundServiceStartNotAllowedException
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName = throw ForegroundServiceStartNotAllowedException("not allowed")
        }
        GleoActionReceiver().onReceive(refusing, Intent(GleoContract.SEND_LOGBOOK))
        val reply = shadowOf(app).broadcastIntents.last()
        assertEquals(GleoContract.RESPONSE_ACTION, reply.action)
        // other start failures (a SecurityException, an IllegalStateException) are caught the same way
        val failing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName = throw SecurityException("no")
        }
        assertEquals(false, VehicleMonitorService.start(failing, VehicleMonitorService.ACTION_FLUSH))
        BootReceiver().onReceive(failing, Intent(Intent.ACTION_BOOT_COMPLETED))
    }
}
