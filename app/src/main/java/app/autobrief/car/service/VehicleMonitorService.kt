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
import app.autobrief.car.AutoBriefApp
import app.autobrief.car.R
import app.autobrief.car.autoBrief
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.model.VehicleSnapshot
import app.autobrief.car.navi.PleosNaviSource
import app.autobrief.car.notify.WebhookSender
import app.autobrief.car.vehicle.CarServices
import app.autobrief.car.vehicle.PleosFusedLocationSource
import app.autobrief.car.vehicle.PleosVehicleSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the logbook running for every ignition cycle, not only while the screen is open: listens to the
 * Pleos Vehicle and NaviHelper SDKs, ticks the time-based rules, asks navigation for the position while a
 * trip is open, and sends queued ledger entries and workbooks on its own I/O loop. Started at boot and when
 * the app opens. One bad event, tick or send round is logged and dropped; it never ends the process.
 *
 * Navigation is the car's main screen: while a trip is recorded and the app's own screen is not shown, the monitor says so
 * in its notification and, where the user allowed drawing over other apps, in a small status pill ([DriveHud]).
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
    private lateinit var gps: PleosFusedLocationSource
    private lateinit var sender: WebhookSender
    private lateinit var loops: MonitorLoops

    /** The status pill over the front app; made with the core, its window only when the first pill is drawn. */
    private lateinit var hud: DriveHud

    /** The text the ongoing notification shows now. */
    private var notificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        navi = PleosNaviSource(this) { e -> dispatch("navigation event") { it.onNavi(e) } }
        vehicle = PleosVehicleSource(this, emit = { e ->
            if (e is VehicleEvent.Connection && e.connected) navi.startNow()
            dispatch("vehicle event") {
                // CarUxRestrictions clears ~300-450 ms after VHAL gear=PARK; clear before closing the trip so Compose
                // transitions straight to the parked HomeScreen instead of rendering an intermediate DrivingView frame.
                if (e is VehicleEvent.GearChanged && e.gear == Gear.PARK) it.setRestricted(false)
                it.onVehicle(e)
            }
        })
        car = CarServices(this) { r -> dispatch("driving restrictions") { it.setRestricted(r) } }
        gps = PleosFusedLocationSource(this) { fix -> dispatch("gnss fix") { it.onPlatformFix(fix) } }
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
        hud = DriveHud(this, log = { line -> Log.i(AutoBriefApp.LOG_TAG, line) })
        inputs.open(c)
        loops.start()
        scope.launch {
            // the pill and the notification follow the trip, its printed distance and whether the app's own screen shows
            combine(c.openTrip, c.vehicle, autoBrief.appVisible) { open, v, visible -> statusOf(open, v, visible) }
                .distinctUntilChangedBy { it.printed }
                .collect { showStatus(it) }
        }
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

    /** What the pill and the notification are drawn from; a trip is being recorded while the core holds an open one. */
    private data class Status(val recording: Boolean, val distanceKm: Double, val family: Boolean, val appVisible: Boolean) {
        /** Odometer readings that print the same distance are one status. */
        val printed: Any get() = copy(distanceKm = 0.0) to recordingState(recording, distanceKm, family)
    }

    /** The distance so far is the home card's: the last odometer reading less the trip's first. */
    private fun statusOf(open: AutoBriefCore.OpenTripView?, v: VehicleSnapshot, appVisible: Boolean) = Status(
        recording = open != null,
        distanceKm = if (open != null) (v.odometerKm ?: open.startOdometerKm) - open.startOdometerKm else 0.0,
        family = open?.family == true,
        appVisible = appVisible,
    )

    private fun showStatus(s: Status) {
        hud.update(s.recording, s.distanceKm, s.family, s.appVisible)
        val text = recordingState(s.recording, s.distanceKm, s.family)?.let { recordingLine(recordingLabel(it.family), it.distance) }
            ?: getString(R.string.monitor_text)
        if (text == notificationText) return
        notificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun onTick(n: Int) {
        core.tick()
        // drawing over other apps may have been allowed or taken back since the last look
        showStatus(statusOf(core.openTrip.value, core.vehicle.value, autoBrief.appVisible.value))
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
        // the FusedLocation permission may have been granted since the service started: MainActivity starts it again
        if (PleosFusedLocationSource.permitted(this)) gps.start()
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
        if (::hud.isInitialized) hud.destroy()
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
        val text = getString(R.string.monitor_text)
        notificationText = text
        val notification = notification(text)
        // special use only: the car's position comes from the Pleos FusedLocation Service over its SDK binding, not from
        // Android's location providers, so the service needs no location type (nor Android's location permissions)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification) // the manifest's type
        }
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_logbook)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .build()

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
