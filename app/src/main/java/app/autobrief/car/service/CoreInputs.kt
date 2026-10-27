package app.autobrief.car.service

/**
 * What the car's links report while the stored logbook is still being read at the app's start (AutoBriefCore.open):
 * kept in arrival order and handed to the core once it is there, before anything that arrives later. No vehicle event is
 * applied to a logbook that is not loaded, and none is lost or reordered meanwhile. Main thread only: the monitor posts
 * every report there.
 */
class CoreInputs<T : Any>(private val cap: Int = CAP, private val onDropped: (Int) -> Unit = {}) {
    private var target: T? = null
    private val waiting = ArrayDeque<(T) -> Unit>()
    private var dropped = 0

    /** Reports waiting for the core. */
    val waitingCount: Int get() = waiting.size

    /** Runs [input] on the core now, or once it is there (see [open]). */
    fun deliver(input: (T) -> Unit) {
        val t = target
        if (t != null) {
            input(t)
            return
        }
        if (waiting.size == cap) {
            waiting.removeFirst()
            dropped++
        }
        waiting.addLast(input)
    }

    /** The core is there: what waited runs first, in arrival order (a report made meanwhile joins the end), then at once. */
    fun open(core: T) {
        check(target == null) { "the core was handed over already" }
        if (dropped > 0) onDropped(dropped)
        while (waiting.isNotEmpty()) waiting.removeFirst()(core)
        target = core
    }

    companion object {
        /** About an hour of a moving car's reports (a few a second); reading the logbook takes seconds. */
        const val CAP = 10_000
    }
}
