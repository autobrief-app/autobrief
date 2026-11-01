package app.autobrief.car.ui

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import app.autobrief.car.R
import app.autobrief.car.autoBrief
import app.autobrief.car.service.VehicleMonitorService
import app.autobrief.car.vehicle.PlatformLocationSource

class MainActivity : ComponentActivity() {

    private val tab = mutableStateOf(Tab.HOME)
    private var monitorStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startMonitorRunnable = Runnable { ensureMonitorStarted() }

    /** Once allowed, the running monitor is started again: it takes the car's GPS from then on. */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) VehicleMonitorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoBrief.claimLoadedCoreIfReady()
        setContent {
            AutoBriefRoot(autoBrief.coreState, tab, onFlush = { VehicleMonitorService.start(this, VehicleMonitorService.ACTION_FLUSH) })
        }
        askLocationOnce()
        // Fallback if window focus is not delivered (e.g. headless activity host); starts counting after Frame 1 attach.
        window.decorView.post {
            if (!monitorStarted) mainHandler.postDelayed(startMonitorRunnable, FOCUS_FALLBACK_START_MS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !monitorStarted) {
            mainHandler.removeCallbacks(startMonitorRunnable)
            mainHandler.postDelayed(startMonitorRunnable, AFTER_FOCUS_START_MS)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(startMonitorRunnable)
        super.onDestroy()
    }

    private fun ensureMonitorStarted() {
        if (monitorStarted || isFinishing || isDestroyed) return
        monitorStarted = true
        // the monitor normally starts at boot; opening the app makes sure it runs (e.g. right after install)
        VehicleMonitorService.start(this)
    }

    /**
     * The car's GPS is optional: asked once, at the first launch, with the reason. Declined, the app works as before,
     * from navigation's position alone.
     */
    private fun askLocationOnce() {
        if (PlatformLocationSource.permitted(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOCATION_ASKED, false)) return
        prefs.edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.location_title)
            .setMessage(R.string.location_rationale)
            .setPositiveButton(R.string.location_continue) { _, _ ->
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            .setNegativeButton(R.string.location_later, null)
            .show()
    }

    private companion object {
        const val PREFS = "ui"
        const val KEY_LOCATION_ASKED = "location_asked"
        const val AFTER_FOCUS_START_MS = 500L
        const val FOCUS_FALLBACK_START_MS = 8_000L
    }
}
