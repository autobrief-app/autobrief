package app.autobrief.car.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * The monitor's two loops. Ticks (the time rules, position polling, pending place rules, the month close) run every
 * [tickMs] in [scope]; sending runs on [io], woken by [requestFlush] or every [flushEveryMs], one round after the
 * other while a round stops at its cap with more due. A dead endpoint (5 s connect and 15 s read timeouts per item)
 * therefore never holds up a tick, and a failing tick or round is reported to [onError] and the loop goes on.
 */
class MonitorLoops(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val tickMs: Long,
    private val flushEveryMs: Long,
    private val onError: (String, Throwable) -> Unit,
    private val onTick: (Int) -> Unit,
    /** One sending round; true when it stopped at its cap with items still due. */
    private val flush: () -> Boolean,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            var n = 0
            while (isActive) {
                delay(tickMs)
                n++
                guarded("tick", onError) { onTick(n) }
            }
        }
        scope.launch(io) {
            while (isActive) {
                withTimeoutOrNull(flushEveryMs) { wake.receive() }
                while (isActive && guarded("flush", onError) { flush() } == true) yield()
            }
        }
    }

    /** Asks for a sending round soon; requests made while one runs are served by one more round. */
    fun requestFlush() {
        wake.trySend(Unit)
    }
}

/** Runs [block]; an exception is reported to [onError] and dropped, so one bad event or round cannot end the monitor. */
inline fun <T> guarded(what: String, onError: (String, Throwable) -> Unit, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(what, e)
        null
    }
