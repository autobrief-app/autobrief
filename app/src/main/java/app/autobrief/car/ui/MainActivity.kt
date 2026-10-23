package app.autobrief.car.ui

import android.Manifest
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.GleoContract
import app.autobrief.car.gleo.intentParams
import app.autobrief.car.gleo.sendGleoResponse
import app.autobrief.car.service.VehicleMonitorService
import kotlinx.serialization.json.JsonPrimitive

class MainActivity : ComponentActivity() {

    /** Set when Gleo (or the tab) asks for designated-driver mode: the consent dialog opens. */
    private val consentRequest = mutableStateOf<ConsentRequest?>(null)
    private val startTab = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleMonitorService.start(this)
        requestRuntimePermissions()
        handleGleoIntent(intent)
        setContent {
            AutoBriefScreen(
                core = core,
                restrictedFlow = VehicleMonitorService.restrictedFlow,
                startTab = startTab,
                consentRequest = consentRequest,
                onExported = { VehicleMonitorService.start(this, VehicleMonitorService.ACTION_FLUSH) },
                exportDir = getExternalFilesDir("exports") ?: filesDir,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleoIntent(intent)
    }

    private fun handleGleoIntent(intent: Intent?) {
        if (intent?.action != GleoContract.START_DESIGNATED_DRIVER_MODE) return
        val params = intentParams(intent)
        val notify = (params["notify_family"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
        startTab.value = 2
        consentRequest.value = ConsentRequest(notifyFamily = notify)
        sendGleoResponse(
            this, GleoContract.START_DESIGNATED_DRIVER_MODE,
            AutoBriefCore.GleoReply("asking", "The designated driver must agree on the screen before the mode starts."),
            core.gleoMode(),
        )
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Car.PERMISSION_SPEED)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            VehicleMonitorService.start(this, VehicleMonitorService.ACTION_SPEED_PERMISSION_GRANTED)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 7
    }
}

data class ConsentRequest(val notifyFamily: Boolean)
