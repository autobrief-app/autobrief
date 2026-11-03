package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUse
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.FuelBasis
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import app.autobrief.car.model.VehicleSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * The logbook on one vehicle state, free of Android types so every rule is unit-testable.
 * The Android layer feeds [onVehicle]/[onNavi]/[tick], provides storage ([Store]) and file output
 * ([FileSink]), and asks navigation for a fresh position through [naviControl].
 *
 * Data lives in two separate spaces: LIVE (the real logbook) and DEMO (labelled fictional history for
 * presentations). Switching never copies anything between them, so a demo can never touch real records.
 *
 * The app makes it with [open], which reads the stored space off the calling thread; the public constructor reads it on
 * the calling thread (tests, and a restart in them). Its files are written in order off its thread ([StoreWriter]).
 */
class AutoBriefCore private constructor(
    private val store: Store,
    private val files: FileSink,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    /**
     * Place labels in the log (`POSITION place=`, the place part of `reason=`). Only debug builds turn it
     * on: a release build matches positions and keeps nothing of them, not even the owner's own place
     * names in logcat.
     */
    private val detailLogs: Boolean,
    /**
     * Milliseconds that only move forward (SystemClock.elapsedRealtime on the car): the PIN lockout runs on it, so
     * setting the car's clock ahead does not end a lock. Tests that do not care pass nothing and share [clock].
     */
    private val monotonic: () -> Long,
    /** Where the work too slow for the core's own thread runs (see [Background]); the app passes [Background.android]. */
    private val background: Background,
    /** The space to start on, read before the core is made. */
    stored: StoredSpace,
) {
    /** A core on the space the store marks in use, read on the calling thread. */
    constructor(
        store: Store,
        files: FileSink = FileSink { _, name, _ -> name },
        clock: () -> Long = System::currentTimeMillis,
        log: (String) -> Unit = {},
        detailLogs: Boolean = false,
        monotonic: () -> Long = clock,
        background: Background = Background.inline(),
    ) : this(store, files, clock, log, detailLogs, monotonic, background, StoredSpace.read(store, store.activeSpace()))
    enum class Space(val dir: String) { LIVE("live"), DEMO("demo") }

    data class SpaceData(
        val settings: AppSettings = AppSettings(),
        val trips: List<TripRecord> = emptyList(),
        val ledger: List<LedgerEntry> = emptyList(),
        /** Random identity of this ledger's chain, made with its first entry; null = a ledger from before chain ids. */
        val chainId: String? = null,
        val outbox: List<OutboxItem> = emptyList(),
        /** The trip that was being driven when the app last stopped; it is resumed at start. */
        val openTrip: OpenTripRecord? = null,
    )

    interface Store {
        fun activeSpace(): Space
        fun setActiveSpace(space: Space)
        fun load(space: Space): SpaceData
        fun saveSettings(space: Space, settings: AppSettings)
        fun saveTrips(space: Space, trips: List<TripRecord>)
        /** The chain id is saved with the entries, in the same file. */
        fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>)
        fun saveOutbox(space: Space, items: List<OutboxItem>)
        fun saveOpenTrip(space: Space, trip: OpenTripRecord?)

        /**
         * The admin PIN's miss count and lock, saved on the calling thread before the guess is checked (never through
         * [StoreWriter]: a process killed right after a wrong guess must not lose it). [load] returns them in the settings.
         */
        fun saveAdminPin(space: Space, failures: Int, lockedUntilMs: Long)
        fun wipe(space: Space)
    }

    /** Writes an exported file and returns where it went (shown to the user). */
    fun interface FileSink {
        fun write(space: Space, fileName: String, bytes: ByteArray): String
    }

    /** Lets the core ask navigation for a fresh position (the answer arrives as [NaviEvent.Location]). */
    fun interface NaviControl {
        fun requestLocation()
    }

    data class NaviStatus(
        val ready: Boolean = false,
        val location: GeoPoint? = null,
        val locationEpochMs: Long = 0,
        val bookmarks: List<NaviBookmark> = emptyList(),
        val destinationLabel: String? = null,
    )

    /** Where the position the place rules would use now comes from (see [positionSource]). */
    enum class PositionSource { NAVIGATION, GPS }

    data class OpenTripView(val startEpochMs: Long, val startOdometerKm: Double, val startPlace: String?, val family: Boolean = false)

    /** What turning on 가족 운행 did: the open trip became a family trip, or the next trip will be one. */
    enum class FamilyResult { CONVERTED, ARMED }

    data class Notice(val text: String, val fileName: String?)

    data class ExportResult(
        val fileName: String,
        val savedTo: String,
        val queued: Boolean,
        val rows: Int,
        /** The fuel settlement the workbook holds, month by month (내 차 mode); empty for a company car. */
        val fuel: List<FuelReimbursement.Month> = emptyList(),
    )

    /**
     * Where the work too slow for the core's own thread runs. The core's state belongs to the thread that calls it (the
     * main thread on the car), and [scope] runs on that thread too: it starts the work the core begins by itself (the
     * month close, a workbook the report screen asked for, the sending copies of a whole ledger). That work is built on
     * [work] and its files written on [io] from a snapshot taken on the core's thread, and what follows it (queuing its
     * sending copies, a notice, the month marked closed) happens back there. A business-year workbook made on the main
     * thread kept it busy for 5.8 s on a loaded two-core emulator: Android closed the app as not responding (5 s limit).
     * The logbook's own files (ledger, trips, queue, settings) are written on [writes], one at a time and in order (see
     * [StoreWriter]): a tap that set a trip's purpose rewrote three of them on the main thread for 7 s (rehearsal
     * 40486795890), and Android closed the app twice.
     */
    class Background(
        val scope: CoroutineScope,
        val work: CoroutineContext,
        val io: CoroutineContext,
        /** Where the logbook's files are written; null = on the calling thread, before the call returns. */
        val writes: Executor? = null,
    ) {
        companion object {
            /** The car: the main thread, Dispatchers.Default to build, Dispatchers.IO for exports, one thread for the files. */
            fun android() = Background(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), Dispatchers.Default, Dispatchers.IO, storeThread())

            /** Everything on the calling thread, in call order: for tests of what is written, not of where. */
            fun inline() = Background(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, Dispatchers.Unconfined)

            /** One thread for the logbook's files ([STORE_THREAD]); it ends after 30 s without a save and starts with the next. */
            fun storeThread(): Executor =
                ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, STORE_THREAD).apply { isDaemon = true } }
                    .apply { allowCoreThreadTimeOut(true) }
        }
    }

    /** The workbook the report screen asked for: being made, made, or failed. */
    sealed interface WorkbookRequest {
        val send: Boolean

        data class Making(override val send: Boolean) : WorkbookRequest
        data class Made(override val send: Boolean, val result: ExportResult) : WorkbookRequest
        data class Failed(override val send: Boolean, val error: String) : WorkbookRequest
    }

    var naviControl: NaviControl? = null

    /** Lets the core ask the car for its energy figures again (the answers arrive as [VehicleEvent.FuelEconomy] etc.). */
    fun interface EnergyControl {
        fun requestEnergy()
    }

    /** Asked after every trip that closes; the vehicle source also reads the figures once when it connects. */
    var energyControl: EnergyControl? = null

    /** A usable reading of the car's cumulative fuel economy (L/100 km) and when it arrived (epoch ms). */
    data class EconomyReading(val litersPer100Km: Double, val atMs: Long)

    /** The car's energy figures as last read in this process: fuel economy and, on an electric car, km per kWh. */
    data class Energy(val fuel: EconomyReading? = null, val evKmPerKwh: Double? = null)

    private val _energy = MutableStateFlow(Energy())

    /** Usable readings only: a value outside 0..50 (or none) never replaces one. */
    val energy: StateFlow<Energy> = _energy.asStateFlow()

    private var space: Space = stored.space
    private var logbook = TripLogbook()
    private var ledger = Ledger()

    /**
     * The ledger's chain id: random, made when the ledger gets its first entry and kept with it. Delivery keys and
     * payloads carry it, so the receiver keeps a demo that starts again at entry 1 as a new copy, not a forgery.
     */
    private var chainId: String? = null

    /**
     * Guards every read-modify-write of the outbox together with its save, and the space it belongs to: the sender's
     * thread marks results while the main thread queues entries or switches the space.
     */
    private val outboxLock = Any()

    /** Every save of the logbook's files goes through it: written in order on [Background.writes] (see [StoreWriter]). */
    private val writer = StoreWriter(store, background.writes, log)

    /** The keys of the queue in use as last written to the disk ([outboxLock]): only those go out (see [outboxSnapshot]). */
    private var writtenKeys: Set<String> = emptySet()

    /** The space a switch is reading ([outboxLock]), and the changes made to its stored queue meanwhile (see [enter]). */
    private var entering: Space? = null
    private val carried = mutableListOf<(List<OutboxItem>) -> List<OutboxItem>>()

    /** Counts the full checks started; one that finishes after a newer one started is dropped (core's thread only). */
    private var checkRound = 0L

    /** The full check of the chain against the rows (see [refreshCheck]); a test wraps it to see where it runs. */
    internal var verify: (List<LedgerEntry>, List<TripRecord>) -> LedgerCheck = { entries, rows -> Ledger.check(entries, rows) }

    /** The place rules a closed trip goes through ([AutoClassifier.classify]); a test wraps it to count the calls. */
    internal var placeRules: (GeoPoint?, GeoPoint?, List<Place>) -> AutoClassifier.Result =
        { start, end, places ->
            AutoClassifier.classify(start, end, places, routeDestinationName, routeDestinationPoint, _settings.value.entityName)
        }

    private val _space = MutableStateFlow(space)
    private val _settings = MutableStateFlow(AppSettings())
    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _check = MutableStateFlow(LedgerCheck(true, 0, "-", emptyList()))
    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val _sendable = MutableStateFlow(0)
    private val _restricted = MutableStateFlow(false)
    private val _navi = MutableStateFlow(NaviStatus())
    private val _open = MutableStateFlow<OpenTripView?>(null)
    private val _positionSource = MutableStateFlow<PositionSource?>(null)
    private val _notice = MutableStateFlow<Notice?>(null)
    private val _lastClosedId = MutableStateFlow<String?>(null)
    private val _workbook = MutableStateFlow<WorkbookRequest?>(null)
    private val _locationPaused = MutableStateFlow(false)

    val activeSpace: StateFlow<Space> = _space.asStateFlow()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val vehicle: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()
    val integrity: StateFlow<LedgerCheck> = _check.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()

    /**
     * Queued items not delivered yet whose queue file is on the disk: what a sending round can take now (see
     * [outboxSnapshot]). The monitor asks for a round when it changes.
     */
    val sendable: StateFlow<Int> = _sendable.asStateFlow()

    /** CarUxRestrictions: true while the screen must stay in its glanceable driving layout. */
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    val navi: StateFlow<NaviStatus> = _navi.asStateFlow()

    /**
     * The source of the position a place could be registered from now (no older than [PLACE_LOCATION_MAX_AGE_MS]):
     * navigation, the car's own GNSS, or null. Updated with every position and every [tick], so an old one ages out.
     */
    val positionSource: StateFlow<PositionSource?> = _positionSource.asStateFlow()
    val openTrip: StateFlow<OpenTripView?> = _open.asStateFlow()
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** The trip that just closed (for the "what was this drive?" card); cleared once it is handled. */
    val lastClosedId: StateFlow<String?> = _lastClosedId.asStateFlow()

    /** The report screen's workbook (see [requestWorkbook]); null = none asked for, or its message was cleared. */
    val workbook: StateFlow<WorkbookRequest?> = _workbook.asStateFlow()

    /**
     * 가족 운행 is on (armed for the next trip, or the open trip is a family trip): the monitor asks navigation for no
     * position and stops the GNSS updates, and the core drops any position that still arrives.
     */
    val locationPaused: StateFlow<Boolean> = _locationPaused.asStateFlow()

    val demoMode: Boolean get() = space == Space.DEMO

    private var routeToll: Int? = null
    private var routeDestinationName: String? = null
    private var routeDestinationPoint: GeoPoint? = null
    private var routeArrived = false
    private var openStartPoint: GeoPoint? = null

    /** The open trip's start place label: after a restart only the label is known, and it stands for the place. */
    private var openStartLabel: String? = null
    private var openTripSaved = false
    private var connectedLogged = false

    /** The open trip is a family trip (가족 운행): no position is asked for or used until it closes. */
    private var openFamily = false

    /** Family drive is on: for the open trip, or armed for the next one. */
    private val familyOn: Boolean get() = openFamily || _settings.value.familyNext

    /** The running PIN lock's end on the [monotonic] clock; null = not locked. */
    private var pinLockUntilMono: Long? = null

    /** False until this process has turned the stored miss count into its lock (see [pinLockRemainingMs]). */
    private var pinLockRestored = false

    /** A closed trip waiting a few seconds for the arrival position before the place rules run. */
    private data class PendingAuto(val tripId: String, val start: GeoPoint?, val deadline: Long, val askedAt: Long)
    private var pendingAuto: PendingAuto? = null

    /** What CarUxRestrictions last said; null = unknown (the car service has not answered, or went down). */
    private var uxRestricted: Boolean? = null

    init {
        enter(stored)
        updateRestricted()
    }

    /**
     * A space as read from the store, with what takes time to derive from it: the rows repaired from the chain and the
     * chain re-hashed against them. It can be read on any thread; [open] reads it off the main thread before the core
     * exists.
     */
    class StoredSpace internal constructor(
        val space: Space,
        val data: SpaceData,
        internal val logbook: TripLogbook,
        internal val ledger: Ledger,
        val rebuilt: Int,
        val check: LedgerCheck,
    ) {
        companion object {
            fun read(store: Store, space: Space = store.activeSpace()): StoredSpace =
                of(space, store.load(space)) { rows -> store.saveTrips(space, rows) }

            /** [d] as the core uses it; [saveRebuilt] stores the rows that had to be rebuilt from the chain. */
            fun of(space: Space, d: SpaceData, saveRebuilt: (List<TripRecord>) -> Unit = {}): StoredSpace {
                // the ledger is written before the trips file: rows the app died before storing come back from the chain
                val (rows, rebuilt) = Ledger.repair(d.trips, d.ledger)
                if (rebuilt > 0) saveRebuilt(rows)
                val logbook = TripLogbook(rows)
                val ledger = Ledger(d.ledger)
                return StoredSpace(space, d, logbook, ledger, rebuilt, Ledger.check(ledger.all, logbook.records))
            }
        }
    }

    /** Makes [o] the space in use. A backfill the app was closed in the middle of goes on (see [backfillLedger]). */
    private fun enter(o: StoredSpace) {
        val d = o.data
        synchronized(outboxLock) {
            space = o.space
            _settings.value = d.settings
            // what was read is on the disk; a change made to this space's stored queue while it was read is applied here too,
            // and goes out once the queue is written again
            val edits = if (entering == o.space) carried.toList() else emptyList()
            entering = null
            carried.clear()
            _outbox.value = edits.fold(d.outbox) { list, edit -> edit(list) }
            writtenKeys = d.outbox.mapTo(HashSet()) { it.key }
            if (edits.isNotEmpty()) saveOutbox() else refreshSendable()
        }
        if (o.rebuilt > 0) log("TRIPS_REBUILT rows=${o.rebuilt}")
        logbook = o.logbook
        ledger = o.ledger
        chainId = d.chainId
        _space.value = o.space
        _trips.value = logbook.records
        _open.value = null
        _lastClosedId.value = null
        _notice.value = null
        // a workbook still being made finishes for the space it was asked in; a finished one's message goes
        if (_workbook.value !is WorkbookRequest.Making) _workbook.value = null
        pendingAuto = null
        routeToll = null
        clearRouteDestination()
        openStartPoint = null
        openStartLabel = null
        openFamily = false
        openTripSaved = d.openTrip != null
        pinLockUntilMono = null
        pinLockRestored = false
        d.openTrip?.let(::resumeOpenTrip)
        updateLocationPaused()
        checkRound++ // a check of the logbook left behind must not land on this one
        _check.value = o.check
        // the new logbook needs the car's current state to detect the next departure correctly
        _vehicle.value.odometerKm?.let { logbook.seedOdometer(it) }
        _vehicle.value = _vehicle.value.copy(odometerKm = logbook.odometerKm)
        logbook.onGear(_vehicle.value.gear, clock())
        d.settings.ledgerBackfillThroughSeq.takeIf { it > 0 }?.let(::backfillLedger)
    }

    /** The trip that was open when the app stopped goes on (it closes at the next P); one already written is dropped. */
    private fun resumeOpenTrip(t: OpenTripRecord) {
        if (logbook.resume(TripLogbook.OpenTrip(t.startEpochMs, t.startOdometerKm, t.tollEstimateKrw))) {
            openFamily = t.family
            openStartLabel = t.startPlace.takeUnless { t.family }
            _open.value = OpenTripView(t.startEpochMs, t.startOdometerKm, openStartLabel, t.family)
            log("TRIP_RESUMED odo=${t.startOdometerKm}" + if (t.family) " family=true" else "")
        } else {
            saveOpenTrip(null)
        }
    }

    private fun saveOpenTrip(t: OpenTripRecord?) {
        writer.saveOpenTrip(space, t)
        openTripSaved = t != null
    }

    /** The open trip as saved: its start values, the toll estimate known so far and the start place label. */
    private fun saveOpenTripNow() {
        val o = logbook.openTrip ?: return
        // a family trip keeps only its distance: no toll estimate either
        saveOpenTrip(OpenTripRecord(o.startEpochMs, o.startOdometerKm, o.tollEstimateKrw.takeUnless { openFamily }, openStartLabel, openFamily))
    }

    /**
     * The driving restrictions from the car service (CarServices); null = unknown. Once the platform's state is known
     * it wins. While it is unknown the screen fails closed: restricted unless a live vehicle link says the car is in P
     * (a gear from a link that is gone says nothing).
     */
    fun setRestricted(value: Boolean?) {
        uxRestricted = value
        updateRestricted()
    }

    private fun updateRestricted() {
        val v = _vehicle.value
        _restricted.value = uxRestricted ?: !(v.sdkConnected && v.gear == Gear.PARK)
    }

    // ---------------------------------------------------------------- vehicle and navigation input

    fun onVehicle(e: VehicleEvent) {
        val now = clock()
        val s = _vehicle.value
        when (e) {
            is VehicleEvent.Connection -> {
                // a lost link leaves no gear behind: the next one comes from the SDK again
                _vehicle.value = s.copy(sdkConnected = e.connected, gear = if (e.connected) s.gear else Gear.UNKNOWN)
                updateRestricted()
                if (e.connected && !connectedLogged) {
                    connectedLogged = true
                    log("VEHICLE_CONNECTED")
                }
            }
            is VehicleEvent.Odometer -> {
                val added = logbook.onOdometer(e.km)
                // the screen shows the value the logbook took, never a NaN or a start-up 0
                _vehicle.value = s.copy(odometerKm = logbook.odometerKm)
                if (added.isNotEmpty()) recordRows(added)
                if (logbook.openTrip != null && _open.value == null) openTripStarted()
            }
            is VehicleEvent.GearChanged -> onGear(e.gear, now)
            is VehicleEvent.Vin -> onVin(e.vin)
            is VehicleEvent.FuelEconomy -> onFuelEconomy(e.litersPer100Km, now)
            is VehicleEvent.EvEfficiency -> onEvEfficiency(e.kmPerKwh)
        }
    }

    /**
     * The car's cumulative fuel economy, logged as it came (one line, so an emulator run shows what the image reports).
     * A usable one (0 < v < 50 L/100 km) is kept with its time, in memory and in the settings of the space in use.
     */
    private fun onFuelEconomy(l100: Double?, now: Long) {
        val valid = FuelReimbursement.validEconomy(l100)
        log("FUEL_ECONOMY l100=${l100 ?: "-"} valid=$valid")
        if (!valid || l100 == null) return
        _energy.update { it.copy(fuel = EconomyReading(l100, now)) }
        writeSettings(_settings.value.copy(fuelEconomyL100 = l100, fuelEconomyAtMs = now))
    }

    /** The car's cumulative EV efficiency (km/kWh), logged as it came; only used to tell an electric car apart. */
    private fun onEvEfficiency(kmPerKwh: Double?) {
        val valid = kmPerKwh != null && kmPerKwh.isFinite() && kmPerKwh > 0 && kmPerKwh < FuelReimbursement.MAX_LITERS_PER_100KM
        log("EV_EFFICIENCY kmkwh=${kmPerKwh ?: "-"} valid=$valid")
        if (valid) _energy.update { it.copy(evKmPerKwh = kmPerKwh) }
    }

    /**
     * The VIN as the SDK sends it (JSON-quoted; the emulator sends the placeholder "invalid"). A well-formed one is kept
     * in the settings too, so payloads and trip bodies name the car before the SDK answers after a restart.
     */
    private fun onVin(raw: String?) {
        val vin = Vins.normalize(raw)
        if (vin == null) {
            log("VIN_UNUSABLE")
            return
        }
        _vehicle.value = _vehicle.value.copy(vin = vin)
        if (_settings.value.vin != vin) writeSettings(_settings.value.copy(vin = vin))
    }

    /** The VIN read from the car now, else the one kept from before; null when the car never gave a usable one. */
    private fun currentVin(): String? = _vehicle.value.vin ?: _settings.value.vin.ifBlank { null }

    fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.Ready -> _navi.update { it.copy(ready = e.ready) }
            // the route's toll estimate and matched business/commute destination (personal/medical names are never stored)
            is NaviEvent.RouteStarted -> if (!familyOn) {
                routeToll = e.tollFareKrw
                if (!e.destinationName.isNullOrBlank()) routeDestinationName = e.destinationName
                routeArrived = false
                refreshNaviDestinationLabel()
                updateOpenRoute()
            }
            is NaviEvent.Destination -> if (!familyOn) {
                if (e.tollFareKrw != null) routeToll = e.tollFareKrw
                if (!e.name.isNullOrBlank()) routeDestinationName = e.name
                if (e.point != null) routeDestinationPoint = e.point
                refreshNaviDestinationLabel()
                updateOpenRoute()
                if (pendingAuto != null && (e.point != null || matchedNaviDestination() != null)) {
                    resolvePendingAuto(freshLocation(clock(), LOCATION_FRESH_MS) ?: e.point)
                }
            }
            is NaviEvent.Arrived -> if (!familyOn) {
                if (!e.name.isNullOrBlank()) routeDestinationName = e.name
                routeArrived = true
                refreshNaviDestinationLabel()
                if (pendingAuto != null) {
                    resolvePendingAuto(freshLocation(clock(), LOCATION_FRESH_MS) ?: routeDestinationPoint)
                }
            }
            NaviEvent.RouteEnded -> {
                routeToll = null
                if (logbook.openTrip == null && pendingAuto == null) clearRouteDestination()
            }
            // family drive: a position that still arrives is not kept, not used and not logged
            is NaviEvent.Location -> if (!familyOn) {
                val now = clock()
                _navi.update { it.copy(ready = true, location = e.point, locationEpochMs = now) }
                updatePositionSource(now)
                logPosition(now)
                pendingAuto?.let { p -> if (now > p.askedAt) resolvePendingAuto(freshLocation(now, LOCATION_FRESH_MS) ?: e.point) }
            }
            is NaviEvent.Bookmarks -> {
                _navi.update { it.copy(ready = true, bookmarks = e.places) }
                refreshNaviDestinationLabel()
            }
        }
    }

    private fun matchedNaviDestination(): Place? =
        if (familyOn) null
        else AutoClassifier.matchNaviDestination(routeDestinationName, allPlaces(), _settings.value.entityName, routeDestinationPoint)

    private fun refreshNaviDestinationLabel() {
        val label = matchedNaviDestination()?.label
        _navi.update { it.copy(destinationLabel = label) }
    }

    private fun clearRouteDestination() {
        routeDestinationName = null
        routeDestinationPoint = null
        routeArrived = false
        _navi.update { it.copy(destinationLabel = null) }
    }

    /** Periodic call (every few seconds): pending place rules time out, the month closes. */
    fun tick() {
        val now = clock()
        updatePositionSource(now)
        pendingAuto?.let { if (now >= it.deadline) resolvePendingAuto(freshLocation(now, LOCATION_FRESH_MS)) }
        closeMonthIfDue()
    }

    private fun updateOpenRoute() {
        val before = logbook.openTrip ?: return
        logbook.updateOpenRoute(routeToll)
        if (logbook.openTrip != before) saveOpenTripNow()
    }

    /**
     * A fix from the car's own GNSS (PlatformLocationSource). Only a usable one counts: real coordinates, not (0, 0),
     * and precise enough to tell places apart. Like navigation's position it stays in memory.
     */
    fun onPlatformFix(fix: PlatformFix) {
        if (familyOn) return // family drive: dropped unread, like navigation's
        val p = fix.point
        if (!p.lat.isFinite() || !p.lon.isFinite() || p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return
        if (p.lat == 0.0 && p.lon == 0.0) return
        if (fix.accuracyM != null && !(fix.accuracyM <= MAX_FIX_ACCURACY_M)) return
        val now = clock()
        val taken = minOf(fix.epochMs, now)
        if (gnssFix?.let { it.epochMs > taken } == true) return // an older fix than the one held (two providers)
        gnssFix = Fix(p, taken, gnss = true)
        updatePositionSource(now)
        logPosition(now)
        pendingAuto?.let { pending -> if (taken > pending.askedAt) resolvePendingAuto(freshLocation(now, LOCATION_FRESH_MS) ?: p) }
    }

    /** A position and where it came from; coordinates never leave memory. */
    private data class Fix(val point: GeoPoint, val epochMs: Long, val gnss: Boolean)

    /** The last usable fix of the car's own GNSS. */
    private var gnssFix: Fix? = null

    /**
     * Where the car is, no older than [maxAgeMs]: navigation's position while it is younger than [NAVI_PREFERRED_MS]
     * (map-matched, it also works in tunnels and car parks), otherwise the platform GNSS fix, otherwise the newer of the
     * two. A fresh navigation position more than [NAVI_GNSS_AGREE_M] from a fresh GNSS fix does not follow the car
     * (NaviHelper relays the navigation app, which stops reading GPS when it is not in front, and on the emulator never
     * reads the injected GPS): the GNSS fix wins then.
     */
    private fun position(now: Long, maxAgeMs: Long): Fix? {
        val navi = _navi.value.location?.let { Fix(it, _navi.value.locationEpochMs, gnss = false) }?.takeIf { now - it.epochMs <= maxAgeMs }
        val gnss = gnssFix?.takeIf { now - it.epochMs <= maxAgeMs }
        val naviFresh = navi?.takeIf { now - it.epochMs <= NAVI_PREFERRED_MS }
        val gnssFresh = gnss?.takeIf { now - it.epochMs <= NAVI_PREFERRED_MS }
        return when {
            naviFresh != null && gnssFresh != null ->
                if (AutoClassifier.distanceM(naviFresh.point, gnssFresh.point) <= NAVI_GNSS_AGREE_M) naviFresh else gnssFresh
            naviFresh != null -> naviFresh
            else -> listOfNotNull(gnss, navi).maxByOrNull { it.epochMs }
        }
    }

    private fun freshLocation(now: Long, maxAgeMs: Long): GeoPoint? = position(now, maxAgeMs)?.point

    private fun updatePositionSource(now: Long) {
        _positionSource.value = position(now, PLACE_LOCATION_MAX_AGE_MS)?.let { if (it.gnss) PositionSource.GPS else PositionSource.NAVIGATION }
    }

    /**
     * Debug builds: which registered place the car is at now, by label (never the coordinates), and `source=gps` when
     * the position is the platform GNSS fix. The demo director waits for it before it departs or parks, so the place
     * rules see the move. Release builds log nothing of positions.
     */
    private fun logPosition(now: Long) {
        if (!detailLogs) return
        val p = position(now, LOCATION_FRESH_MS) ?: return
        log("POSITION place=${AutoClassifier.nearest(p.point, allPlaces())?.label ?: "-"}" + if (p.gnss) " source=gps" else "")
    }

    private fun onGear(gear: Gear, now: Long) {
        _vehicle.value = _vehicle.value.copy(gear = gear)
        updateRestricted()
        val wasOpen = logbook.openTrip != null
        val added = logbook.onGear(gear, now, routeToll)
        if (added.isNotEmpty()) recordRows(added)
        if (!wasOpen && logbook.openTrip != null) openTripStarted()
        // P again without moving (a gear shuffle) writes no row: nothing is being recorded any more
        if (logbook.openTrip == null) {
            _open.value = null
            openStartPoint = null
            openStartLabel = null
            // a family trip that moved nowhere wrote no row; an armed family drive stays armed for the next one
            openFamily = false
            updateLocationPaused()
            if (openTripSaved) saveOpenTrip(null)
        }
        if ((gear == Gear.PARK || gear == Gear.DRIVE) && !familyOn) naviControl?.requestLocation()
    }

    /** The last trip's card stays until it is handled: a gear shuffle that writes no row must not hide it. */
    private fun openTripStarted() {
        val open = logbook.openTrip ?: return
        val now = clock()
        // a trip that opens while family drive is armed is a family trip: no start position, no start place
        if (_settings.value.familyNext) openFamily = true
        openStartPoint = if (openFamily) null else freshLocation(now, START_LOCATION_MAX_AGE_MS)
        val label = openStartPoint?.let { AutoClassifier.nearest(it, allPlaces()) }?.label
        openStartLabel = label
        _open.value = OpenTripView(open.startEpochMs, open.startOdometerKm, label, openFamily)
        saveOpenTripNow()
        updateLocationPaused()
        log("TRIP_OPENED odo=${open.startOdometerKm}" + if (openFamily) " family=true" else "")
    }

    /** Rows just produced by the logbook: gap rows and closed trips go into the ledger as they are. */
    private fun recordRows(added: List<TripRecord>) {
        for (row in added) {
            if (row.purpose == TripPurpose.UNLOGGED_GAP) {
                appendLedger(LedgerKind.GAP, row, Ledger.tripBody(row, currentVin()))
                log("GAP_RECORDED start=${row.startOdometerKm} end=${row.endOdometerKm}")
            } else {
                tripClosed(row)
            }
        }
        saveTrips()
        // cleared only after the row is stored: a restart in between finds the row and drops the saved trip
        if (logbook.openTrip == null && openTripSaved) saveOpenTrip(null)
    }

    private fun tripClosed(row: TripRecord) {
        energyControl?.requestEnergy()
        if (openFamily) {
            familyTripClosed(row)
            return
        }
        val now = clock()
        _open.value = null
        _lastClosedId.value = row.id
        val start = openStartPoint ?: openStartLabel?.let { label -> allPlaces().firstOrNull { it.label == label }?.point }
        openStartPoint = null
        openStartLabel = null
        val arrival = freshLocation(now, ARRIVAL_LOCATION_MAX_AGE_MS)
        val hasNaviDest = matchedNaviDestination() != null
        // positions may come from navigation or from the car's own GNSS: either one can still deliver the arrival
        if (_settings.value.autoClassify && arrival == null && !routeArrived && routeDestinationPoint == null &&
            (start != null || _navi.value.ready || gnssFix != null || hasNaviDest)
        ) {
            // the position at the moment of parking is not in yet: write the row now, run the place rules shortly
            appendLedger(LedgerKind.TRIP, row, Ledger.tripBody(row, currentVin()))
            pendingAuto = PendingAuto(row.id, start, now + AUTO_WAIT_MS, now)
            naviControl?.requestLocation()
            logClosed(row)
            return
        }
        applyPlaces(row, start, arrival, writeTrip = true)
    }

    /**
     * A family trip parks: its row is 비업무 with the memo "가족 운행", marked family in the ledger, with no place fields and
     * without the place rules. Family drive ends with it: positions are asked for and used again from here.
     */
    private fun familyTripClosed(row: TripRecord) {
        _open.value = null
        _lastClosedId.value = row.id
        openFamily = false
        openStartPoint = null
        openStartLabel = null
        routeToll = null
        clearRouteDestination()
        val marked = logbook.markFamily(row.id) ?: row
        appendLedger(LedgerKind.TRIP, marked, Ledger.tripBody(marked, currentVin()))
        val (_, updated) = logbook.classify(marked.id, TripPurpose.PERSONAL, defaultUser(), FAMILY_MEMO, ClassifiedBy.FAMILY) ?: return
        appendLedger(LedgerKind.CLASSIFY, updated, Ledger.classifyBody(updated))
        if (_settings.value.familyNext) writeSettings(_settings.value.copy(familyNext = false))
        updateLocationPaused()
        logClosed(updated)
        closeMonthIfDue()
    }

    private fun resolvePendingAuto(arrival: GeoPoint?) {
        val p = pendingAuto ?: return
        pendingAuto = null
        val row = logbook.find(p.tripId) ?: return
        applyPlaces(row, p.start, arrival, writeTrip = false)
        saveTrips()
    }

    /** Place labels for the row; when a place rule decides the purpose, the trip is classified automatically. */
    private fun applyPlaces(row: TripRecord, start: GeoPoint?, end: GeoPoint?, writeTrip: Boolean) {
        val result = placeRules(start, end, allPlaces())
        clearRouteDestination()
        val placed = logbook.setPlaces(row.id, result.startLabel, result.endLabel) ?: row
        if (writeTrip) appendLedger(LedgerKind.TRIP, placed, Ledger.tripBody(placed, currentVin()))
        val purpose = result.purpose
        if (purpose != null && _settings.value.autoClassify && placed.purpose == TripPurpose.UNCLASSIFIED) {
            val (_, updated) = logbook.classify(placed.id, purpose, defaultUser(), result.memo, ClassifiedBy.AUTO, result.reason) ?: return
            appendLedger(LedgerKind.CLASSIFY, updated, Ledger.classifyBody(updated))
            if (writeTrip) logClosed(updated)
            else log("TRIP_CLASSIFIED id=${updated.id} purpose=${updated.purpose.name} by=${ClassifiedBy.AUTO} reason=${logReason(result.reason)}")
        } else if (writeTrip) {
            logClosed(placed)
        }
        closeMonthIfDue()
    }

    private fun logClosed(r: TripRecord) {
        log(
            "TRIP_CLOSED id=${r.id} start=${r.startOdometerKm} end=${r.endOdometerKm} km=${TripLogbook.round1(r.distanceKm)} " +
                "purpose=${r.purpose.name} by=${r.classifiedBy ?: "-"} reason=${logReason(r.autoReason)}" +
                if (r.family) " family=true" else "",
        )
    }

    /** The automatic rule's reason names places ("집→회사"): only debug builds log it, release logs `hidden`. */
    private fun logReason(reason: String?): String = when {
        reason == null -> "-"
        detailLogs -> reason
        else -> "hidden"
    }

    // ---------------------------------------------------------------- family drive (가족 운행)

    /**
     * Turns on 가족 운행 (the home switch, or voice): a trip already open becomes a family trip and the start place it
     * captured is dropped; parked with no open trip, the next trip will be one. From now until that trip closes no
     * position is asked for or used, and the positions held so far are forgotten.
     */
    fun startFamilyDrive(): FamilyResult {
        if (logbook.openTrip != null) {
            if (!openFamily) {
                openFamily = true
                openStartPoint = null
                openStartLabel = null
                _open.value = _open.value?.copy(startPlace = null, family = true)
                saveOpenTripNow()
                updateLocationPaused()
                log("FAMILY_DRIVE on=open")
            }
            return FamilyResult.CONVERTED
        }
        if (!_settings.value.familyNext) {
            writeSettings(_settings.value.copy(familyNext = true))
            updateLocationPaused()
            log("FAMILY_DRIVE on=next")
        }
        return FamilyResult.ARMED
    }

    /** The home switch turned off before the drive: the next trip is an ordinary one again. A family trip under way stays one. */
    fun cancelFamilyDrive() {
        if (!_settings.value.familyNext) return
        writeSettings(_settings.value.copy(familyNext = false))
        updateLocationPaused()
        log("FAMILY_DRIVE off")
    }

    /**
     * Follows [familyOn]. When it turns on, a trip whose place rules still wait for the arrival position (the drive before
     * this one) is settled with what is held now, then every position held is forgotten.
     */
    private fun updateLocationPaused() {
        val on = familyOn
        if (on && !_locationPaused.value) {
            pendingAuto?.let { resolvePendingAuto(freshLocation(clock(), LOCATION_FRESH_MS)) }
            routeToll = null
            clearRouteDestination()
            _navi.update { it.copy(location = null, locationEpochMs = 0) }
            gnssFix = null
            _positionSource.value = null
        }
        _locationPaused.value = on
    }

    // ---------------------------------------------------------------- places

    /** Navigation bookmarks (HOME/OFFICE/CLIENT) first, then the places registered in the app. */
    fun allPlaces(): List<Place> =
        _navi.value.bookmarks.mapNotNull {
            val label = when (it.kind) {
                PlaceKind.HOME -> "집"
                PlaceKind.OFFICE -> "회사"
                PlaceKind.CLIENT -> sanitizeMemo(DisplayMasking.mask(it.name)).take(20).ifBlank { return@mapNotNull null }
            }
            Place(label, it.kind, it.point, fromNavigation = true)
        } + _settings.value.places

    enum class PlaceResult { SAVED, NO_LOCATION, INVALID }

    /** Saves the car's current position as a place (the label is what trips will show). */
    fun registerPlaceHere(label: String, kind: PlaceKind): PlaceResult {
        val name = sanitizeMemo(label).take(20)
        if (name.isBlank()) return PlaceResult.INVALID
        val here = freshLocation(clock(), PLACE_LOCATION_MAX_AGE_MS) ?: return PlaceResult.NO_LOCATION
        val others = _settings.value.places.filterNot { it.label == name || (kind != PlaceKind.CLIENT && it.kind == kind) }
        writeSettings(_settings.value.copy(places = others + Place(name, kind, here)))
        return PlaceResult.SAVED
    }

    fun removePlace(label: String) {
        writeSettings(_settings.value.copy(places = _settings.value.places.filterNot { it.label == label }))
    }

    // ---------------------------------------------------------------- classification

    enum class ClassifyResult { OK, NOT_FOUND, REASON_REQUIRED }

    /**
     * Sets a purpose from the screen or voice. Changing a purpose that was already set (also an automatic
     * one) is a correction: it needs a reason and is written as an AMEND entry with the previous purpose.
     */
    fun classifyTrip(
        id: String,
        purpose: TripPurpose,
        user: CarUser?,
        memo: String,
        source: String,
        reason: String? = null,
    ): ClassifyResult {
        val before = logbook.find(id) ?: return ClassifyResult.NOT_FOUND
        if (before.purpose == TripPurpose.UNLOGGED_GAP) return ClassifyResult.NOT_FOUND
        val correcting = before.purpose != TripPurpose.UNCLASSIFIED
        val why = sanitizeMemo(reason)
        if (correcting && why.isBlank()) return ClassifyResult.REASON_REQUIRED
        val (prev, updated) = logbook.classify(id, purpose, user ?: before.user ?: defaultUser(), sanitizeMemo(memo), source)
            ?: return ClassifyResult.NOT_FOUND
        if (correcting) appendLedger(LedgerKind.AMEND, updated, Ledger.amendBody(updated, prev.purpose, why))
        else appendLedger(LedgerKind.CLASSIFY, updated, Ledger.classifyBody(updated))
        if (pendingAuto?.tripId == id) {
            pendingAuto = null
            clearRouteDestination()
        }
        if (_lastClosedId.value == id) _lastClosedId.value = null
        log("TRIP_CLASSIFIED id=$id purpose=${purpose.name} by=$source${if (correcting) " amended" else ""}")
        saveTrips()
        return ClassifyResult.OK
    }

    fun dismissLastClosed() {
        _lastClosedId.value = null
    }

    /** Every ledger entry behind one row, oldest first (shown in the trip detail). */
    fun ledgerFor(tripId: String): List<LedgerEntry> = ledger.all.filter { it.tripId == tripId }

    val ledgerEntries: List<LedgerEntry> get() = ledger.all

    fun dismissNotice() {
        _notice.value = null
    }

    private fun defaultUser(): CarUser? = _settings.value.users.getOrNull(_settings.value.defaultUserIndex)

    // ---------------------------------------------------------------- ledger, storage and outbox

    /** The ledger's chain id (see [chainId]); null until the first entry, or for a ledger from before chain ids. */
    val ledgerChainId: String? get() = chainId

    private fun appendLedger(kind: String, trip: TripRecord, body: JsonObject): LedgerEntry {
        if (ledger.size == 0) chainId = newChainId()
        val entry = ledger.append(kind, trip.id, body, clock())
        writer.saveLedger(space, chainId, ledger.all)
        log("LEDGER seq=${entry.seq} kind=$kind hash=${entry.hash.take(8)}")
        if (_settings.value.archiveWebhookUrl.isNotBlank()) {
            enqueue(Queued(ledgerKey(entry), LEDGER_KIND, ledgerPayload(entry), entry.seq))
        }
        return entry
    }

    /** One delivery key per entry of one chain; a ledger from before chain ids keeps its old keys. */
    private fun ledgerKey(e: LedgerEntry, space: Space = this.space, chainId: String? = this.chainId): String =
        chainId?.let { "ledger-${space.dir}-$it-${e.seq}" } ?: "ledger-${space.dir}-${e.seq}"

    /** The car block of every payload, as the car is known when the item is queued (see [wireBody]). */
    private fun carJson() = buildJsonObject {
        put("vin", currentVin())
        put("plate", _settings.value.plateNumber)
        put("model", _settings.value.vehicleModel)
        put("entity", _settings.value.entityName)
    }

    /** The sending copy of [e]; the defaults read the core's state, so only its own thread may leave them out. */
    private fun ledgerPayload(
        e: LedgerEntry,
        space: Space = this.space,
        chainId: String? = this.chainId,
        car: JsonObject = carJson(),
    ): String = buildJsonObject {
        put("type", "ledger_entry")
        put("app", "autobrief")
        put("appVersion", APP_VERSION)
        chainId?.let { put("chainId", it) }
        // the receiver starts a new demo chain silently; a new live chain for a known car is a warning there
        put("space", space.dir)
        put("car", car)
        put("entry", buildJsonObject {
            put("seq", e.seq)
            put("epochMs", e.epochMs)
            put("kind", e.kind)
            put("tripId", e.tripId)
            put("body", e.body)
            put("prevHash", e.prevHash)
            put("hash", e.hash)
        })
    }.toString()

    /**
     * The body sent for [item]: the payload as it was queued, car block included, so an entry keeps the plate, company
     * and VIN it was recorded under. Only a VIN that block lacks (it can arrive after the first gear event) is filled
     * in from the VIN the app has kept since.
     */
    fun wireBody(item: OutboxItem): String = fillMissingVin(item.payloadJson, currentVin())

    private fun saveTrips() {
        _trips.value = logbook.records
        writer.saveTrips(space, logbook.records)
        refreshCheck()
    }

    /**
     * The verification code follows the newest entry at once. The full check (every hash computed again and the rows
     * replayed from the chain) took seconds of the main thread after each purpose tap on a loaded head unit with a year of
     * trips: it runs on [Background.work] and its verdict lands back here, unless a newer change or a switch came first.
     */
    private fun refreshCheck() {
        val round = ++checkRound
        val entries = ledger.all
        val rows = logbook.records
        _check.value = _check.value.copy(entryCount = entries.size, code = Ledger.code(entries.lastOrNull()?.hash))
        background.scope.launch {
            val checked = withContext(background.work) {
                if (round != checkRound) null else verify(entries, rows)
            }
            if (checked != null && round == checkRound) _check.value = checked
        }
    }


    private class Queued(val key: String, val kind: String, val payload: String, val seq: Long? = null)

    private fun enqueue(item: Queued) = enqueueAll(listOf(item))

    /**
     * One update and ONE write for a whole batch: the outbox file is rewritten whole, so a write per item is quadratic.
     * On the core's thread only (the queue in use belongs to it). Items made for a space no longer in use (a workbook that
     * finished after a switch) go to that space's stored queue (see [updateStoredOutbox]).
     */
    private fun enqueueAll(items: List<Queued>, into: Space? = null) {
        if (items.isEmpty()) return
        synchronized(outboxLock) {
            val target = into ?: space
            if (target != space) {
                updateStoredOutbox(target) { list -> withQueued(list, items) }
                return
            }
            val next = withQueued(_outbox.value, items)
            if (next === _outbox.value) return
            _outbox.value = next
            saveOutbox()
        }
    }

    /** [list] with those of [items] it does not hold yet (by key), pruned; [list] itself when it holds them all. */
    private fun withQueued(list: List<OutboxItem>, items: List<Queued>): List<OutboxItem> {
        val known = list.mapTo(HashSet()) { it.key }
        val fresh = items.filter { known.add(it.key) }.map { OutboxItem(it.key, it.kind, it.payload, clock(), seq = it.seq) }
        return if (fresh.isEmpty()) list else prune(list + fresh)
    }

    /**
     * Changes the stored queue of [target], a space not in use, on the writing thread after what it still has to write.
     * A switch to [target] that is reading it meanwhile gets the change too (see [enter]). Under [outboxLock].
     */
    private fun updateStoredOutbox(target: Space, edit: (List<OutboxItem>) -> List<OutboxItem>) {
        if (entering == target) carried += edit
        writer.updateOutbox(target, edit)
    }

    /** Hands the queue in use to the writer; its items may go out once it is written (see [outboxSnapshot]). Under [outboxLock]. */
    private fun saveOutbox() {
        val s = space
        val items = _outbox.value
        writer.saveOutbox(s, items) { outboxWritten(s, items) }
        refreshSendable()
    }

    /** On the writing thread: [items] are on the disk as the queue of [s]. */
    private fun outboxWritten(s: Space, items: List<OutboxItem>) {
        synchronized(outboxLock) {
            if (s != space) return
            writtenKeys = items.mapTo(HashSet()) { it.key }
            refreshSendable()
        }
    }

    /** Under [outboxLock]. */
    private fun refreshSendable() {
        _sendable.value = _outbox.value.count { it.deliveredEpochMs == null && it.key in writtenKeys }
    }

    /** Bounded queue in queueing order: delivered items are kept only as a short history, undelivered ones up to a hard cap. */
    private fun prune(list: List<OutboxItem>): List<OutboxItem> {
        val drop = HashSet<String>()
        list.filter { it.deliveredEpochMs != null }.dropLast(OUTBOX_DELIVERED_KEEP).mapTo(drop) { it.key }
        list.filter { it.deliveredEpochMs == null }.dropLast(OUTBOX_PENDING_MAX).mapTo(drop) { it.key }
        return if (drop.isEmpty()) list else list.filterNot { it.key in drop }
    }

    /** One send result for an outbox item. */
    data class Delivery(val key: String, val delivered: Boolean, val error: String?)

    /**
     * What one sending round works from, taken at once: the space, its address, its queue, and the VIN the app keeps
     * (filled into a queued car block that has none). A space switch mid-round cannot pair one space's entries with
     * the other space's address.
     */
    data class OutboxSnapshot(val space: Space, val url: String, val items: List<OutboxItem>, val vin: String?) {
        fun body(item: OutboxItem): String = fillMissingVin(item.payloadJson, vin)
    }

    fun outboxSnapshot(): OutboxSnapshot = synchronized(outboxLock) {
        val s = _settings.value
        // while a backfill is still to be queued no ledger entry goes out: a newer one must not reach the receiver first
        val held = s.ledgerBackfillThroughSeq > 0
        // only what is on the disk goes out: the ledger is written before the queue, so the receiver never holds an entry the
        // car could still lose (after a restart the car's next entry would take its number: a forgery to the receiver)
        val items = _outbox.value.filter { it.key in writtenKeys && !(held && it.kind == LEDGER_KIND) }
        OutboxSnapshot(space, s.archiveWebhookUrl, items, currentVin())
    }

    /** The round's space and address are still the ones in use; a switch or a new address ends the round. */
    fun isCurrent(snapshot: OutboxSnapshot): Boolean = synchronized(outboxLock) {
        snapshot.space == space && snapshot.url == _settings.value.archiveWebhookUrl
    }

    /** Results for the space in use (see the other overload). */
    fun markDeliveries(results: List<Delivery>) = markDeliveries(space, results)

    /**
     * Called from the sender's I/O thread with a batch of results for [space] (one write). Results for a space that is
     * no longer in use go to that space's stored queue. A workbook that failed [ARCHIVE_MAX_ATTEMPTS] times is given up
     * (it stays saved in the car); ledger entries are never given up, so 대기 counts exactly what will still arrive.
     */
    fun markDeliveries(space: Space, results: List<Delivery>) {
        if (results.isEmpty()) return
        val byKey = results.associateBy { it.key }
        val now = clock()
        fun apply(list: List<OutboxItem>): List<OutboxItem> = prune(
            list.mapNotNull { item ->
                val d = byKey[item.key] ?: return@mapNotNull item
                val next = item.copy(
                    attempts = item.attempts + 1,
                    deliveredEpochMs = if (d.delivered) now else null,
                    lastError = d.error,
                    lastAttemptEpochMs = now,
                )
                if (next.kind != LEDGER_KIND && next.deliveredEpochMs == null && next.attempts >= ARCHIVE_MAX_ATTEMPTS) {
                    log("OUTBOX_GAVE_UP kind=${next.kind} attempts=${next.attempts}")
                    null
                } else {
                    next
                }
            },
        )
        synchronized(outboxLock) {
            if (space == this.space) {
                _outbox.value = apply(_outbox.value)
                saveOutbox()
            } else {
                updateStoredOutbox(space, ::apply)
            }
        }
    }

    // ---------------------------------------------------------------- settings, PIN, demo space

    val adminPinSet: Boolean get() = _settings.value.adminPinHash.isNotEmpty()

    enum class SettingsResult { SAVED, PIN_REQUIRED, PIN_LOCKED, INVALID_URL, TRIP_IN_PROGRESS }

    /** The lock that follows [failures] misses in a row: 5 minutes from the fifth, doubling every five up to 80. */
    private fun pinLockLengthMs(failures: Int): Long {
        val rounds = failures / PIN_ATTEMPTS_BEFORE_LOCK
        return if (rounds == 0) 0 else PIN_LOCK_MS * (1L shl (rounds - 1).coerceAtMost(4))
    }

    /**
     * How long PIN entry stays locked, always measured on the monotonic clock. A restart cannot know how much of a lock
     * already ran (the wall clock may have been moved), so while the stored miss count is at or past the threshold
     * (only a correct PIN clears it) the first check after a restart locks again for that round's full length.
     */
    private fun pinLockRemainingMs(s: AppSettings): Long {
        if (!pinLockRestored) {
            pinLockRestored = true
            val length = pinLockLengthMs(s.adminPinFailures)
            if (length > 0) pinLockUntilMono = monotonic() + length
        }
        return pinLockUntilMono?.let { (it - monotonic()).coerceAtLeast(0) } ?: 0
    }

    /** Until when PIN entry is locked (epoch ms); 0 = not locked. */
    val pinLockedUntil: Long get() = pinLockRemainingMs(_settings.value).takeIf { it > 0 }?.let { clock() + it } ?: 0

    /**
     * The one place an admin PIN is checked. No PIN at all is a request for one, not a wrong guess. Every fifth wrong
     * PIN in a row locks entry for 5 minutes, doubling up to 80 minutes; the counters are kept on the disk so a restart
     * does not reset them. A PIN stored by an older version is re-hashed on its next correct entry.
     *
     * A guess is counted on the disk before it is checked, and a correct one clears the count on the disk before it opens
     * anything (both written at once on this thread, see [savePinState]): killing the app right after a wrong guess cannot
     * take the guess back. Storage that refuses the count refuses the check (PIN_LOCKED).
     */
    private fun checkAdminPin(pin: String?): SettingsResult {
        val s = _settings.value
        if (s.adminPinHash.isEmpty()) return SettingsResult.SAVED
        if (pinLockRemainingMs(s) > 0) return SettingsResult.PIN_LOCKED
        if (pin.isNullOrBlank()) return SettingsResult.PIN_REQUIRED
        val failures = s.adminPinFailures + 1
        val lockMs = if (failures % PIN_ATTEMPTS_BEFORE_LOCK == 0) pinLockLengthMs(failures) else 0L
        val counted = s.copy(adminPinFailures = failures, adminPinLockedUntilMs = if (lockMs > 0) clock() + lockMs else s.adminPinLockedUntilMs)
        if (!savePinState(counted)) return SettingsResult.PIN_LOCKED
        if (AdminPin.matches(pin, s.adminPinSalt, s.adminPinHash)) {
            var next = s.copy(adminPinFailures = 0, adminPinLockedUntilMs = 0)
            if (AdminPin.isLegacy(s.adminPinHash)) {
                val salt = AdminPin.newSalt()
                next = next.copy(adminPinSalt = salt, adminPinHash = AdminPin.hash(pin, salt))
                log("ADMIN_PIN_REHASHED")
            }
            // the guess stays counted until the cleared count is on the disk
            if (!savePinState(next)) return SettingsResult.PIN_LOCKED
            pinLockUntilMono = null
            return SettingsResult.SAVED
        }
        if (lockMs > 0) pinLockUntilMono = monotonic() + lockMs
        log("ADMIN_PIN_MISS count=$failures")
        return if (lockMs > 0) SettingsResult.PIN_LOCKED else SettingsResult.PIN_REQUIRED
    }

    /**
     * [next]'s PIN miss count and lock, saved straight to the store on this thread (not through [writer], whose queue may
     * hold seconds of bigger files), then the settings as usual; false when the store refused them (storage full or gone).
     */
    private fun savePinState(next: AppSettings): Boolean {
        try {
            store.saveAdminPin(space, next.adminPinFailures, next.adminPinLockedUntilMs)
        } catch (e: Exception) {
            log("ADMIN_PIN_NOT_SAVED error=${e::class.java.simpleName}")
            return false
        }
        writeSettings(next)
        return true
    }

    /**
     * Owner settings. The sending address and the admin PIN change only with the current PIN once a PIN
     * exists. Setting an address for the first time queues the whole ledger so the receiver can verify the
     * chain from its first entry.
     */
    fun saveSettings(new: AppSettings, newPin: String?, currentPin: String?): SettingsResult {
        val old = _settings.value
        val url = new.archiveWebhookUrl.trim()
        if (url.isNotEmpty() && !isAllowedEndpoint(url)) return SettingsResult.INVALID_URL
        val sensitive = newPin != null || url != old.archiveWebhookUrl
        if (sensitive) checkAdminPin(currentPin).takeIf { it != SettingsResult.SAVED }?.let { return it }
        val current = _settings.value
        // an address set for the first time gets the whole ledger: the mark of that is saved with the address
        val firstAddress = old.archiveWebhookUrl.isBlank() && url.isNotBlank()
        var next = new.copy(
            archiveWebhookUrl = url,
            adminPinHash = current.adminPinHash,
            adminPinSalt = current.adminPinSalt,
            adminPinFailures = current.adminPinFailures,
            adminPinLockedUntilMs = current.adminPinLockedUntilMs,
            lastMonthlyCloseYm = current.lastMonthlyCloseYm,
            vin = current.vin,
            familyNext = current.familyNext,
            fuelEconomyL100 = current.fuelEconomyL100,
            fuelEconomyAtMs = current.fuelEconomyAtMs,
            fuelPriceKrwPerLiter = new.fuelPriceKrwPerLiter.coerceIn(0, FUEL_PRICE_MAX),
            manualKmPerLiter = new.manualKmPerLiter.takeIf { FuelReimbursement.litersPer100Km(it) != null } ?: 0.0,
            ledgerBackfillThroughSeq = if (firstAddress) ledger.headSeq else current.ledgerBackfillThroughSeq,
            fiscalYearStartMonth = new.fiscalYearStartMonth.coerceIn(1, 12),
            defaultUserIndex = new.defaultUserIndex.coerceIn(0, (new.users.size - 1).coerceAtLeast(0)),
        )
        if (newPin != null) {
            require(newPin.length in 4..8 && newPin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
            val salt = AdminPin.newSalt()
            next = next.copy(adminPinSalt = salt, adminPinHash = AdminPin.hash(newPin, salt))
        }
        writeSettings(next)
        if (firstAddress && next.ledgerBackfillThroughSeq > 0) backfillLedger(next.ledgerBackfillThroughSeq)
        return SettingsResult.SAVED
    }

    /**
     * Queues the sending copies of the ledger up to [throughSeq] (its newest [BACKFILL_MAX] up to there) for an address
     * set for the first time, or the sample loaded with one. Thousands of copies: they are built on [Background.work]
     * from the entries as they are now and queued back on the core's thread.
     *
     * The mark saved with the address ([AppSettings.ledgerBackfillThroughSeq]) is cleared only once they are in the
     * outbox, so a start that finds it set queues them again ([enter]): the app closed meanwhile (a kill 2 s after the
     * sample loaded lost entries 1-977 on CI) leaves no hole at the receiver; a copy already queued is not queued twice.
     * While the mark is set the sender holds back this space's ledger entries, so an entry recorded meanwhile (queued at
     * once, as always) cannot reach the receiver before the older ones.
     */
    private fun backfillLedger(throughSeq: Long) {
        val into = space
        val chain = chainId
        val car = carJson()
        val entries = ledger.all.filter { it.seq <= throughSeq }.takeLast(BACKFILL_MAX)
        background.scope.launch {
            try {
                val items = withContext(background.work) {
                    entries.map { e -> Queued(ledgerKey(e, into, chain), LEDGER_KIND, ledgerPayload(e, into, chain, car), e.seq) }
                }
                // the ledger was replaced meanwhile (the sample loaded again or reset, e.g. by a setup that restarted the
                // app while these were built): they are copies of a chain that is gone, and the new one has its own mark
                if (space == into && chainId != chain) return@launch
                enqueueAll(items, into) // back on the core's thread, which owns the queue (see [enqueueAll])
                // a space left meanwhile keeps its mark: entering it again queues nothing twice and clears it then
                if (space == into && _settings.value.ledgerBackfillThroughSeq == throughSeq) {
                    writeSettings(_settings.value.copy(ledgerBackfillThroughSeq = 0))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the mark stays: the next start queues them, and this space's ledger entries wait until then
                log("BACKFILL_FAILED entries=${entries.size} error=${e::class.java.simpleName}")
            }
        }
    }

    /**
     * DEMO keeps labelled fictional data apart from the real logbook. Entering it needs the LIVE admin PIN
     * when one is set (a driver must not be able to hide real drives in the demo space); leaving it is
     * always allowed. Not while a trip is open: the drive would be split across both logbooks.
     *
     * Call it on the core's thread. The other space is read on the writing thread after what it still has to write (its
     * files parsed and its chain re-hashed: seconds on a loaded head unit) while the core's thread stays free; the switch
     * is made back there, and this returns once it is on the disk.
     */
    suspend fun setDemoMode(on: Boolean, pin: String?): SettingsResult = spaceLock.withLock {
        if (on == demoMode) return SettingsResult.SAVED
        if (logbook.openTrip != null) return SettingsResult.TRIP_IN_PROGRESS
        if (on) checkAdminPin(pin).takeIf { it != SettingsResult.SAVED }?.let { return it }
        val target = if (on) Space.DEMO else Space.LIVE
        val stored = readSpace(target)
        if (logbook.openTrip != null) {
            // a trip started while the other space was read: it is recorded in this one, which stays in use
            synchronized(outboxLock) { stopEntering(target) }
            return SettingsResult.TRIP_IN_PROGRESS
        }
        enter(stored)
        writer.setActiveSpace(target)
        writer.awaitWritten()
        log("SPACE ${target.name}")
        SettingsResult.SAVED
    }

    /** Space changes run one at a time: a second tap waits for the first to finish. */
    private val spaceLock = Mutex()

    /** [target] as stored, read on the writing thread after what it still has to write. */
    private suspend fun readSpace(target: Space): StoredSpace {
        val read = synchronized(outboxLock) {
            entering = target
            carried.clear()
            writer.read { StoredSpace.read(it, target) }
        }
        try {
            return read.await()
        } catch (e: Throwable) {
            synchronized(outboxLock) { stopEntering(target) }
            throw e
        }
    }

    /** No switch to [target] after all: changes to its stored queue are no longer carried. Under [outboxLock]. */
    private fun stopEntering(target: Space) {
        if (entering != target) return
        entering = null
        carried.clear()
    }

    /**
     * Replaces the DEMO space with the fictional sample history up to yesterday and the sample settings
     * (fictional company, users, places). Refused outside the demo space. The year of trips and its chain are made and
     * checked on [Background.io]; the space is then replaced in one step on the core's thread (what the old demo still had
     * to write is written, then wiped), and this returns once the sample is on the disk.
     */
    suspend fun loadSampleHistory(webhookUrl: String = ""): Int = spaceLock.withLock {
        check(demoMode) { "sample history only loads in the demo space" }
        val today = TripLogbook.localDate(clock())
        val sample = withContext(background.io) { sampleSpace(today, webhookUrl) }
        writer.wipe(Space.DEMO)
        writer.saveLedger(Space.DEMO, sample.data.chainId, sample.data.ledger)
        writer.saveTrips(Space.DEMO, sample.data.trips)
        writer.saveSettings(Space.DEMO, sample.data.settings)
        writer.awaitWritten()
        enter(sample)
        log("DEMO_READY trips=${sample.data.trips.size}")
        sample.data.trips.size
    }

    /** The sample year as the demo space holds it once loaded (see [loadSampleHistory]). */
    private fun sampleSpace(today: LocalDate, webhookUrl: String): StoredSpace {
        val (rows, sampleLedger) = SampleHistory.build(today)
        // the closing of past months is part of the sample, not something to resend now; an address comes with the mark
        // of the sending copies still to queue, which entering the space starts (see [backfillLedger])
        val settings = SampleHistory.settings(webhookUrl).copy(
            lastMonthlyCloseYm = YearMonth.from(today).minusMonths(1).toString(),
            ledgerBackfillThroughSeq = if (webhookUrl.isNotBlank()) sampleLedger.headSeq else 0,
        )
        val data = SpaceData(settings = settings, trips = rows, ledger = sampleLedger.all, chainId = newChainId())
        val logbook = TripLogbook(rows)
        val check = LedgerCheck(true, sampleLedger.size, Ledger.code(sampleLedger.all.lastOrNull()?.hash), emptyList())
        return StoredSpace(Space.DEMO, data, logbook, sampleLedger, 0, check)
    }

    /** Empties the demo space (refused outside it); returns once that is on the disk. */
    suspend fun resetDemo() = spaceLock.withLock {
        check(demoMode) { "only the demo space can be reset" }
        writer.wipe(Space.DEMO)
        enter(StoredSpace.of(Space.DEMO, SpaceData()))
        writer.awaitWritten()
        log("DEMO_RESET")
    }

    /**
     * Blocks until every save handed over so far is on the disk, at most [timeoutMs]; false when it was not. For the end of
     * the process (AutoBriefApp); never on the writing thread.
     */
    fun flushWrites(timeoutMs: Long): Boolean = writer.flush(timeoutMs)

    /** Runs [block] on the core's thread, in the core's own scope: it outlives the screen that started it. */
    fun launch(block: suspend AutoBriefCore.() -> Unit) {
        background.scope.launch { block() }
    }

    private fun writeSettings(new: AppSettings) {
        _settings.value = new
        writer.saveSettings(space, new)
    }

    // ---------------------------------------------------------------- summaries, tax, export

    fun summary(period: ClosedRange<LocalDate>): LogbookSummary = logbook.summarize(period)

    fun today(): LocalDate = TripLogbook.localDate(clock())

    fun currentBusinessYear(): ClosedRange<LocalDate> = TaxPeriods.businessYearOf(today(), _settings.value.fiscalYearStartMonth)

    /** The logbook ratio so far this business year applied to the owner's annual car cost. */
    fun taxBenefit(): TaxBenefit.Result? {
        val s = _settings.value
        val ratio = summary(currentBusinessYear()).businessUseRatio
        return TaxBenefit.compute(s.annualCarCostKrw, s.annualDepreciationKrw, ratio, s.taxRate.ratePctFor(currentBusinessYear().start))
    }

    // ---------------------------------------------------------------- fuel reimbursement (내 차 mode)

    /** The car's latest usable fuel economy: read in this process, else the one kept in the settings, the newer of the two. */
    fun fuelReading(): EconomyReading? {
        val s = _settings.value
        val stored = if (FuelReimbursement.validEconomy(s.fuelEconomyL100)) EconomyReading(s.fuelEconomyL100, s.fuelEconomyAtMs) else null
        return listOfNotNull(_energy.value.fuel, stored).maxByOrNull { it.atMs }
    }

    /** The economy a settlement uses: the car's own reading, else the owner's figure (연비 직접 입력), else none. */
    fun fuelEconomyInUse(): FuelReimbursement.Economy? = economyOf(_settings.value, fuelReading())

    /** The car reports an EV efficiency and no fuel economy: its settlement (전비, 원/kWh) is not in this version. */
    val electricOnly: Boolean get() = fuelReading() == null && _energy.value.evKmPerKwh != null

    /** [month]'s settlement in 내 차 mode; null for a company car. */
    fun fuelSettlement(month: YearMonth): FuelReimbursement.Month? = fuelSettlements(TaxPeriods.month(month)).firstOrNull()

    /** Every calendar month of [period] up to this one, settled with the economy and price in use now; empty for a company car. */
    fun fuelSettlements(period: ClosedRange<LocalDate>): List<FuelReimbursement.Month> =
        settleFuel(logbook.records, _settings.value, fuelEconomyInUse(), period, today())

    /** The form for [period] as of today: a business year still running is labelled as the total to today. */
    fun exportSheet(period: ClosedRange<LocalDate>, businessYear: Boolean): LogbookExport.Sheet =
        LogbookExport.build(logbook.records, _settings.value, period, _check.value.code, businessYear, exportDate = today())

    /** Workbooks are made one at a time: the report screen, the month close and Gleo share the export folder and the outbox. */
    private val workbookLock = Mutex()

    /** What a workbook is made of, taken at once on the core's thread: immutable values, so it can be built anywhere. */
    private class WorkbookInput(
        val space: Space,
        val chainId: String?,
        val settings: AppSettings,
        val records: List<TripRecord>,
        val entries: List<LedgerEntry>,
        val code: String,
        val car: JsonObject,
        val today: LocalDate,
        val ledgerSize: Int,
        val economy: FuelReimbursement.Economy?,
        val economyAtMs: Long?,
    )

    private fun workbookInput() =
        WorkbookInput(
            space, chainId, _settings.value, logbook.records, ledger.all, _check.value.code, carJson(), today(), ledger.size,
            fuelEconomyInUse(), fuelReading()?.atMs,
        )

    private class MadeWorkbook(
        val name: String,
        val xlsx: ByteArray,
        val csv: ByteArray,
        val rows: Int,
        val archive: Queued?,
        val fuel: List<FuelReimbursement.Month>,
    )

    /**
     * Writes the period workbook (form + ledger sheet) and queues it for the sending address when one is set. Call it on
     * the core's thread: the logbook is read there, the workbook is built on [Background.work] and its files are written
     * on [Background.io], so that thread stays free meanwhile; its sending copy is queued back on the core's thread.
     */
    suspend fun exportWorkbook(period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean = true): ExportResult =
        workbookLock.withLock { writeWorkbook(workbookInput(), period, businessYear, send) }

    /** The month close's workbook for [forSpace]; null when another space is in use by the time it is [forSpace]'s turn. */
    private suspend fun monthWorkbook(forSpace: Space, month: YearMonth): ExportResult? =
        workbookLock.withLock { if (space != forSpace) null else writeWorkbook(workbookInput(), TaxPeriods.month(month), false, send = true) }

    private suspend fun writeWorkbook(input: WorkbookInput, period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean): ExportResult {
        val made = withContext(background.work) { buildWorkbook(input, period, businessYear, send) }
        val savedTo = withContext(background.io) {
            files.write(input.space, made.name, made.xlsx).also { files.write(input.space, made.name.removeSuffix(".xlsx") + ".csv", made.csv) }
        }
        made.archive?.let { enqueueAll(listOf(it), input.space) } // back on the core's thread, which owns the queue
        log("EXPORT file=${made.name} rows=${made.rows} queued=${made.archive != null}")
        return ExportResult(made.name, savedTo, made.archive != null, made.rows, made.fuel)
    }

    private fun buildWorkbook(i: WorkbookInput, period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean): MadeWorkbook {
        val sheet = LogbookExport.build(i.records, i.settings, period, i.code, businessYear, exportDate = i.today)
        val table = LogbookExport.ledgerTable(i.entries, i.records, period, i.code)
        // 내 차 mode: the settlement sheet, month by month, and the same figures in the sending copy
        val fuel = settleFuel(i.records, i.settings, i.economy, period, i.today)
        val fuelTable = if (i.settings.carUse == CarUse.OWN) {
            LogbookExport.fuelTable(fuel, i.settings.fuelBasis, i.economy, i.economyAtMs, i.today)
        } else {
            null
        }
        val xlsx = LogbookExport.toXlsx(sheet, listOfNotNull(table, fuelTable))
        val name = "운행기록부_${period.start}_${period.endInclusive}.xlsx"
        val archive = if (send && i.settings.archiveWebhookUrl.isNotBlank()) {
            val payload = buildJsonObject {
                put("type", "logbook_archive")
                i.chainId?.let { put("chainId", it) }
                put("space", i.space.dir)
                put("car", i.car)
                put("fileName", name)
                put("periodStart", period.start.toString())
                put("periodEnd", period.endInclusive.toString())
                put("verificationCode", i.code)
                if (i.settings.carUse == CarUse.OWN) put("fuelReimbursement", fuelJson(fuel, i.settings.fuelBasis))
                put("xlsxBase64", Base64.getEncoder().encodeToString(xlsx))
            }
            Queued("archive-${i.space.dir}-${i.chainId?.let { "$it-" } ?: ""}$name-${i.ledgerSize}", "archive", payload.toString())
        } else {
            null
        }
        return MadeWorkbook(name, xlsx, LogbookExport.toCsv(sheet), sheet.rows.size, archive, fuel)
    }

    /**
     * 엑셀 만들기 / 지금 보내기 on the report screen: starts the workbook and returns at once; [workbook] says when it is
     * made (or why not). False while the previous one is still being made.
     */
    fun requestWorkbook(period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean): Boolean {
        if (_workbook.value is WorkbookRequest.Making) return false
        _workbook.value = WorkbookRequest.Making(send)
        background.scope.launch {
            _workbook.value = try {
                WorkbookRequest.Made(send, exportWorkbook(period, businessYear, send))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the file could not be written (storage full or gone): the screen says so and the buttons work again
                log("EXPORT_FAILED error=${e::class.java.simpleName}")
                WorkbookRequest.Failed(send, e::class.java.simpleName)
            }
        }
        return true
    }

    /** The report screen leaves the period whose workbook it showed: its message goes (one still being made stays). */
    fun dismissWorkbook() {
        if (_workbook.value !is WorkbookRequest.Making) _workbook.value = null
    }

    /** No month-close attempt before this time (epoch ms): set after a workbook could not be written. */
    private var monthCloseRetryAt = Long.MIN_VALUE

    /** A month close is running (its workbooks are made off the core's thread); set, read and cleared on that thread. */
    private var monthClosing = false

    /**
     * Once a month is over its workbook is written (and sent) once: every month from the last one closed up to the
     * previous month, in order. A month counts as closed only after its workbook was written; a failed write is tried
     * again a minute later. Nothing closes while a trip, or the place rules of a trip that just ended, is still open:
     * that row belongs in the month. The workbooks are made off the core's thread, one close at a time.
     */
    private fun closeMonthIfDue() {
        val now = clock()
        if (monthClosing || logbook.openTrip != null || pendingAuto != null || now < monthCloseRetryAt) return
        val first = logbook.records.firstOrNull() ?: return
        val previous = YearMonth.from(Instant.ofEpochMilli(now).atZone(KST)).minusMonths(1)
        val done = _settings.value.lastMonthlyCloseYm.takeIf { it.isNotBlank() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        val from = done?.plusMonths(1) ?: TripLogbook.yearMonth(first.startEpochMs)
        if (from.isAfter(previous)) return
        monthClosing = true
        val forSpace = space
        background.scope.launch {
            try {
                closeMonths(forSpace, from, previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                monthCloseRetryAt = clock() + MONTH_CLOSE_RETRY_MS
                log("MONTH_CLOSE_FAILED error=${e::class.java.simpleName}")
            } finally {
                monthClosing = false
            }
        }
    }

    /**
     * Closes [from]..[through] of [forSpace] in order, back on the core's thread after each workbook. It stops when
     * another space is in use: a month whose workbook was already written is marked by that logbook's next close (the
     * same file again, and the same delivery key while its ledger has not grown).
     */
    private suspend fun closeMonths(forSpace: Space, from: YearMonth, through: YearMonth) {
        var month = from
        while (!month.isAfter(through)) {
            if (space != forSpace) return
            val rows = logbook.recordsIn(TaxPeriods.month(month))
            if (rows.isNotEmpty()) {
                val result = try {
                    monthWorkbook(forSpace, month) ?: return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the file could not be written (storage full or gone): the month stays open for the retry
                    monthCloseRetryAt = clock() + MONTH_CLOSE_RETRY_MS
                    log("MONTH_CLOSE_FAILED ym=$month error=${e::class.java.simpleName}")
                    return
                }
                if (space != forSpace) return
                val sum = TripLogbook.summarize(rows)
                _notice.value = Notice(
                    "${month.monthValue}월 운행기록부를 만들었습니다: ${rows.size}건, 업무사용비율 ${sum.businessUseRatio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "-"}" +
                        fuelNotice(result.fuel) + if (result.queued) ", 보내기 주소로 전송 대기" else "",
                    result.fileName,
                )
            }
            writeSettings(_settings.value.copy(lastMonthlyCloseYm = month.toString()))
            month = month.plusMonths(1)
        }
    }

    // ---------------------------------------------------------------- Gleo function calls

    data class GleoReply(val status: String, val message: String, val data: JsonArray? = null)

    /** A Gleo function call, on the core's thread; SEND_LOGBOOK makes its workbook off it (see [exportWorkbook]). */
    suspend fun handleGleo(functionName: String, params: JsonObject): GleoReply = when (functionName) {
        GleoContract.LOG_TRIP_PURPOSE -> gleoLogPurpose(params)
        GleoContract.GET_LOGBOOK_STATUS -> gleoStatus(params)
        GleoContract.SEND_LOGBOOK -> gleoSend(params)
        else -> GleoReply("fail", "This function is not supported.")
    }

    private fun gleoLogPurpose(params: JsonObject): GleoReply {
        if (params.str("purpose")?.uppercase() == "FAMILY") return gleoFamily()
        val purpose = when (params.str("purpose")?.uppercase()) {
            "COMMUTE" -> TripPurpose.COMMUTE
            "BUSINESS" -> TripPurpose.BUSINESS
            "PERSONAL" -> TripPurpose.PERSONAL
            else -> null
        } ?: return GleoReply("asking", "Please say whether the trip was for commuting, business or personal use.")
        // voice may only label the last trip, only right after it ended, and only while it has no purpose:
        // corrections need the screen and a reason (a voice command cannot rewrite a recorded purpose)
        val trip = logbook.lastTrip()?.takeIf { clock() - it.endEpochMs <= VOICE_CLASSIFY_WINDOW_MS }
            ?: return GleoReply("fail", "There is no trip that ended in the last 30 minutes.")
        if (trip.purpose != TripPurpose.UNCLASSIFIED) {
            return GleoReply("fail", "The last trip already has a purpose. Please change it on the screen.")
        }
        val name = params.str("user_name")
        val user = name?.let { n -> _settings.value.users.firstOrNull { it.name == n } }
        if (name != null && user == null) return GleoReply("asking", "That person is not registered as a user of this car.")
        val memo = params.str("memo") ?: trip.memo
        val km = TripLogbook.round1(trip.distanceKm)
        if (classifyTrip(trip.id, purpose, user, memo, ClassifiedBy.VOICE) != ClassifyResult.OK) {
            return GleoReply("fail", "The trip could not be updated.")
        }
        return GleoReply(
            "success",
            "The last trip of ${String.format(Locale.ROOT, "%.0f", km)} km was logged as ${purpose.name.lowercase()} use.",
            JsonArray(listOf(buildJsonObject { put("distanceKm", km); put("purpose", purpose.label) })),
        )
    }

    /**
     * FAMILY turns on 가족 운행 for the drive under way, or parked for the next one. It never labels a drive that already
     * ended (its location was read): a drive that just ended with no purpose keeps waiting for one, and the reply says so.
     */
    private fun gleoFamily(): GleoReply {
        val waiting = logbook.openTrip == null &&
            logbook.lastTrip()?.let { clock() - it.endEpochMs <= VOICE_CLASSIFY_WINDOW_MS && it.purpose == TripPurpose.UNCLASSIFIED } == true
        return when (startFamilyDrive()) {
            FamilyResult.CONVERTED -> GleoReply("success", "Family drive on: no location is read for this drive.")
            FamilyResult.ARMED -> GleoReply(
                "success",
                "Family drive on: no location is read for the next drive." +
                    if (waiting) " The drive that just ended still needs a purpose." else "",
            )
        }
    }

    private fun gleoStatus(params: JsonObject): GleoReply {
        val year = params.str("period")?.uppercase() == "THIS_YEAR"
        val period = if (year) currentBusinessYear() else TaxPeriods.month(YearMonth.from(today()))
        val s = summary(period)
        if (s.rowCount == 0) return GleoReply("success", if (year) "No trips are logged this business year yet." else "No trips are logged this month yet.")
        val ratio = s.businessUseRatio?.let { String.format(Locale.ROOT, "%.1f", it * 100) } ?: "0"
        val pending = if (s.unclassifiedCount > 0) " ${s.unclassifiedCount} trips still need a purpose." else " Every trip has a purpose."
        val gaps = if (s.gapRowCount > 0) " ${s.gapRowCount} unlogged odometer gaps (${String.format(Locale.ROOT, "%.0f", s.gapKm)} km)." else ""
        // 내 차 mode: the fuel reimbursement of the same period (every month of it so far), or what it still needs
        val fuel = if (_settings.value.carUse == CarUse.OWN) fuelSettlements(period) else emptyList()
        val fuelKrw = fuel.takeIf { it.isNotEmpty() && it.all { m -> m.result.amountKrw != null } }?.sumOf { it.result.amountKrw!! }
        val fuelText = when {
            fuel.isEmpty() -> ""
            fuelKrw != null -> " Fuel reimbursement ${if (year) "this business year" else "this month"}: ${app.autobrief.car.ui.comma(fuelKrw)} won."
            fuel.any { it.result.missing == FuelReimbursement.Missing.ECONOMY } ->
                " The fuel reimbursement needs the car's fuel economy or one entered in the settings."
            else -> " The fuel reimbursement needs a fuel price in the settings."
        }
        return GleoReply(
            "success",
            "${if (year) "This business year" else "This month"}: ${s.rowCount} trips, ${String.format(Locale.ROOT, "%.0f", s.totalKm)} km, business use $ratio percent.$pending$gaps$fuelText",
            JsonArray(listOf(buildJsonObject {
                put("trips", s.rowCount)
                put("totalKm", s.totalKm)
                put("businessUsePct", ratio)
                put("unclassified", s.unclassifiedCount)
                if (s.gapRowCount > 0) {
                    put("gapCount", s.gapRowCount)
                    put("gapKm", s.gapKm)
                }
                fuelKrw?.let { put("fuelReimbursementKrw", it) }
            })),
        )
    }

    private var lastVoiceSendMs = Long.MIN_VALUE / 2

    private suspend fun gleoSend(params: JsonObject): GleoReply {
        val now = clock()
        if (now - lastVoiceSendMs < SEND_COOLDOWN_MS) return GleoReply("fail", "A logbook was just sent. Please try again in a minute.")
        lastVoiceSendMs = now
        val today = today()
        val (period, businessYear) = when (params.str("period")?.uppercase()) {
            "LAST_MONTH" -> TaxPeriods.month(YearMonth.from(today).minusMonths(1)) to false
            "BUSINESS_YEAR" -> currentBusinessYear() to true
            else -> TaxPeriods.month(YearMonth.from(today)) to false
        }
        if (logbook.recordsIn(period).isEmpty()) return GleoReply("fail", "There are no trips in that period.")
        val result = try {
            exportWorkbook(period, businessYear)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("EXPORT_FAILED error=${e::class.java.simpleName}")
            return GleoReply("fail", "The logbook could not be saved in the car.")
        }
        return GleoReply(
            "success",
            if (result.queued) "The logbook for ${period.start} to ${period.endInclusive} was saved in the car and is being sent to the registered address."
            else "The logbook for ${period.start} to ${period.endInclusive} was saved in the car. No sending address is registered.",
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    companion object {
        /** Builds a core on the calling thread from a [StoredSpace] already read off-thread. */
        fun fromStored(
            store: Store,
            stored: StoredSpace,
            files: FileSink,
            log: (String) -> Unit,
            detailLogs: Boolean,
            monotonic: () -> Long,
            background: Background,
            clock: () -> Long = System::currentTimeMillis,
        ): AutoBriefCore = AutoBriefCore(store, files, clock, log, detailLogs, monotonic, background, stored)

        /**
         * The app's core. The space is read on [Background.io] (its files parsed, the chain re-hashed: seconds on a loaded
         * head unit) and the core is made back on the calling thread, which becomes its thread. Made in
         * Application.onCreate on the main thread, a year of trips kept a loaded car from completing the app's start
         * (rehearsal 40475583018), and Android closed it.
         */
        suspend fun open(
            store: Store,
            files: FileSink,
            log: (String) -> Unit,
            detailLogs: Boolean,
            monotonic: () -> Long,
            background: Background,
            clock: () -> Long = System::currentTimeMillis,
        ): AutoBriefCore {
            val stored = withContext(background.io) { StoredSpace.read(store, store.activeSpace()) }
            return fromStored(store, stored, files, log, detailLogs, monotonic, background, clock)
        }

        const val APP_VERSION = "2.0.0"

        /** The thread that writes the logbook's files on the car (see [Background.storeThread]). */
        const val STORE_THREAD = "AutoBriefStore"
        const val VOICE_CLASSIFY_WINDOW_MS = 30 * 60_000L
        const val START_LOCATION_MAX_AGE_MS = 10 * 60_000L
        const val ARRIVAL_LOCATION_MAX_AGE_MS = 15_000L
        const val LOCATION_FRESH_MS = 60_000L

        /** Navigation's position is preferred while it is younger than this. */
        const val NAVI_PREFERRED_MS = 30_000L

        /** Navigation and GNSS farther apart than a place radius would name different places: GNSS wins (see position()). */
        const val NAVI_GNSS_AGREE_M = AutoClassifier.RADIUS_M

        /** A fix less precise than this (a network-only estimate) cannot tell places apart. */
        const val MAX_FIX_ACCURACY_M = 150f
        const val PLACE_LOCATION_MAX_AGE_MS = 2 * 60_000L
        const val AUTO_WAIT_MS = 12_000L
        const val OUTBOX_PENDING_MAX = 10_000
        const val OUTBOX_DELIVERED_KEEP = 200

        /** A workbook is given up after this many failed sends; ledger entries never are. */
        const val ARCHIVE_MAX_ATTEMPTS = 50
        const val BACKFILL_MAX = 5_000
        const val SEND_COOLDOWN_MS = 60_000L
        private const val LEDGER_KIND = "ledger"
        const val MONTH_CLOSE_RETRY_MS = 60_000L
        const val PIN_ATTEMPTS_BEFORE_LOCK = 5
        const val PIN_LOCK_MS = 5 * 60_000L

        /** The longest lock: 5 minutes doubled four times. */
        const val PIN_LOCK_MAX_MS = PIN_LOCK_MS * 16

        /** The Gleo voice assistant of Pleos Connect v2.1 (AmbientAiApp); it defines the signature permission below. */
        const val GLEO_PACKAGE = "ai.umos.ambientai"
        const val GLEO_SENDER_PERMISSION = "ai.umos.gleo.permission.APP_ACTION"
        private const val MEMO_MAX = 80

        /** The memo of a family trip's row (가족 운행). */
        const val FAMILY_MEMO = "가족 운행"

        /** The highest fuel price taken from the settings (KRW per litre). */
        const val FUEL_PRICE_MAX = 99_999

        /** The economy a settlement uses: the car's reading, else the owner's km/L, else none. */
        fun economyOf(s: AppSettings, reading: EconomyReading?): FuelReimbursement.Economy? =
            reading?.let { FuelReimbursement.Economy(it.litersPer100Km, FuelReimbursement.Source.VEHICLE) }
                ?: FuelReimbursement.litersPer100Km(s.manualKmPerLiter)?.let { FuelReimbursement.Economy(it, FuelReimbursement.Source.MANUAL) }

        /**
         * Each calendar month of [period] up to [today]'s, settled from [records] (the printed ⑨, plus ⑧ when the commute
         * is paid too) with [economy] and the settings' price. Empty for a company car. Pure: workbooks build it off the
         * core's thread from a snapshot.
         */
        fun settleFuel(
            records: List<TripRecord>,
            s: AppSettings,
            economy: FuelReimbursement.Economy?,
            period: ClosedRange<LocalDate>,
            today: LocalDate,
        ): List<FuelReimbursement.Month> {
            if (s.carUse != CarUse.OWN) return emptyList()
            val last = YearMonth.from(minOf(period.endInclusive, today))
            var month = YearMonth.from(period.start)
            val out = mutableListOf<FuelReimbursement.Month>()
            while (!month.isAfter(last)) {
                val range = TaxPeriods.month(month)
                val sum = TripLogbook.summarize(records.filter { TripLogbook.localDate(it.startEpochMs) in range })
                val km = if (s.fuelBasis == FuelBasis.WITH_COMMUTE) sum.businessUseKm else sum.businessKm
                out += FuelReimbursement.Month(month, economy?.source, FuelReimbursement.compute(km, economy?.litersPer100Km, s.fuelPriceKrwPerLiter))
                month = month.plusMonths(1)
            }
            return out
        }

        /** The sending copy's settlement block: the basis, every month and the total (null while a month has no amount). */
        fun fuelJson(months: List<FuelReimbursement.Month>, basis: FuelBasis): JsonObject = buildJsonObject {
            put("basis", basis.name)
            put("months", JsonArray(months.map { m ->
                buildJsonObject {
                    put("month", m.month.toString())
                    put("businessKm", m.result.businessKm)
                    put("litersPer100Km", m.result.litersPer100Km)
                    put("kmPerLiter", m.result.litersPer100Km?.let { TripLogbook.round1(FuelReimbursement.kmPerLiter(it)) })
                    put("economySource", m.economySource?.label)
                    put("priceKrwPerLiter", m.result.priceKrwPerLiter)
                    put("amountKrw", m.result.amountKrw)
                }
            }))
            put("totalKrw", months.takeIf { it.isNotEmpty() && it.all { m -> m.result.amountKrw != null } }?.sumOf { it.result.amountKrw!! })
        }

        /** The month close's words for its settlement: the amount, or that it could not be made; nothing for a company car. */
        fun fuelNotice(months: List<FuelReimbursement.Month>): String {
            if (months.isEmpty()) return ""
            val total = months.takeIf { it.all { m -> m.result.amountKrw != null } }?.sumOf { it.result.amountKrw!! }
            return if (total != null) ", 유류비 정산 ${app.autobrief.car.ui.comma(total)}원" else ", 유류비 정산은 연비나 유가가 없어 비워 두었습니다"
        }

        /** [payloadJson] with [vin] in its car block when the block has none; every other field stays as queued. */
        fun fillMissingVin(payloadJson: String, vin: String?): String {
            if (vin == null) return payloadJson
            // a payload that is not an object goes out as it is: the receiver refuses it, and the round must not stop here
            val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return payloadJson
            val car = payload["car"] as? JsonObject ?: return payloadJson
            if ((car["vin"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true) return payloadJson
            return JsonObject(payload + ("car" to JsonObject(car + ("vin" to JsonPrimitive(vin))))).toString()
        }

        /** 64 random bits as 16 hex digits: a fresh chain id. */
        fun newChainId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /** Memo text arrives from voice or the screen: one line, no control characters, bounded length. */
        fun sanitizeMemo(raw: String?): String =
            (raw ?: "").replace(Regex("[\\p{Cntrl}]"), " ").trim().take(MEMO_MAX)

        /** HTTPS anywhere; plain HTTP only to this machine or the emulator's host (local demo receiver). */
        fun isAllowedEndpoint(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val host = uri.host ?: return false
            return when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> host == "10.0.2.2" || host == "127.0.0.1" || host == "localhost"
                else -> false
            }
        }

        val json = Json { ignoreUnknownKeys = true }
    }
}
