package app.autobrief.car.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Standard AAOS car service, used where Pleos follows AAOS: the driving UX restrictions an app that declares
 * distraction optimisation must honour (no lists or text entry while the car moves). While the car service cannot
 * be reached the state is reported as unknown (null; the core then counts the car as restricted outside P) and the
 * connection is tried again with a growing pause.
 */
class CarServices(private val context: Context, private val onRestricted: (Boolean?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var stopped = false

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        stopped = false
        connect(0)
    }

    private fun connect(attempt: Int) {
        if (stopped) return
        val c = runCatching { Car.createCar(context) }.getOrNull()
        if (c == null) {
            retry(attempt, "car service not ready")
            return
        }
        car = c
        try {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            onRestricted(ux.currentCarUxRestrictions?.isRequiresDistractionOptimization)
            ux.registerListener { r -> onRestricted(r.isRequiresDistractionOptimization) }
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            car = null
            uxManager = null
            retry(attempt, "ux restrictions: ${e.message}")
        }
    }

    private fun retry(attempt: Int, why: String) {
        Log.w(TAG, why)
        onRestricted(null)
        main.postDelayed({ connect(attempt + 1) }, Backoff.delayMs(attempt))
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        uxManager = null
        car = null
    }

    companion object {
        private const val TAG = "AutoBriefCar"
    }
}
