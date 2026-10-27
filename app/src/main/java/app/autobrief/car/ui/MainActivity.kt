package app.autobrief.car.ui

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
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

    /** Once allowed, the running monitor is started again: it takes the car's GPS from then on. */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) VehicleMonitorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the monitor normally starts at boot; opening the app makes sure it runs (e.g. right after install)
        VehicleMonitorService.start(this)
        setContent {
            AutoBriefRoot(autoBrief.coreState, tab, onFlush = { VehicleMonitorService.start(this, VehicleMonitorService.ACTION_FLUSH) })
        }
        askLocationOnce()
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
    }
}
