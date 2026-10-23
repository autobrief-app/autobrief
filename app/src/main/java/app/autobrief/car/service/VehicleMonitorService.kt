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
import app.autobrief.car.R
import app.autobrief.car.core
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.navi.PleosNaviSource
import app.autobrief.car.notify.WebhookSender
import app.autobrief.car.vehicle.CarServices
import app.autobrief.car.vehicle.PleosVehicleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the logbook running for every ignition cycle, not only while the screen is open: listens to the
 * Pleos Vehicle and NaviHelper SDKs and the AAOS speed property, ticks the time-based rules, and flushes
 * the outbox. Started at boot and when the app opens.
 */
class VehicleMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var core: AutoBriefCore
    private lateinit var vehicle: PleosVehicleSource
    private lateinit var navi: PleosNaviSource
    private lateinit var car: CarServices
    private lateinit var sender: WebhookSender

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        core = applicationContext.core
        vehicle = PleosVehicleSource(this) { e -> scope.launch { core.onVehicle(e) } }
        navi = PleosNaviSource(this) { e -> scope.launch { core.onNavi(e) } }
        car = CarServices(this) { e -> scope.launch { core.onVehicle(e) } }
        sender = WebhookSender(core)
        core.control = vehicle
        vehicle.start()
        navi.start()
        car.start()
        restrictedFlow = car.restricted
        scope.launch {
            var n = 0
            while (isActive) {
                delay(TICK_MS)
                core.tick()
                if (core.custody.value != null) navi.refresh() // keeps remaining distance current for arrival
                if (++n % FLUSH_EVERY_TICKS == 0) withContext(Dispatchers.IO) { sender.flush() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SPEED_PERMISSION_GRANTED) car.startSpeed()
        if (intent?.action == ACTION_FLUSH) scope.launch(Dispatchers.IO) { sender.flush() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        vehicle.stop()
        navi.stop()
        car.stop()
        core.control = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitor_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_SPEED_PERMISSION_GRANTED = "app.autobrief.car.SPEED_PERMISSION_GRANTED"
        const val ACTION_FLUSH = "app.autobrief.car.FLUSH"
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 5_000L
        private const val FLUSH_EVERY_TICKS = 6

        /** Driving restriction state for the screen; false when the service has not started. */
        var restrictedFlow: kotlinx.coroutines.flow.StateFlow<Boolean>? = null
            private set

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, VehicleMonitorService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
