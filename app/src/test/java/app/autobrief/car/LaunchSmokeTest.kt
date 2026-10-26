package app.autobrief.car

import android.os.Looper
import app.autobrief.car.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The entry path itself: the first composition of MainActivity on a fresh install (empty ledger, no
 * vehicle service bound). v5 crashed exactly here while its domain tests were green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = AutoBriefApp::class)
class LaunchSmokeTest {
    @get:Rule val drain = DrainMainLooper()

    @Test fun firstLaunchOnAnEmptyLedgerDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertTrue(activity.core.trips.value.isEmpty())
        controller.pause().stop().destroy()
    }
}
