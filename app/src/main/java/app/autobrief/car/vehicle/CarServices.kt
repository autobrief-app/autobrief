package app.autobrief.car.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Standard AAOS car service, used where Pleos follows AAOS: the driving UX restrictions an app that declares
 * distraction optimisation must honour (no lists or text entry while the car moves). Until the car service answers,
 * and whenever it goes down, the state is reported as unknown (null): the core then fails closed (restricted unless a
 * live vehicle link says the car is in P). On Android 11+ the Car object waits for the service and reconnects by
 * itself (lifecycle listener); on older versions the connection is tried again with a growing pause.
 */
class CarServices(private val context: Context, private val onRestricted: (Boolean?) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var stopped = false

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        stopped = false
        onRestricted(null)
        if (Build.VERSION.SDK_INT >= 30) {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { c, ready ->
                if (stopped) return@createCar
                if (ready) attach(c) else detach("car service went down")
            }
        } else {
            connect(0)
        }
    }

    /** Android 9 and 10: the plain createCar, tried again while it returns nothing. */
    private fun connect(attempt: Int) {
        if (stopped) return
        val c = runCatching { Car.createCar(context) }.getOrNull()
        if (c == null) {
            detach("car service not ready")
            main.postDelayed({ connect(attempt + 1) }, Backoff.delayMs(attempt))
            return
        }
        car = c
        if (!attach(c)) {
            runCatching { c.disconnect() }
            car = null
            main.postDelayed({ connect(attempt + 1) }, Backoff.delayMs(attempt))
        }
    }

    private fun attach(c: Car): Boolean = try {
        val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
        runCatching { uxManager?.unregisterListener() }
        uxManager = ux
        onRestricted(ux.currentCarUxRestrictions?.isRequiresDistractionOptimization)
        ux.registerListener { r -> onRestricted(r.isRequiresDistractionOptimization) }
        true
    } catch (e: Exception) {
        detach("ux restrictions: ${e.message}")
        false
    }

    private fun detach(why: String) {
        Log.w(TAG, why)
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        onRestricted(null)
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
