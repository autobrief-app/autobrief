package app.autobrief.car.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.domain.PlatformFix
import app.autobrief.car.model.GeoPoint

/**
 * The car's own GNSS through Android's LocationManager: GPS_PROVIDER, plus FUSED_PROVIDER on API 31+ when the platform
 * has one, a fix every [INTERVAL_MS]. No minimum distance: Android combines the time and the distance filter, so a
 * distance minimum would leave a parked car without fresh fixes. The core uses it when NaviHelper has no fresh
 * position (navigation stops reading GPS when it is not in front, and on the emulator never follows injected GPS).
 *
 * Runs only while the monitor service runs and only with the location permission; without it nothing is requested
 * and the app works from navigation alone. Fixes are passed on in memory and never stored.
 */
class PlatformLocationSource(context: Context, private val emit: (PlatformFix) -> Unit) {
    private val app = context.applicationContext
    private val manager: LocationManager? = app.getSystemService(LocationManager::class.java)

    var running = false
        private set

    // every callback spelled out: on API 28-29 LocationListener has no default methods
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /** Starts the updates when the permission is there; true while they run. */
    @SuppressLint("MissingPermission") // checked just before; a revoked grant is a SecurityException, caught below
    fun start(): Boolean {
        val lm = manager ?: return false
        if (running || !permitted(app)) return running
        val providers = buildList {
            if (LocationManager.GPS_PROVIDER in lm.allProviders) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        }
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                running = true
            } catch (e: SecurityException) {
                Log.w(TAG, "$provider: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$provider: ${e.message}")
            }
        }
        // the platform's last fix, when it has one, so a parked car has a position before the first update
        if (running) {
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let(::deliver)
        }
        return running
    }

    fun stop() {
        if (running) runCatching { manager?.removeUpdates(listener) }
        running = false
    }

    private fun deliver(location: Location) {
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        emit(
            PlatformFix(
                GeoPoint(location.latitude, location.longitude),
                System.currentTimeMillis() - ageMs,
                if (location.hasAccuracy()) location.accuracy else null,
            ),
        )
    }

    companion object {
        private const val TAG = "AutoBriefGnss"
        const val INTERVAL_MS = 5_000L

        fun permitted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
