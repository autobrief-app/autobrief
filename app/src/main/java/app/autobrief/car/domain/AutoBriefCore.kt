package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.Place
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import app.autobrief.car.model.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import java.util.UUID

/**
 * The logbook on one vehicle state, free of Android types so every rule is unit-testable.
 * The Android layer feeds [onVehicle]/[onNavi]/[tick], provides storage ([Store]) and file output
 * ([FileSink]), and asks navigation for a fresh position through [naviControl].
 *
 * Data lives in two separate spaces: LIVE (the real logbook) and DEMO (labelled fictional history for
 * presentations). Switching never copies anything between them, so a demo can never touch real records.
 */
class AutoBriefCore(
    private val store: Store,
    private val files: FileSink = FileSink { _, name, _ -> name },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /**
     * Place labels in the log (`POSITION place=`, the place part of `reason=`). Only debug builds turn it
     * on: a release build matches positions and keeps nothing of them, not even the owner's own place
     * names in logcat.
     */
    private val detailLogs: Boolean = false,
    /**
     * Milliseconds that only move forward (SystemClock.elapsedRealtime on the car): the PIN lockout runs on it, so
     * setting the car's clock ahead does not end a lock. Tests that do not care pass nothing and share [clock].
     */
    private val monotonic: () -> Long = clock,
) {
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
    )

    data class OpenTripView(val startEpochMs: Long, val startOdometerKm: Double, val startPlace: String?)

    data class Notice(val text: String, val fileName: String?)

    data class ExportResult(val fileName: String, val savedTo: String, val queued: Boolean, val rows: Int)

    var naviControl: NaviControl? = null

    private var space: Space = store.activeSpace()
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

    private val _space = MutableStateFlow(space)
    private val _settings = MutableStateFlow(AppSettings())
    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    private val _check = MutableStateFlow(LedgerCheck(true, 0, "-", emptyList()))
    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val _restricted = MutableStateFlow(false)
    private val _navi = MutableStateFlow(NaviStatus())
    private val _open = MutableStateFlow<OpenTripView?>(null)
    private val _notice = MutableStateFlow<Notice?>(null)
    private val _lastClosedId = MutableStateFlow<String?>(null)

    val activeSpace: StateFlow<Space> = _space.asStateFlow()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val vehicle: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()
    val integrity: StateFlow<LedgerCheck> = _check.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()

    /** CarUxRestrictions: true while the screen must stay in its glanceable driving layout. */
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    val navi: StateFlow<NaviStatus> = _navi.asStateFlow()
    val openTrip: StateFlow<OpenTripView?> = _open.asStateFlow()
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** The trip that just closed (for the "what was this drive?" card); cleared once it is handled. */
    val lastClosedId: StateFlow<String?> = _lastClosedId.asStateFlow()

    val demoMode: Boolean get() = space == Space.DEMO

    private var routeToll: Int? = null
    private var openStartPoint: GeoPoint? = null

    /** The open trip's start place label: after a restart only the label is known, and it stands for the place. */
    private var openStartLabel: String? = null
    private var openTripSaved = false
    private var connectedLogged = false

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
        loadSpace(space)
        updateRestricted()
    }

    private fun loadSpace(s: Space) {
        val d = store.load(s)
        synchronized(outboxLock) {
            space = s
            _settings.value = d.settings
            _outbox.value = d.outbox
        }
        // the ledger is written before the trips file: rows the app died before storing come back from the chain
        val (rows, rebuilt) = Ledger.repair(d.trips, d.ledger)
        if (rebuilt > 0) {
            store.saveTrips(s, rows)
            log("TRIPS_REBUILT rows=$rebuilt")
        }
        logbook = TripLogbook(rows)
        ledger = Ledger(d.ledger)
        chainId = d.chainId
        _space.value = s
        _trips.value = logbook.records
        _open.value = null
        _lastClosedId.value = null
        _notice.value = null
        pendingAuto = null
        openStartPoint = null
        openStartLabel = null
        openTripSaved = d.openTrip != null
        pinLockUntilMono = null
        pinLockRestored = false
        d.openTrip?.let(::resumeOpenTrip)
        refreshCheck()
        // the new logbook needs the car's current state to detect the next departure correctly
        _vehicle.value.odometerKm?.let { logbook.seedOdometer(it) }
        logbook.onGear(_vehicle.value.gear, clock())
    }

    /** The trip that was open when the app stopped goes on (it closes at the next P); one already written is dropped. */
    private fun resumeOpenTrip(t: OpenTripRecord) {
        if (logbook.resume(TripLogbook.OpenTrip(t.startEpochMs, t.startOdometerKm, t.tollEstimateKrw))) {
            _open.value = OpenTripView(t.startEpochMs, t.startOdometerKm, t.startPlace)
            openStartLabel = t.startPlace
            log("TRIP_RESUMED odo=${t.startOdometerKm}")
        } else {
            saveOpenTrip(null)
        }
    }

    private fun saveOpenTrip(t: OpenTripRecord?) {
        store.saveOpenTrip(space, t)
        openTripSaved = t != null
    }

    /** The open trip as saved: its start values, the toll estimate known so far and the start place label. */
    private fun saveOpenTripNow() {
        val o = logbook.openTrip ?: return
        saveOpenTrip(OpenTripRecord(o.startEpochMs, o.startOdometerKm, o.tollEstimateKrw, openStartLabel))
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
        }
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
            // only the toll estimate is kept; the destination's name (where the driver goes) is not stored anywhere
            is NaviEvent.RouteStarted -> {
                routeToll = e.tollFareKrw
                updateOpenRoute()
            }
            is NaviEvent.Destination -> {
                if (e.tollFareKrw != null) routeToll = e.tollFareKrw
                updateOpenRoute()
            }
            is NaviEvent.Arrived -> Unit
            NaviEvent.RouteEnded -> routeToll = null
            is NaviEvent.Location -> {
                val now = clock()
                _navi.update { it.copy(ready = true, location = e.point, locationEpochMs = now) }
                logPosition(now)
                pendingAuto?.let { p -> if (now > p.askedAt) resolvePendingAuto(freshLocation(now, LOCATION_FRESH_MS) ?: e.point) }
            }
            is NaviEvent.Bookmarks -> _navi.update { it.copy(ready = true, bookmarks = e.places) }
        }
    }

    /** Periodic call (every few seconds): pending place rules time out, the month closes. */
    fun tick() {
        val now = clock()
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
        val p = fix.point
        if (!p.lat.isFinite() || !p.lon.isFinite() || p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return
        if (p.lat == 0.0 && p.lon == 0.0) return
        if (fix.accuracyM != null && !(fix.accuracyM <= MAX_FIX_ACCURACY_M)) return
        val now = clock()
        val taken = minOf(fix.epochMs, now)
        if (gnssFix?.let { it.epochMs > taken } == true) return // an older fix than the one held (two providers)
        gnssFix = Fix(p, taken, gnss = true)
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
            if (openTripSaved) saveOpenTrip(null)
        }
        if (gear == Gear.PARK || gear == Gear.DRIVE) naviControl?.requestLocation()
    }

    /** The last trip's card stays until it is handled: a gear shuffle that writes no row must not hide it. */
    private fun openTripStarted() {
        val open = logbook.openTrip ?: return
        val now = clock()
        openStartPoint = freshLocation(now, START_LOCATION_MAX_AGE_MS)
        val label = openStartPoint?.let { AutoClassifier.nearest(it, allPlaces()) }?.label
        openStartLabel = label
        _open.value = OpenTripView(open.startEpochMs, open.startOdometerKm, label)
        saveOpenTripNow()
        log("TRIP_OPENED odo=${open.startOdometerKm}")
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
        val now = clock()
        _open.value = null
        _lastClosedId.value = row.id
        val start = openStartPoint ?: openStartLabel?.let { label -> allPlaces().firstOrNull { it.label == label }?.point }
        openStartPoint = null
        openStartLabel = null
        val arrival = freshLocation(now, ARRIVAL_LOCATION_MAX_AGE_MS)
        // positions may come from navigation or from the car's own GNSS: either one can still deliver the arrival
        if (_settings.value.autoClassify && arrival == null && (start != null || _navi.value.ready || gnssFix != null)) {
            // the position at the moment of parking is not in yet: write the row now, run the place rules shortly
            appendLedger(LedgerKind.TRIP, row, Ledger.tripBody(row, currentVin()))
            pendingAuto = PendingAuto(row.id, start, now + AUTO_WAIT_MS, now)
            naviControl?.requestLocation()
            logClosed(row)
            return
        }
        applyPlaces(row, start, arrival, writeTrip = true)
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
        val result = AutoClassifier.classify(start, end, allPlaces())
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
        saveTrips()
        closeMonthIfDue()
    }

    private fun logClosed(r: TripRecord) {
        log(
            "TRIP_CLOSED id=${r.id} start=${r.startOdometerKm} end=${r.endOdometerKm} km=${TripLogbook.round1(r.distanceKm)} " +
                "purpose=${r.purpose.name} by=${r.classifiedBy ?: "-"} reason=${logReason(r.autoReason)}",
        )
    }

    /** The automatic rule's reason names places ("집→회사"): only debug builds log it, release logs `hidden`. */
    private fun logReason(reason: String?): String = when {
        reason == null -> "-"
        detailLogs -> reason
        else -> "hidden"
    }

    // ---------------------------------------------------------------- places

    /** Navigation bookmarks (HOME/OFFICE) first, then the places registered in the app. */
    fun allPlaces(): List<Place> =
        _navi.value.bookmarks.map {
            Place(if (it.kind == PlaceKind.HOME) "집" else "회사", it.kind, it.point, fromNavigation = true)
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
        store.saveLedger(space, chainId, ledger.all)
        log("LEDGER seq=${entry.seq} kind=$kind hash=${entry.hash.take(8)}")
        if (_settings.value.archiveWebhookUrl.isNotBlank()) {
            enqueue(Queued(ledgerKey(entry), "ledger", ledgerPayload(entry), entry.seq))
        }
        return entry
    }

    /** One delivery key per entry of one chain; a ledger from before chain ids keeps its old keys. */
    private fun ledgerKey(e: LedgerEntry): String =
        chainId?.let { "ledger-${space.dir}-$it-${e.seq}" } ?: "ledger-${space.dir}-${e.seq}"

    /** The car block of every payload, as the car is known when the item is queued (see [wireBody]). */
    private fun carJson() = buildJsonObject {
        put("vin", currentVin())
        put("plate", _settings.value.plateNumber)
        put("model", _settings.value.vehicleModel)
        put("entity", _settings.value.entityName)
    }

    private fun ledgerPayload(e: LedgerEntry): String = buildJsonObject {
        put("type", "ledger_entry")
        put("app", "autobrief")
        put("appVersion", APP_VERSION)
        chainId?.let { put("chainId", it) }
        // the receiver starts a new demo chain silently; a new live chain for a known car is a warning there
        put("space", space.dir)
        put("car", carJson())
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
        store.saveTrips(space, logbook.records)
        refreshCheck()
    }

    private fun refreshCheck() {
        _check.value = Ledger.check(ledger.all, logbook.records)
    }

    private class Queued(val key: String, val kind: String, val payload: String, val seq: Long? = null)

    private fun enqueue(item: Queued) = enqueueAll(listOf(item))

    /** One update and ONE write for a whole batch: the outbox file is rewritten whole, so a write per item is quadratic. */
    private fun enqueueAll(items: List<Queued>) {
        if (items.isEmpty()) return
        synchronized(outboxLock) {
            val list = _outbox.value
            val known = list.mapTo(HashSet()) { it.key }
            val fresh = items.filter { known.add(it.key) }.map { OutboxItem(it.key, it.kind, it.payload, clock(), seq = it.seq) }
            if (fresh.isEmpty()) return
            _outbox.value = prune(list + fresh)
            store.saveOutbox(space, _outbox.value)
        }
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
        OutboxSnapshot(space, _settings.value.archiveWebhookUrl, _outbox.value, currentVin())
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
                if (next.kind != "ledger" && next.deliveredEpochMs == null && next.attempts >= ARCHIVE_MAX_ATTEMPTS) {
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
                store.saveOutbox(space, _outbox.value)
            } else {
                store.saveOutbox(space, apply(store.load(space).outbox))
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
     * PIN in a row locks entry for 5 minutes, doubling up to 80 minutes; the counters live in the settings file so a
     * restart does not reset them. A PIN stored by an older version is re-hashed on its next correct entry.
     */
    private fun checkAdminPin(pin: String?): SettingsResult {
        val s = _settings.value
        if (s.adminPinHash.isEmpty()) return SettingsResult.SAVED
        if (pinLockRemainingMs(s) > 0) return SettingsResult.PIN_LOCKED
        if (pin.isNullOrBlank()) return SettingsResult.PIN_REQUIRED
        if (AdminPin.matches(pin, s.adminPinSalt, s.adminPinHash)) {
            var next = s.copy(adminPinFailures = 0, adminPinLockedUntilMs = 0)
            if (AdminPin.isLegacy(s.adminPinHash)) {
                val salt = AdminPin.newSalt()
                next = next.copy(adminPinSalt = salt, adminPinHash = AdminPin.hash(pin, salt))
                log("ADMIN_PIN_REHASHED")
            }
            if (next != s) writeSettings(next)
            pinLockUntilMono = null
            return SettingsResult.SAVED
        }
        val failures = s.adminPinFailures + 1
        val lockMs = if (failures % PIN_ATTEMPTS_BEFORE_LOCK == 0) pinLockLengthMs(failures) else 0L
        if (lockMs > 0) pinLockUntilMono = monotonic() + lockMs
        writeSettings(s.copy(adminPinFailures = failures, adminPinLockedUntilMs = if (lockMs > 0) clock() + lockMs else s.adminPinLockedUntilMs))
        log("ADMIN_PIN_MISS count=$failures")
        return if (lockMs > 0) SettingsResult.PIN_LOCKED else SettingsResult.PIN_REQUIRED
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
        var next = new.copy(
            archiveWebhookUrl = url,
            adminPinHash = current.adminPinHash,
            adminPinSalt = current.adminPinSalt,
            adminPinFailures = current.adminPinFailures,
            adminPinLockedUntilMs = current.adminPinLockedUntilMs,
            lastMonthlyCloseYm = current.lastMonthlyCloseYm,
            vin = current.vin,
            fiscalYearStartMonth = new.fiscalYearStartMonth.coerceIn(1, 12),
            defaultUserIndex = new.defaultUserIndex.coerceIn(0, (new.users.size - 1).coerceAtLeast(0)),
        )
        if (newPin != null) {
            require(newPin.length in 4..8 && newPin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
            val salt = AdminPin.newSalt()
            next = next.copy(adminPinSalt = salt, adminPinHash = AdminPin.hash(newPin, salt))
        }
        writeSettings(next)
        if (old.archiveWebhookUrl.isBlank() && url.isNotBlank()) backfillLedger()
        return SettingsResult.SAVED
    }

    private fun backfillLedger() {
        enqueueAll(ledger.all.takeLast(BACKFILL_MAX).map { e -> Queued(ledgerKey(e), "ledger", ledgerPayload(e), e.seq) })
    }

    /**
     * DEMO keeps labelled fictional data apart from the real logbook. Entering it needs the LIVE admin PIN
     * when one is set (a driver must not be able to hide real drives in the demo space); leaving it is
     * always allowed. Not while a trip is open: the drive would be split across both logbooks.
     */
    fun setDemoMode(on: Boolean, pin: String?): SettingsResult {
        if (on == demoMode) return SettingsResult.SAVED
        if (logbook.openTrip != null) return SettingsResult.TRIP_IN_PROGRESS
        if (on) checkAdminPin(pin).takeIf { it != SettingsResult.SAVED }?.let { return it }
        val target = if (on) Space.DEMO else Space.LIVE
        store.setActiveSpace(target)
        loadSpace(target)
        log("SPACE ${target.name}")
        return SettingsResult.SAVED
    }

    /**
     * Replaces the DEMO space with the fictional sample history up to yesterday and the sample settings
     * (fictional company, users, places). Refused outside the demo space.
     */
    fun loadSampleHistory(webhookUrl: String = ""): Int {
        check(demoMode) { "sample history only loads in the demo space" }
        val today = TripLogbook.localDate(clock())
        val (rows, sampleLedger) = SampleHistory.build(today)
        store.wipe(Space.DEMO)
        store.saveSettings(Space.DEMO, SampleHistory.settings(webhookUrl))
        store.saveTrips(Space.DEMO, rows)
        store.saveLedger(Space.DEMO, newChainId(), sampleLedger.all)
        // the closing of past months is part of the sample, not something to resend now
        store.saveSettings(Space.DEMO, SampleHistory.settings(webhookUrl).copy(lastMonthlyCloseYm = YearMonth.from(today).minusMonths(1).toString()))
        loadSpace(Space.DEMO)
        if (webhookUrl.isNotBlank()) backfillLedger()
        log("DEMO_READY trips=${rows.size}")
        return rows.size
    }

    fun resetDemo() {
        check(demoMode) { "only the demo space can be reset" }
        store.wipe(Space.DEMO)
        loadSpace(Space.DEMO)
        log("DEMO_RESET")
    }

    private fun writeSettings(new: AppSettings) {
        _settings.value = new
        store.saveSettings(space, new)
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

    fun exportSheet(period: ClosedRange<LocalDate>, businessYear: Boolean): LogbookExport.Sheet =
        LogbookExport.build(logbook.records, _settings.value, period, _check.value.code, businessYear)

    /** Writes the period workbook (form + ledger sheet) and queues it for the sending address when one is set. */
    fun exportWorkbook(period: ClosedRange<LocalDate>, businessYear: Boolean, send: Boolean = true): ExportResult {
        val sheet = exportSheet(period, businessYear)
        val table = LogbookExport.ledgerTable(ledger.all, logbook.records, period, _check.value.code)
        val xlsx = LogbookExport.toXlsx(sheet, listOf(table))
        val name = "운행기록부_${period.start}_${period.endInclusive}.xlsx"
        val savedTo = files.write(space, name, xlsx)
        files.write(space, name.removeSuffix(".xlsx") + ".csv", LogbookExport.toCsv(sheet))
        val queued = send && _settings.value.archiveWebhookUrl.isNotBlank()
        if (queued) {
            val payload = buildJsonObject {
                put("type", "logbook_archive")
                chainId?.let { put("chainId", it) }
                put("space", space.dir)
                put("car", carJson())
                put("fileName", name)
                put("periodStart", period.start.toString())
                put("periodEnd", period.endInclusive.toString())
                put("verificationCode", _check.value.code)
                put("xlsxBase64", Base64.getEncoder().encodeToString(xlsx))
            }
            enqueue(Queued("archive-${space.dir}-${chainId?.let { "$it-" } ?: ""}$name-${ledger.size}", "archive", payload.toString()))
        }
        log("EXPORT file=$name rows=${sheet.rows.size} queued=$queued")
        return ExportResult(name, savedTo, queued, sheet.rows.size)
    }

    /** No month-close attempt before this time (epoch ms): set after a workbook could not be written. */
    private var monthCloseRetryAt = Long.MIN_VALUE

    /**
     * Once a month is over its workbook is written (and sent) once: every month from the last one closed up to the
     * previous month, in order. A month counts as closed only after its workbook was written; a failed write is tried
     * again a minute later. Nothing closes while a trip, or the place rules of a trip that just ended, is still open:
     * that row belongs in the month.
     */
    private fun closeMonthIfDue() {
        val now = clock()
        if (logbook.openTrip != null || pendingAuto != null || now < monthCloseRetryAt) return
        val first = logbook.records.firstOrNull() ?: return
        val previous = YearMonth.from(Instant.ofEpochMilli(now).atZone(KST)).minusMonths(1)
        val done = _settings.value.lastMonthlyCloseYm.takeIf { it.isNotBlank() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        var month = done?.plusMonths(1) ?: TripLogbook.yearMonth(first.startEpochMs)
        while (!month.isAfter(previous)) {
            val rows = logbook.recordsIn(TaxPeriods.month(month))
            if (rows.isNotEmpty()) {
                val result = try {
                    exportWorkbook(TaxPeriods.month(month), businessYear = false)
                } catch (e: Exception) {
                    // the file could not be written (storage full or gone): the month stays open for the retry
                    monthCloseRetryAt = now + MONTH_CLOSE_RETRY_MS
                    log("MONTH_CLOSE_FAILED ym=$month error=${e::class.java.simpleName}")
                    return
                }
                val sum = TripLogbook.summarize(rows)
                _notice.value = Notice(
                    "${month.monthValue}월 운행기록부를 만들었습니다: ${rows.size}건, 업무사용비율 ${sum.businessUseRatio?.let { "%.1f%%".format(it * 100) } ?: "-"}" +
                        if (result.queued) ", 보내기 주소로 전송 대기" else "",
                    result.fileName,
                )
            }
            writeSettings(_settings.value.copy(lastMonthlyCloseYm = month.toString()))
            month = month.plusMonths(1)
        }
    }

    // ---------------------------------------------------------------- Gleo function calls

    data class GleoReply(val status: String, val message: String, val data: JsonArray? = null)

    fun handleGleo(functionName: String, params: JsonObject): GleoReply = when (functionName) {
        GleoContract.LOG_TRIP_PURPOSE -> gleoLogPurpose(params)
        GleoContract.GET_LOGBOOK_STATUS -> gleoStatus(params)
        GleoContract.SEND_LOGBOOK -> gleoSend(params)
        else -> GleoReply("fail", "This function is not supported.")
    }

    private fun gleoLogPurpose(params: JsonObject): GleoReply {
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
            "The last trip of ${"%.0f".format(km)} km was logged as ${purpose.name.lowercase()} use.",
            JsonArray(listOf(buildJsonObject { put("distanceKm", km); put("purpose", purpose.label) })),
        )
    }

    private fun gleoStatus(params: JsonObject): GleoReply {
        val year = params.str("period")?.uppercase() == "THIS_YEAR"
        val period = if (year) currentBusinessYear() else TaxPeriods.month(YearMonth.from(today()))
        val s = summary(period)
        if (s.rowCount == 0) return GleoReply("success", if (year) "No trips are logged this business year yet." else "No trips are logged this month yet.")
        val ratio = s.businessUseRatio?.let { "%.1f".format(it * 100) } ?: "0"
        val pending = if (s.unclassifiedCount > 0) " ${s.unclassifiedCount} trips still need a purpose." else " Every trip has a purpose."
        return GleoReply(
            "success",
            "${if (year) "This business year" else "This month"}: ${s.rowCount} trips, ${"%.0f".format(s.totalKm)} km, business use $ratio percent.$pending",
            JsonArray(listOf(buildJsonObject {
                put("trips", s.rowCount)
                put("totalKm", s.totalKm)
                put("businessUsePct", ratio)
                put("unclassified", s.unclassifiedCount)
            })),
        )
    }

    private var lastVoiceSendMs = Long.MIN_VALUE / 2

    private fun gleoSend(params: JsonObject): GleoReply {
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
        val result = exportWorkbook(period, businessYear)
        return GleoReply(
            "success",
            if (result.queued) "The logbook for ${period.start} to ${period.endInclusive} was saved in the car and is being sent to the registered address."
            else "The logbook for ${period.start} to ${period.endInclusive} was saved in the car. No sending address is registered.",
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    companion object {
        const val APP_VERSION = "2.0.0"
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
        const val MONTH_CLOSE_RETRY_MS = 60_000L
        const val PIN_ATTEMPTS_BEFORE_LOCK = 5
        const val PIN_LOCK_MS = 5 * 60_000L

        /** The longest lock: 5 minutes doubled four times. */
        const val PIN_LOCK_MAX_MS = PIN_LOCK_MS * 16

        /** The Gleo voice assistant of Pleos Connect v2.1 (AmbientAiApp); it defines the signature permission below. */
        const val GLEO_PACKAGE = "ai.umos.ambientai"
        const val GLEO_SENDER_PERMISSION = "ai.umos.gleo.permission.APP_ACTION"
        private const val MEMO_MAX = 80

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
