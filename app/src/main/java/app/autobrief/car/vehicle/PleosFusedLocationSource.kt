package app.autobrief.car.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.domain.PlatformFix
import app.autobrief.car.model.GeoPoint

/**
 * The car's own position through the Pleos FusedLocation SDK (Pleos Connect's FusedLocation Service, permission
 * `pleos.car.permission.FUSED_LOCATION`). The core uses it when NaviHelper has no fresh position (navigation stops
 * reading GPS when it is not in front, and on the emulator never follows injected GPS).
 *
 * Runs only while the monitor service runs, only with the permission and only where the service is installed
 * ([FusedClient.isSupported]); otherwise nothing is asked for and the app works from navigation alone. At most one fix
 * every [INTERVAL_MS] is passed on, in memory, and never stored. The SDK forbids reusing an instance after release(),
 * so every start connects a new one.
 */
class PleosFusedLocationSource(
    context: Context,
    private val connect: (Context) -> FusedClient = ::SdkFusedClient,
    private val emit: (PlatformFix) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var client: FusedClient? = null
    private var lastDeliveredAt = Long.MIN_VALUE

    var running = false
        private set

    /** While true no update is asked for and [start] does nothing (가족 운행, or the logbook is not read yet). */
    @Volatile var paused = false
        private set

    // the SDK may call back on its own thread: fixes are handed over on the main thread, and dropped once stopped
    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            main.post { if (running) deliver(location) }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "fused location: ${exception::class.java.simpleName}: ${exception.message}")
        }
    }

    /** Starts the updates when the permission and the service are there; true while they run. */
    fun start(): Boolean {
        if (running || paused || !permitted(app)) return running
        val c = connect(app)
        if (!c.isSupported()) {
            Log.w(TAG, "the Pleos FusedLocation service is not installed: navigation's position only")
            return false
        }
        try {
            c.initialize()
            c.register(listener)
            // the service's last fix, when it has one, so a parked car has a position before the first update
            c.lastKnown(listener)
        } catch (e: SecurityException) {
            // the grant was revoked between the check and the call
            Log.w(TAG, "fused location refused: ${e.message}")
            c.release()
            return false
        }
        client = c
        running = true
        return true
    }

    fun stop() {
        client?.let { c ->
            c.unregister(listener)
            c.release()
        }
        client = null
        running = false
    }

    /** Pausing stops the updates at once; resuming starts them again when the permission is there. */
    fun setPaused(on: Boolean) {
        paused = on
        if (on) stop() else start()
    }

    private fun deliver(location: LocationData) {
        val now = SystemClock.elapsedRealtime()
        if (lastDeliveredAt != Long.MIN_VALUE && now - lastDeliveredAt < INTERVAL_MS) return
        lastDeliveredAt = now
        // LocationData.confidence has no documented unit, so it is not passed on as a horizontal accuracy
        emit(PlatformFix(GeoPoint(location.latitude, location.longitude), location.timestampMillis, null))
    }

    /** The calls this source makes on the SDK, so tests can stand in for the service Robolectric does not have. */
    interface FusedClient {
        fun isSupported(): Boolean
        fun initialize()
        fun register(listener: FusedLocationListener)
        fun unregister(listener: FusedLocationListener)
        fun lastKnown(listener: FusedLocationListener)
        fun release()
    }

    private class SdkFusedClient(context: Context) : FusedClient {
        private val sdk = FusedLocation(context)
        override fun isSupported() = sdk.isSupported()
        override fun initialize() = sdk.initialize()
        override fun register(listener: FusedLocationListener) = sdk.registerFusedLocationCallback(listener)
        override fun unregister(listener: FusedLocationListener) = sdk.unregisterFusedLocationCallback(listener)
        override fun lastKnown(listener: FusedLocationListener) = sdk.getLastKnownLocation(listener)
        override fun release() = sdk.release()
    }

    companion object {
        private const val TAG = "AutoBriefGnss"
        const val INTERVAL_MS = 5_000L
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"

        fun permitted(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
