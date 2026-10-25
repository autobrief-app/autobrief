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
import android.util.Log
import app.autobrief.car.domain.NaviBookmark
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.PlaceKind

/**
 * Pleos NaviHelper SDK 3.0.5 (permission pleos.car.permission.NAVI_ROUTE), read-only use: the route's
 * destination name and toll estimate, the HOME/OFFICE bookmarks and the current position. Results
 * arrive through the listener, never as return values; positions are passed on, not stored.
 */
class PleosNaviSource(context: Context, private val emit: (NaviEvent) -> Unit) {

    private val navi = NaviHelper(context.applicationContext)
    @Volatile private var ready = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            emit(NaviEvent.RouteStarted(info.name, info.tollFare))
            refreshDestination()
        }
        override fun onDestinationInfo(info: DestinationInfo) =
            emit(NaviEvent.Destination(info.name, info.remainDistance, info.remainTime, info.tollFare))
        override fun onDestinationArrived(info: DestinationArrivedInfo) = emit(NaviEvent.Arrived(info.name))
        override fun onRouteEnded() = emit(NaviEvent.RouteEnded)
        override fun onRouteCancelled() = emit(NaviEvent.RouteEnded)
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            emit(NaviEvent.Location(GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)))
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = emit(NaviEvent.Bookmarks(bookmarkInfoList.mapNotNull { it.toBookmark() }))
        override fun onBookMarkChanged() = requestBookmarks()
    }

    fun start() {
        runCatching {
            navi.initialize({
                ready = true
                navi.addListener(listener)
                emit(NaviEvent.Ready(true))
                requestBookmarks()
                requestLocation()
                refreshDestination()
            }, {
                Log.w(TAG, "navi init: ${it.message}")
                emit(NaviEvent.Ready(false))
            })
        }.onFailure { Log.w(TAG, "navi init: ${it.message}") }
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

    fun stop() {
        runCatching {
            navi.removeListener(listener)
            navi.release()
        }
        ready = false
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
    }
}
