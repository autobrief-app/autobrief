package app.autobrief.car.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.autobrief.car.AutoBriefApp
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.service.VehicleMonitorService

/**
 * `adb shell am broadcast -a app.autobrief.car.debug.DEMO -p app.autobrief.car --es cmd load_sample
 * [--es webhook http://10.0.2.2:8787/hook]` — switches to the demo space and loads the labelled
 * fictional history; `cmd reset` wipes the demo space, `cmd live` returns to the real logbook.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = context.core
        when (intent.getStringExtra("cmd")) {
            "load_sample" -> {
                if (core.setDemoMode(true, null) != AutoBriefCore.SettingsResult.SAVED) {
                    Log.w(AutoBriefApp.LOG_TAG, "DEMO_REFUSED (admin PIN set or trip in progress)")
                    return
                }
                val webhook = intent.getStringExtra("webhook")?.takeIf { AutoBriefCore.isAllowedEndpoint(it) } ?: ""
                core.loadSampleHistory(webhook)
                VehicleMonitorService.start(context, VehicleMonitorService.ACTION_FLUSH)
            }
            "reset" -> if (core.demoMode) core.resetDemo()
            "live" -> core.setDemoMode(false, null)
            else -> Log.i(AutoBriefApp.LOG_TAG, "DEMO_STATUS space=${core.activeSpace.value} trips=${core.trips.value.size} code=${core.integrity.value.code}")
        }
    }
}
