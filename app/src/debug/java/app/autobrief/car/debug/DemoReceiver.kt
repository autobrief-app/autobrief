package app.autobrief.car.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.autobrief.car.AutoBriefApp
import app.autobrief.car.autoBrief
import app.autobrief.car.background
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.service.VehicleMonitorService
import kotlinx.coroutines.launch

/**
 * `adb shell am broadcast -a app.autobrief.car.debug.DEMO -p app.autobrief.car --es cmd load_sample
 * [--es webhook http://10.0.2.2:8787/hook]` — switches to the demo space and loads the labelled
 * fictional history; `cmd reset` wipes the demo space, `cmd live` returns to the real logbook,
 * `cmd gleo --es function <name> --es params '<json>'` runs a Gleo function the way Gleo would (adb cannot
 * hold Gleo's signature permission that guards the real receiver).
 *
 * The broadcast stays open (goAsync) until the command ran, after the stored logbook was read when the broadcast started
 * the process: `am broadcast` returns once it is done.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending: PendingResult? = goAsync() // null when called directly (tests), not by a broadcast
        context.background.scope.launch {
            try {
                run(context, context.autoBrief.awaitCore(), intent)
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun run(context: Context, core: AutoBriefCore, intent: Intent) {
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
            "gleo" -> {
                // what Gleo would send: --es function LOG_TRIP_PURPOSE --es params '{"purpose":"BUSINESS"}'
                val function = intent.getStringExtra("function") ?: return
                val params = app.autobrief.car.domain.GleoContract.parseParams(intent.getStringExtra("params") ?: "{}")
                // like GleoActionReceiver: SEND_LOGBOOK's workbook is made off the main thread before the reply
                val reply = core.handleGleo(function, params)
                Log.i(AutoBriefApp.LOG_TAG, "GLEO_REPLY function=$function status=${reply.status} message=${reply.message}")
            }
            "reset" -> if (core.demoMode) core.resetDemo()
            "live" -> core.setDemoMode(false, null)
            else -> Log.i(AutoBriefApp.LOG_TAG, "DEMO_STATUS space=${core.activeSpace.value} trips=${core.trips.value.size} code=${core.integrity.value.code}")
        }
    }
}
