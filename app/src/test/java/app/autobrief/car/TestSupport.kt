package app.autobrief.car

import android.os.Looper
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.KST
import app.autobrief.car.domain.LedgerEntry
import app.autobrief.car.domain.NaviBookmark
import app.autobrief.car.domain.NaviEvent
import app.autobrief.car.domain.SampleHistory
import app.autobrief.car.domain.VehicleEvent
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Collections

/**
 * In-memory storage with the same two spaces as the device files. Each call is atomic, like a file save on the device:
 * the core writes the outbox from its I/O threads while its own thread writes the other parts.
 */
open class MemoryStore(var active: Space = Space.LIVE) : AutoBriefCore.Store {
    val data = mutableMapOf(Space.LIVE to AutoBriefCore.SpaceData(), Space.DEMO to AutoBriefCore.SpaceData())
    @Synchronized override fun activeSpace() = active
    @Synchronized override fun setActiveSpace(space: Space) { active = space }
    @Synchronized override fun load(space: Space) = data.getValue(space)
    @Synchronized override fun saveSettings(space: Space, settings: AppSettings) { data[space] = data.getValue(space).copy(settings = settings) }
    @Synchronized override fun saveTrips(space: Space, trips: List<TripRecord>) { data[space] = data.getValue(space).copy(trips = trips) }
    @Synchronized override fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) { data[space] = data.getValue(space).copy(chainId = chainId, ledger = entries) }
    /** How many times the outbox was written (each write is a whole-file rewrite on the device). */
    var outboxSaves = 0
    @Synchronized override fun saveOutbox(space: Space, items: List<OutboxItem>) { outboxSaves++; data[space] = data.getValue(space).copy(outbox = items) }
    @Synchronized override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) { data[space] = data.getValue(space).copy(openTrip = trip) }
    @Synchronized override fun wipe(space: Space) { data[space] = AutoBriefCore.SpaceData() }
}

/** [AutoBriefCore.handleGleo] from a test thread; with the inline background the reply is made before this returns. */
fun AutoBriefCore.gleo(function: String, params: JsonObject): AutoBriefCore.GleoReply = runBlocking { handleGleo(function, params) }

/** [AutoBriefCore.exportWorkbook] from a test thread. */
fun AutoBriefCore.exportNow(period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean = true): AutoBriefCore.ExportResult =
    runBlocking { exportWorkbook(period, businessYear, send) }

/**
 * A core on a controllable clock, with the log lines and written files captured. Its slow work runs inline unless a test
 * passes a [background] (then the log and the files fill from the core's I/O thread, and [onWrite] sees each file name
 * on the thread that writes it).
 */
class Harness(
    settings: AppSettings = AppSettings(),
    startAt: Long = at(LocalDate.of(2026, 10, 23), 8, 0),
    detailLogs: Boolean = false,
    background: AutoBriefCore.Background = AutoBriefCore.Background.inline(),
    onWrite: (String) -> Unit = {},
    /** A test that watches the store's writes passes its own (a subclass that records or holds them). */
    store: MemoryStore = MemoryStore(),
) {
    @Volatile var now = startAt
    val store: MemoryStore = store.also { it.saveSettings(Space.LIVE, settings) }
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(linkedMapOf())
    var locationRequests = 0
    val core = AutoBriefCore(
        store,
        { space, name, bytes -> onWrite(name); files["${space.dir}/$name"] = bytes; name },
        { now },
        { logs += it },
        detailLogs,
        background = background,
    )
        .also {
            it.naviControl = AutoBriefCore.NaviControl { locationRequests++ }
            // a working car: CarUxRestrictions has answered "not required" (ReconnectTest covers the unknown state)
            it.setRestricted(false)
        }

    fun odo(km: Double) = core.onVehicle(VehicleEvent.Odometer(km))
    fun gear(g: Gear) = core.onVehicle(VehicleEvent.GearChanged(g))
    fun at(p: GeoPoint) = core.onNavi(NaviEvent.Location(p))
    fun advance(ms: Long) { now += ms }

    fun bookmarks() = core.onNavi(
        NaviEvent.Bookmarks(
            listOf(
                NaviBookmark(PlaceKind.HOME, "우리집", SampleHistory.HOME.point),
                NaviBookmark(PlaceKind.OFFICE, "회사", SampleHistory.OFFICE.point),
            ),
        ),
    )

    /** Park → drive [km] → park, with positions at both ends when given (arrival position before P). */
    fun drive(fromKm: Double, km: Double, from: GeoPoint?, to: GeoPoint?, minutes: Long = 30) {
        from?.let { at(it) }
        odo(fromKm)
        gear(Gear.PARK)
        advance(60_000)
        gear(Gear.DRIVE)
        advance(minutes * 60_000)
        odo(fromKm + km)
        to?.let { at(it) }
        gear(Gear.PARK)
    }

    companion object {
        fun at(d: LocalDate, h: Int, m: Int): Long =
            ZonedDateTime.of(d.year, d.monthValue, d.dayOfMonth, h, m, 0, 0, KST).toInstant().toEpochMilli()
    }
}

