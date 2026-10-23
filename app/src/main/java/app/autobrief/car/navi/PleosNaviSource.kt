package app.autobrief.car.navi

import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.autobrief.car.domain.NaviEvent

/**
 * Pleos NaviHelper SDK 3.0.5 (permission pleos.car.permission.NAVI_ROUTE). Read-only use: destination
 * name, remaining distance/time, the route's toll estimate, arrival and the road speed limit.
 * Results arrive through the listener, never as return values.
 */
class PleosNaviSource(context: Context, private val emit: (NaviEvent) -> Unit) {

    private val navi = NaviHelper(context.applicationContext)

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            emit(NaviEvent.RouteStarted(info.name, info.tollFare))
            refresh()
        }
        override fun onDestinationInfo(info: DestinationInfo) =
            emit(NaviEvent.Destination(info.name, info.address, info.remainDistance, info.remainTime, info.tollFare))
        override fun onDestinationArrived(info: DestinationArrivedInfo) = emit(NaviEvent.Arrived(info.name))
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = emit(NaviEvent.SpeedLimit(speedLimit))
        override fun onRouteEnded() = emit(NaviEvent.RouteEnded)
        override fun onRouteCancelled() = emit(NaviEvent.RouteEnded)
    }

    fun start() {
        runCatching {
            navi.initialize({
                navi.addListener(listener)
                refresh()
            }, { Log.w(TAG, "navi init: ${it.message}") })
        }.onFailure { Log.w(TAG, "navi init: ${it.message}") }
    }

    /** Asks for the current destination info; the answer comes back through onDestinationInfo. */
    fun refresh() {
        runCatching { navi.getDestinationInfo() }.onFailure { Log.w(TAG, "destination: ${it.message}") }
    }

    fun stop() {
        runCatching {
            navi.removeListener(listener)
            navi.release()
        }
    }

    companion object {
        private const val TAG = "AutoBriefNavi"
    }
}
