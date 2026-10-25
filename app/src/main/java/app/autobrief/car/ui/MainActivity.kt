package app.autobrief.car.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import app.autobrief.car.core
import app.autobrief.car.service.VehicleMonitorService

class MainActivity : ComponentActivity() {

    private val tab = mutableStateOf(Tab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the monitor normally starts at boot; opening the app makes sure it runs (e.g. right after install)
        VehicleMonitorService.start(this)
        setContent {
            AutoBriefScreen(core = core, tab = tab, onFlush = { VehicleMonitorService.start(this, VehicleMonitorService.ACTION_FLUSH) })
        }
    }
}
