package app.autobrief.car.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the monitor at boot so trips are logged even if nobody opens the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VehicleMonitorService.start(context)
        }
    }
}
