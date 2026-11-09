package app.autobrief.car.navi

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.domain.AutoClassifier
import app.autobrief.car.domain.NaviBookmark
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.vehicle.Backoff
import app.autobrief.car.vehicle.QuietWatch

/**
 * Pleos NaviHelper SDK 3.0.5 (permission pleos.car.permission.NAVI_ROUTE), read-only use: the route's
 * toll estimate, the HOME/OFFICE bookmarks and the current position. Results arrive through the listener,
 * never as return values; positions are passed on, not stored.
 *
 * The navigation service may not be up when the app starts: initialising is tried again with a growing pause
 * until it succeeds. Once connected, a link that answered nothing for [QUIET_MS] while the car is out of P
 * ([checkQuiet], called by the monitor's tick; positions are asked for every few seconds then) is made again.
 *
 * Every call into NaviHelper runs on a dedicated HandlerThread and waits until the vehicle SDK has connected
 * (or [INITIAL_DELAY_MS] has elapsed) so NaviHelper's binder callbacks do not contend with the vehicle SDK's
 * initial kotlin-reflect metadata load.
 */
class PleosNaviSource(context: Context, private val emit: (NaviEvent) -> Unit) {

    private val app = context.applicationContext
    private val thread = HandlerThread("AutoBriefNaviSdk", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val sdk = Handler(thread.looper)
    private var navi: NaviHelper? = null
    @Volatile private var started = false
    @Volatile private var ready = false
    @Volatile private var stopped = false
    private val quiet = QuietWatch(QUIET_MS, SystemClock::elapsedRealtime)

    /** While true no position is asked for (가족 운행, or the logbook is not read yet); set by the monitor. */
    @Volatile var locationPaused = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationChanged() {
            quiet.heard()
            refreshDestination()
        }
        override fun onRouteStarted(info: RouteStartInfo) {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.RouteStarted(info.name, info.tollFare))
            refreshDestination()
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            quiet.heard()
            if (locationPaused) return
            val point = GeoPoint(info.latitude, info.longitude).takeIf {
                it.lat.isFinite() && it.lon.isFinite() && (it.lat != 0.0 || it.lon != 0.0)
            }
            emit(NaviEvent.Destination(info.name, info.remainDistance, info.remainTime, info.tollFare, point))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.Arrived(info.name))
        }
        override fun onRouteEnded() {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.RouteEnded)
        }
        override fun onRouteCancelled() {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.RouteEnded)
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.Location(GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)))
        }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            quiet.heard()
            if (locationPaused) return
            emit(NaviEvent.Bookmarks(bookmarkInfoList.mapNotNull { it.toBookmark() }))
        }
        override fun onBookMarkChanged() {
            quiet.heard()
            if (locationPaused) return
            requestBookmarks()
        }
    }

    fun start(initialDelayMs: Long = INITIAL_DELAY_MS) {
        stopped = false
        sdk.postDelayed({ beginIfNeeded() }, initialDelayMs)
    }

    /** Starts initialisation immediately once the vehicle SDK has connected and warmed kotlin-reflect. */
    fun startNow() {
        if (stopped || started) return
        sdk.post(::beginIfNeeded)
    }

    private fun beginIfNeeded() {
        if (stopped || started) return
        started = true
        if (navi == null) navi = NaviHelper(app)
        initialize(attempt = 0)
    }

    private fun initialize(attempt: Int) {
        if (stopped) return
        val helper = navi ?: NaviHelper(app).also { navi = it }
        try {
            helper.initialize({
                sdk.post {
                    if (stopped || helper !== navi) return@post
                    ready = true
                    quiet.heard()
                    helper.addListener(listener)
                    emit(NaviEvent.Ready(true))
                    requestBookmarks()
                    requestLocation()
                    refreshDestination()
                }
            }, {
                Log.w(TAG, "navi init: ${it.message}")
                emit(NaviEvent.Ready(false))
                retry(attempt)
            })
        } catch (e: Exception) {
            Log.w(TAG, "navi init: ${e.message}")
            retry(attempt)
        }
    }

    private fun retry(attempt: Int) {
        if (!stopped) sdk.postDelayed({ initialize(attempt + 1) }, Backoff.delayMs(attempt))
    }

    /** Resuming asks for the position and bookmarks at once (the requests skipped meanwhile are not made up later). */
    fun setLocationPaused(on: Boolean) {
        locationPaused = on
        if (!on) {
            requestBookmarks()
            requestLocation()
        }
    }

    /** The answer comes back through onCurrentLocationInfo; nothing is asked while [locationPaused]. */
    fun requestLocation() {
        if (!ready || locationPaused) return
        sdk.post {
            if (!stopped && ready && !locationPaused) {
                runCatching { navi?.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "location: ${it.message}") }
            }
        }
    }

    fun requestBookmarks() {
        if (!ready || locationPaused) return
        sdk.post {
            if (!stopped && ready && !locationPaused) {
                runCatching { navi?.getBookmarkInfo() }.onFailure { Log.w(TAG, "bookmarks: ${it.message}") }
            }
        }
    }

    private fun refreshDestination() {
        if (!ready || locationPaused) return
        sdk.post {
            if (!stopped && ready && !locationPaused) {
                runCatching { navi?.getDestinationInfo() }.onFailure { Log.w(TAG, "destination: ${it.message}") }
            }
        }
    }

    /** Called by the monitor every tick with the car's gear; see the class comment. */
    fun checkQuiet(gear: Gear) {
        if (stopped || !ready) return
        sdk.post {
            if (stopped || !ready || !quiet.reconnectDue(gear)) return@post
            Log.w(TAG, "navigation answered nothing for ${QUIET_MS / 60_000} min outside P: connecting again")
            release()
            navi = NaviHelper(app)
            initialize(attempt = 0)
        }
    }

    private fun release() {
        ready = false
        val helper = navi ?: return
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
        navi = null
    }

    fun stop() {
        stopped = true
        sdk.removeCallbacksAndMessages(null)
        sdk.post(::release)
        thread.quitSafely()
    }

    private fun BookmarkInfo.toBookmark(): NaviBookmark? {
        val label = name.trim()
        val kind = when (category) {
            BookmarkType.HOME -> PlaceKind.HOME
            BookmarkType.OFFICE -> PlaceKind.OFFICE
            BookmarkType.DEFAULT -> if (label.isNotBlank() && !AutoClassifier.isPrivatePlaceName(label)) PlaceKind.CLIENT else return null
            else -> return null
        }
        return NaviBookmark(kind, label, GeoPoint(latitude, longitude))
    }

    companion object {
        private const val TAG = "AutoBriefNavi"

        /** Fallback delay when the vehicle SDK has not connected yet. */
        const val INITIAL_DELAY_MS = 5_000L

        /** Positions are asked for every 6 s outside P: five minutes without any answer is a lost link. */
        const val QUIET_MS = 5 * 60_000L
    }
}
