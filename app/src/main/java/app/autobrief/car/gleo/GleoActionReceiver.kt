package app.autobrief.car.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.autobrief.car.AutoBriefApp
import app.autobrief.car.background
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.service.VehicleMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Gleo functions declared with `"component": "service"` arrive here via sendBroadcast. The reply goes back as
 * APP_ACTION_RESPONSE inside Gleo's 7-second window. SEND_LOGBOOK makes a workbook first: that runs off the main
 * thread (the broadcast is kept open with goAsync until the reply is sent), so the screen keeps answering meanwhile.
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = intent.action ?: return
        if (function !in GleoContract.FUNCTIONS) return
        val params = intentParams(intent)
        val pending: PendingResult? = goAsync() // null when called directly (tests), not by a broadcast
        context.background.scope.launch {
            try {
                val reply = try {
                    context.core.handleGleo(function, params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(AutoBriefApp.LOG_TAG, "GLEO_FAILED function=$function error=${e::class.java.simpleName}", e)
                    AutoBriefCore.GleoReply("fail", "The request could not be completed.")
                }
                sendGleoResponse(context, function, reply)
                // a start Android refuses from the background is logged there, never thrown into Gleo's broadcast
                if (function == GleoContract.SEND_LOGBOOK) VehicleMonitorService.start(context, VehicleMonitorService.ACTION_FLUSH)
            } finally {
                pending?.finish()
            }
        }
    }
}

fun intentParams(intent: Intent) = GleoContract.parseParams(
    intent.getStringExtra(GleoContract.EXTRA_ACTION_PARAM),
    intent.extras?.keySet()?.associateWith { key -> intent.extras?.get(key)?.toString() } ?: emptyMap(),
)

/**
 * The reply goes to the Gleo app only, so other apps cannot read it. The guide's sample sends it as an
 * implicit broadcast; that form is used only on a build where Gleo is not [AutoBriefCore.GLEO_PACKAGE].
 * The payload carries no places, addresses or names either way (status, an English sentence, totals).
 */
fun sendGleoResponse(context: Context, function: String, reply: AutoBriefCore.GleoReply) {
    val response = Intent(GleoContract.RESPONSE_ACTION)
        .putExtra(GleoContract.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(GleoContract.EXTRA_RESPONSE, GleoContract.response(function, reply))
    val gleoInstalled = runCatching { context.packageManager.getPackageInfo(AutoBriefCore.GLEO_PACKAGE, 0) }.isSuccess
    if (gleoInstalled) response.setPackage(AutoBriefCore.GLEO_PACKAGE)
    context.sendBroadcast(response)
}
