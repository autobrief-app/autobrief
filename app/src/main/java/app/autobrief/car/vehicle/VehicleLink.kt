package app.autobrief.car.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import app.autobrief.car.model.Gear

/**
 * The calls [PleosVehicleSource] makes into the Pleos Vehicle SDK, one link per connection. [PleosVehicleLink] is the
 * real one; a test passes its own to see which thread each call runs on.
 */
interface VehicleLink {
    /** Binds to the vehicle service; the binding completes later (the first answer to [currentDistance] shows it). */
    fun initialize()

    /** The dashboard odometer once (km; null = no value yet). */
    fun currentDistance(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit)

    fun currentGear(onValue: (Gear) -> Unit, onFailed: (Exception) -> Unit)

    /** The VIN as the SDK sends it (JSON-quoted; the emulator sends "invalid"). */
    fun vin(onValue: (String?) -> Unit, onFailed: (Exception) -> Unit)

    /** Fuel: the cumulative fuel economy (L/100 km; null = no value). CAR_ENERGY. */
    fun fuelEconomy(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit)

    /** EvBattery: the cumulative EV efficiency (km/kWh; null = no value). CAR_ENERGY. */
    fun evEfficiency(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit)

    /** Odometer and gear updates from now on, until [release]; [onFailed] names the listener that failed. */
    fun listen(onDistance: (Float?) -> Unit, onGear: (Gear) -> Unit, onFailed: (String, Exception) -> Unit)

    fun release()
}

/**
 * Pleos Connect Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle`): Odometer, Gear (CAR_DRIVE), CarInfo VIN (CAR_INFO), and
 * Fuel and EvBattery's cumulative figures (CAR_ENERGY).
 */
class PleosVehicleLink(context: Context) : VehicleLink {
    private val vehicle = Vehicle(context.applicationContext)
    private var odometerListener: DistanceDrivenListener? = null
    private var gearListener: GearSelectionListener? = null

    override fun initialize() = vehicle.initialize()

    override fun currentDistance(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) =
        vehicle.getOdometer().getCurrentDistanceDriven(onValue, onFailed)

    override fun currentGear(onValue: (Gear) -> Unit, onFailed: (Exception) -> Unit) =
        vehicle.getGear().getCurrentGearSelectionState({ onValue(it.toGear()) }, onFailed)

    override fun vin(onValue: (String?) -> Unit, onFailed: (Exception) -> Unit) =
        vehicle.getCarInfo().getVINInfo(onValue, onFailed)

    override fun fuelEconomy(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) =
        vehicle.getFuel().getCumulativeFuelEconomy(onValue, onFailed)

    override fun evEfficiency(onValue: (Float?) -> Unit, onFailed: (Exception) -> Unit) =
        vehicle.getEvBattery().getCumulativeEvEfficiency(onValue, onFailed)

    override fun listen(onDistance: (Float?) -> Unit, onGear: (Gear) -> Unit, onFailed: (String, Exception) -> Unit) {
        val odometer = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) = onDistance(km)
            override fun onFailed(e: Exception) = onFailed("odometer", e)
        }
        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = onGear(gear.toGear())
            override fun onFailed(e: Exception) = onFailed("gear", e)
        }
        odometerListener = odometer
        gearListener = gear
        vehicle.getOdometer().registerDistanceDriven(odometer)
        vehicle.getGear().registerGearSelection(gear)
    }

    override fun release() {
        odometerListener?.let { l -> runCatching { vehicle.getOdometer().unregisterDistanceDriven(l) } }
        gearListener?.let { l -> runCatching { vehicle.getGear().unregisterGearSelection(l) } }
        odometerListener = null
        gearListener = null
        runCatching { vehicle.release() }
    }

    private fun VehicleGear?.toGear(): Gear = when (this) {
        null -> Gear.UNKNOWN
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE and manual-range gears all mean the car is in motion gear
    }
}
