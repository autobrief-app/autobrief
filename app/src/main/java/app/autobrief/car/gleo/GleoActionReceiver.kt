package app.autobrief.car.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract

/**
 * Gleo functions declared with `"component": "service"` arrive here via sendBroadcast. The reply goes
 * back as APP_ACTION_RESPONSE well inside Gleo's 7-second window (the work is a few in-memory updates).
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        if (function !in GleoContract.FUNCTIONS) return
        val core = context.core
        val reply = core.handleGleo(function, intentParams(intent))
        sendGleoResponse(context, function, reply, core.gleoMode())
    }
}

fun intentParams(intent: Intent) = GleoContract.parseParams(
    intent.getStringExtra(GleoContract.EXTRA_ACTION_PARAM),
    intent.extras?.keySet()?.associateWith { key -> intent.extras?.get(key)?.toString() } ?: emptyMap(),
)

fun sendGleoResponse(context: Context, function: String, reply: AutoBriefCore.GleoReply, mode: String) {
    val response = Intent(GleoContract.RESPONSE_ACTION)
        .putExtra(GleoContract.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(GleoContract.EXTRA_RESPONSE, GleoContract.response(function, reply, mode))
    context.sendBroadcast(response)
}
