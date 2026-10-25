package app.autobrief.car.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Standard AAOS car service, used where Pleos follows AAOS: the driving UX restrictions an app that
 * declares distraction optimisation must honour (no lists or text entry while the car moves).
 */
class CarServices(private val context: Context, private val onRestricted: (Boolean) -> Unit) {

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: return
        car = c
        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            onRestricted(ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
            ux.registerListener { r -> onRestricted(r.isRequiresDistractionOptimization) }
        }.onFailure { Log.w(TAG, "ux restrictions: ${it.message}") }
    }

    fun stop() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        uxManager = null
        car = null
    }

    companion object {
        private const val TAG = "AutoBriefCar"
    }
}
