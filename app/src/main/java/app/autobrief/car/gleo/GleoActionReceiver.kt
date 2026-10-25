package app.autobrief.car.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.service.VehicleMonitorService

/**
 * Gleo functions declared with `"component": "service"` arrive here via sendBroadcast. The reply goes
 * back as APP_ACTION_RESPONSE well inside Gleo's 7-second window (in-memory updates and one small
 * workbook write).
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        if (function !in GleoContract.FUNCTIONS) return
        val reply = context.core.handleGleo(function, intentParams(intent))
        sendGleoResponse(context, function, reply)
        if (function == GleoContract.SEND_LOGBOOK) VehicleMonitorService.start(context, VehicleMonitorService.ACTION_FLUSH)
    }
}

fun intentParams(intent: Intent) = GleoContract.parseParams(
    intent.getStringExtra(GleoContract.EXTRA_ACTION_PARAM),
    intent.extras?.keySet()?.associateWith { key -> intent.extras?.get(key)?.toString() } ?: emptyMap(),
)

/**
 * The app-integration guide sends this reply as an implicit broadcast and names no package or permission
 * for Gleo, so the payload is kept to non-sensitive fields (status, an English sentence, distances and
 * purposes) and never carries places, addresses or names.
 */
fun sendGleoResponse(context: Context, function: String, reply: AutoBriefCore.GleoReply) {
    val response = Intent(GleoContract.RESPONSE_ACTION)
        .putExtra(GleoContract.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(GleoContract.EXTRA_RESPONSE, GleoContract.response(function, reply))
    context.sendBroadcast(response)
}
