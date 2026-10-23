package app.autobrief.car.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.autobrief.car.domain.VehicleEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Standard AAOS car service, used where Pleos follows AAOS: vehicle speed (PERF_VEHICLE_SPEED, m/s,
 * needs the runtime permission android.car.permission.CAR_SPEED) and the driving UX restrictions an
 * app declaring distraction optimisation must honour.
 */
class CarServices(private val context: Context, private val emit: (VehicleEvent) -> Unit) {

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private val _restricted = MutableStateFlow(false)

    /** True while driving: the screen must show its simplified, glanceable layout. */
    val restricted: StateFlow<Boolean> = _restricted

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { emit(VehicleEvent.Speed(it * 3.6)) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "speed property error")
        }
    }

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: return
        car = c
        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            _restricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            ux.registerListener { r -> _restricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { Log.w(TAG, "ux restrictions: ${it.message}") }
        startSpeed()
    }

    /** Call again after the user grants CAR_SPEED. */
    fun startSpeed() {
        val c = car ?: return
        if (propertyManager != null) return
        if (context.checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            propertyManager = pm
        }.onFailure { Log.w(TAG, "speed: ${it.message}") }
    }

    fun stop() {
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        propertyManager = null
        uxManager = null
        car = null
    }

    companion object {
        private const val TAG = "AutoBriefCar"
    }
}
