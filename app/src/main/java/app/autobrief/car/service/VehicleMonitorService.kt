package app.autobrief.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.autobrief.car.R
import app.autobrief.car.autoBrief
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.navi.PleosNaviSource
import app.autobrief.car.notify.WebhookSender
import app.autobrief.car.vehicle.CarServices
import app.autobrief.car.vehicle.PlatformLocationSource
import app.autobrief.car.vehicle.PleosVehicleSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Keeps the logbook running for every ignition cycle, not only while the screen is open: listens to the
 * Pleos Vehicle and NaviHelper SDKs, ticks the time-based rules, asks navigation for the position while a
 * trip is open, and sends queued ledger entries and workbooks on its own I/O loop. Started at boot and when
 * the app opens. One bad event, tick or send round is logged and dropped; it never ends the process.
 *
 * The links start at once, while the process may still be reading the stored logbook (AutoBriefApp): what they report
 * until the core is there waits in [inputs] and goes in first, in arrival order ([attach]).
 */
class VehicleMonitorService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> logError("coroutine", e) },
    )
    /** Set once the stored logbook is read ([attach]). */
    private lateinit var core: AutoBriefCore
    private val inputs = CoreInputs<AutoBriefCore> { n -> Log.w(TAG, "$n early reports dropped: the logbook took too long to read") }
    private lateinit var vehicle: PleosVehicleSource
    private lateinit var navi: PleosNaviSource
    private lateinit var car: CarServices
    private lateinit var gps: PlatformLocationSource
    private lateinit var sender: WebhookSender
    private lateinit var loops: MonitorLoops

    /** Running as a foreground service of the location type, which keeps GNSS fixes coming while navigation is in front. */
    private var locationForeground = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        vehicle = PleosVehicleSource(this, emit = { e -> dispatch("vehicle event") { it.onVehicle(e) } })
        navi = PleosNaviSource(this) { e -> dispatch("navigation event") { it.onNavi(e) } }
        car = CarServices(this) { r -> dispatch("driving restrictions") { it.setRestricted(r) } }
        gps = PlatformLocationSource(this) { fix -> dispatch("gnss fix") { it.onPlatformFix(fix) } }
        // no position is asked for until the logbook is read: 가족 운행 may be on (see attach)
        navi.setLocationPaused(true)
        gps.setPaused(true)
        // ticks and sending rounds run once the core is there (started in attach); a flush asked for before waits
        loops = MonitorLoops(scope, Dispatchers.IO, TICK_MS, FLUSH_EVERY_MS, ::logError, ::onTick) { sender.flush() }
        vehicle.start()
        navi.start()
        car.start()
        scope.launch { attach(autoBrief.awaitCore()) }
    }

    /** The stored logbook is read: the reports that waited go in first, then the ticks and the sending start. */
    @OptIn(FlowPreview::class)
    private fun attach(c: AutoBriefCore) {
        core = c
        sender = WebhookSender(c)
        c.naviControl = AutoBriefCore.NaviControl { navi.requestLocation() }
        // the car's fuel economy again after every trip (off the main thread, on the vehicle source's own)
        c.energyControl = AutoBriefCore.EnergyControl { vehicle.requestEnergy() }
        inputs.open(c)
        loops.start()
        scope.launch {
            // 가족 운행: navigation is asked for no position and the GNSS updates stop until the family trip closes
            c.locationPaused.collect { paused ->
                navi.setLocationPaused(paused)
                gps.setPaused(paused)
            }
        }
        scope.launch {
            // new ledger entries reach the company endpoint within seconds of being written, not at the next periodic round
            core.sendable
                .debounce(FLUSH_DEBOUNCE_MS)
                .collect { pending -> if (pending > 0) loops.requestFlush() }
        }
    }

    private fun onTick(n: Int) {
        core.tick()
        // SDK links that went quiet while the car is out of P are made again
        vehicle.checkQuiet()
        navi.checkQuiet(core.vehicle.value.gear)
        // the position is needed at departure and arrival; while driving keep it reasonably fresh
        if (core.vehicle.value.gear == Gear.DRIVE || core.vehicle.value.gear == Gear.REVERSE) {
            if (n % LOCATION_EVERY_TICKS_DRIVING == 0) navi.requestLocation()
        } else if (n % LOCATION_EVERY_TICKS_PARKED == 0) {
            navi.requestLocation()
        }
        if (n % BOOKMARKS_EVERY_TICKS == 0) navi.requestBookmarks()
    }

    /**
     * SDK callbacks are posted to the main thread one by one and reach the core in that order (after the ones that waited
     * for it); each runs on its own, so one that throws loses only itself.
     */
    private fun dispatch(what: String, block: (AutoBriefCore) -> Unit) {
        scope.launch { inputs.deliver { c -> guarded(what, ::logError) { block(c) } } }
    }

    private fun logError(what: String, e: Throwable) {
        Log.e(TAG, "$what failed: ${e::class.java.simpleName}: ${e.message}", e)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // the location permission may have been granted since the service started, or the service was started in the
        // background (boot, app update) and could not take the location type then: MainActivity starts it again
        if (PlatformLocationSource.permitted(this)) {
            if (!locationForeground) startAsForeground()
            gps.start()
        }
        if (intent?.action == ACTION_FLUSH) loops.requestFlush()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        vehicle.stop()
        navi.stop()
        car.stop()
        gps.stop()
        if (::core.isInitialized) {
            // the vehicle link is gone with the service: its last gear no longer tells the screen the car is parked
            core.onVehicle(VehicleEvent.Connection(false, "monitor stopped"))
            core.setRestricted(null)
            core.naviControl = null
            core.energyControl = null
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_logbook)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitor_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            // the location type keeps the GNSS fixes coming while navigation is in front
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (PlatformLocationSource.permitted(this)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            locationForeground = try {
                startForeground(NOTIFICATION_ID, notification, types)
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
            } catch (e: SecurityException) {
                // Android 14 refuses the location type to a service started in the background (boot, app update):
                // it gets no GNSS fixes while the app is not in front, until the app is opened and starts it again
                Log.w(TAG, "foreground without location: ${e.message}")
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                false
            }
        } else {
            startForeground(NOTIFICATION_ID, notification) // the manifest's types
            locationForeground = true
        }
    }

    companion object {
        const val ACTION_FLUSH = "app.autobrief.car.FLUSH"
        private const val TAG = "AutoBriefMonitor"
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 2_000L
        private const val LOCATION_EVERY_TICKS_DRIVING = 3
        private const val LOCATION_EVERY_TICKS_PARKED = 30
        private const val BOOKMARKS_EVERY_TICKS = 150
        private const val FLUSH_EVERY_MS = 30_000L
        private const val FLUSH_DEBOUNCE_MS = 800L

        /**
         * Starts the monitor (or hands a running one [action]); false when Android refused. From the background Android
         * 12+ may refuse with ForegroundServiceStartNotAllowedException (an IllegalStateException): that is logged, and
         * a monitor that already runs sends new entries by itself anyway.
         */
        fun start(context: Context, action: String? = null): Boolean = try {
            context.startForegroundService(Intent(context, VehicleMonitorService::class.java).setAction(action))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "monitor not started (${action ?: "start"}): ${e::class.java.simpleName}: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "monitor not started (${action ?: "start"}): ${e.message}")
            false
        }
    }
}