/**
 * ServiceSmokeTest leaves the Pleos Vehicle SDK retrying its service bind on a background thread for about 15 s of real
 * time (the SDK's connectService retries 30 times, 500 ms apart, and release() does not stop it). A retry that lands in a
 * later Robolectric test gets Robolectric's default bind result, a null ComponentName, and the SDK's Kotlin callback
 * throws on the main looper. Put this rule before the compose rule: it refuses those binds and drops a callback that a
 * bind just before it already queued (the SDK throws at its parameter check, before doing anything).
 */
class RefuseStalePleosBinds : ExternalResource() {
    override fun before() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        PLEOS_BIND_ACTIONS.forEach { app.declareActionUnbindable(it) }
        repeat(10) {
            try {
                shadowOf(Looper.getMainLooper()).idle()
                return
            } catch (e: NullPointerException) {
                if (e.stackTrace.none { it.className.startsWith("ai.pleos.playground.") }) throw e
            }
        }
    }

    companion object {
        val PLEOS_BIND_ACTIONS = listOf("ai.pleos.playground.vehicle.VEHICLE_START", "ai.pleos.playground.intent.action.NAVI_START")
    }
}

/**
 * Compose runs a hosted activity's UI work, and its global snapshot notifications, on one process-wide dispatcher
 * (AndroidUiDispatcher.Main) that posts itself to the main looper once and waits for that message. Robolectric clears
 * the main looper after every test, so a test that ends with that message queued (an activity with Compose content
 * destroyed without idling afterwards) leaves the dispatcher waiting for good: in every later test of the same SDK,
 * no state change made outside a composition is applied and the compose rule never gets idle. Tests that host a real
 * activity use this rule, which runs what their teardown queued before the test ends.
 */
class DrainMainLooper : ExternalResource() {
    override fun after() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/**
 * Runs the main looper, one batch of due messages at a time, until [done] or [timeoutMs] of real time: work the app runs
 * on Dispatchers.IO (a real thread under Robolectric) posts its result there. Returns the longest batch in ms (a batch
 * holds every message that was due at once, so it bounds each of them).
 */
fun runMainLooperUntil(what: String, timeoutMs: Long = 20_000, done: () -> Boolean): Long {
    val until = System.nanoTime() + timeoutMs * 1_000_000
    var longest = 0L
    while (true) {
        val started = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idle()
        longest = maxOf(longest, (System.nanoTime() - started) / 1_000_000)
        if (done()) return longest
        check(System.nanoTime() < until) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** The app's core once its stored logbook is read (see [AutoBriefApp.coreState]). */
fun AutoBriefApp.loadedCore(): AutoBriefCore {
    runMainLooperUntil("the stored logbook to be read") { coreState.value != null }
    return coreState.value!!
}
