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
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 */
class PleosNaviSource(context: Context, private val emit: (NaviEvent) -> Unit) {

    private val app = context.applicationContext
    private var navi = NaviHelper(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    @Volatile private var stopped = false
    private val quiet = QuietWatch(QUIET_MS, SystemClock::elapsedRealtime)

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            quiet.heard()
            emit(NaviEvent.RouteStarted(info.name, info.tollFare))
            refreshDestination()
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            quiet.heard()
            emit(NaviEvent.Destination(info.name, info.remainDistance, info.remainTime, info.tollFare))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            quiet.heard()
            emit(NaviEvent.Arrived(info.name))
        }
        override fun onRouteEnded() {
            quiet.heard()
            emit(NaviEvent.RouteEnded)
        }
        override fun onRouteCancelled() {
            quiet.heard()
            emit(NaviEvent.RouteEnded)
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            quiet.heard()
            emit(NaviEvent.Location(GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)))
        }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            quiet.heard()
            emit(NaviEvent.Bookmarks(bookmarkInfoList.mapNotNull { it.toBookmark() }))
        }
        override fun onBookMarkChanged() = requestBookmarks()
    }

    fun start() {
        stopped = false
        initialize(attempt = 0)
    }

    private fun initialize(attempt: Int) {
        if (stopped) return
        val helper = navi
        try {
            helper.initialize({
                if (stopped || helper !== navi) return@initialize
                ready = true
                quiet.heard()
                helper.addListener(listener)
                emit(NaviEvent.Ready(true))
                requestBookmarks()
                requestLocation()
                refreshDestination()
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
        if (!stopped) main.postDelayed({ initialize(attempt + 1) }, Backoff.delayMs(attempt))
    }

    /** The answer comes back through onCurrentLocationInfo. */
    fun requestLocation() {
        if (!ready) return
        runCatching { navi.getCurrentLocationInfo() }.onFailure { Log.w(TAG, "location: ${it.message}") }
    }

    fun requestBookmarks() {
        if (!ready) return
        runCatching { navi.getBookmarkInfo() }.onFailure { Log.w(TAG, "bookmarks: ${it.message}") }
    }

    private fun refreshDestination() {
        runCatching { navi.getDestinationInfo() }.onFailure { Log.w(TAG, "destination: ${it.message}") }
    }

    /** Called by the monitor every tick with the car's gear; see the class comment. */
    fun checkQuiet(gear: Gear) {
        if (stopped || !ready || !quiet.reconnectDue(gear)) return
        Log.w(TAG, "navigation answered nothing for ${QUIET_MS / 60_000} min outside P: connecting again")
        release()
        navi = NaviHelper(app)
        initialize(attempt = 0)
    }

    private fun release() {
        ready = false
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        release()
    }

    private fun BookmarkInfo.toBookmark(): NaviBookmark? {
        val kind = when (category) {
            BookmarkType.HOME -> PlaceKind.HOME
            BookmarkType.OFFICE -> PlaceKind.OFFICE
            else -> return null
        }
        return NaviBookmark(kind, name ?: "", GeoPoint(latitude, longitude))
    }

    companion object {
        private const val TAG = "AutoBriefNavi"

        /** Positions are asked for every 6 s outside P: five minutes without any answer is a lost link. */
        const val QUIET_MS = 5 * 60_000L
    }
}
