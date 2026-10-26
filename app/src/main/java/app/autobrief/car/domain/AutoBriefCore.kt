package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.ClassifiedBy
import app.autobrief.car.model.Gear
import app.autobrief.car.model.GeoPoint
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
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64

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
) {
    enum class Space(val dir: String) { LIVE("live"), DEMO("demo") }

    data class SpaceData(
        val settings: AppSettings = AppSettings(),
        val trips: List<TripRecord> = emptyList(),
        val ledger: List<LedgerEntry> = emptyList(),
        val outbox: List<OutboxItem> = emptyList(),
    )

    interface Store {
        fun activeSpace(): Space
        fun setActiveSpace(space: Space)
        fun load(space: Space): SpaceData
        fun saveSettings(space: Space, settings: AppSettings)
        fun saveTrips(space: Space, trips: List<TripRecord>)
        fun saveLedger(space: Space, entries: List<LedgerEntry>)
        fun saveOutbox(space: Space, items: List<OutboxItem>)
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
    private var routeName: String? = null
    private var openStartPoint: GeoPoint? = null
    private var connectedLogged = false

    /** A closed trip waiting a few seconds for the arrival position before the place rules run. */
    private data class PendingAuto(val tripId: String, val start: GeoPoint?, val deadline: Long, val askedAt: Long)
    private var pendingAuto: PendingAuto? = null

    init {
        loadSpace(space)
    }

    private fun loadSpace(s: Space) {
        val d = store.load(s)
        space = s
        logbook = TripLogbook(d.trips)
        ledger = Ledger(d.ledger)
        _space.value = s
        _settings.value = d.settings
        _trips.value = logbook.records
        _outbox.value = d.outbox
        _open.value = null
        _lastClosedId.value = null
        _notice.value = null
        pendingAuto = null
        openStartPoint = null
        refreshCheck()
        // the new logbook needs the car's current state to detect the next departure correctly
        _vehicle.value.odometerKm?.let { logbook.onOdometer(it) }
        logbook.onGear(_vehicle.value.gear, clock())
    }

    fun setRestricted(value: Boolean) {
        _restricted.value = value
    }

    // ---------------------------------------------------------------- vehicle and navigation input

    fun onVehicle(e: VehicleEvent) {
        val now = clock()
        val s = _vehicle.value
        when (e) {
            is VehicleEvent.Connection -> {
                _vehicle.value = s.copy(sdkConnected = e.connected)
                if (e.connected && !connectedLogged) {
                    connectedLogged = true
                    log("VEHICLE_CONNECTED")
                }
            }
            is VehicleEvent.Odometer -> {
                _vehicle.value = s.copy(odometerKm = e.km)
                val added = logbook.onOdometer(e.km)
                if (added.isNotEmpty()) recordRows(added)
                if (logbook.openTrip != null && _open.value == null) openTripStarted()
            }
            is VehicleEvent.GearChanged -> onGear(e.gear, now)
            is VehicleEvent.Vin -> _vehicle.value = s.copy(vin = e.vin)
        }
    }

    fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.Ready -> _navi.update { it.copy(ready = e.ready) }
            is NaviEvent.RouteStarted -> {
                routeName = e.destinationName
                routeToll = e.tollFareKrw
                logbook.updateOpenRoute(routeToll, routeName)
            }
            is NaviEvent.Destination -> {
                routeName = e.name ?: routeName
                if (e.tollFareKrw != null) routeToll = e.tollFareKrw
                logbook.updateOpenRoute(routeToll, routeName)
            }
            is NaviEvent.Arrived -> Unit
            NaviEvent.RouteEnded -> {
                routeName = null
                routeToll = null
            }
            is NaviEvent.Location -> {
                val now = clock()
                _navi.update { it.copy(ready = true, location = e.point, locationEpochMs = now) }
                // debug builds: which registered place the car is at now, by label (never the coordinates);
                // the demo director waits for it before it departs or parks, so the place rules see the move
                if (detailLogs) log("POSITION place=${AutoClassifier.nearest(e.point, allPlaces())?.label ?: "-"}")
                pendingAuto?.let { p -> if (now > p.askedAt) resolvePendingAuto(e.point) }
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

    private fun freshLocation(now: Long, maxAgeMs: Long): GeoPoint? =
        _navi.value.takeIf { it.location != null && now - it.locationEpochMs <= maxAgeMs }?.location

    private fun onGear(gear: Gear, now: Long) {
        _vehicle.value = _vehicle.value.copy(gear = gear)
        val wasOpen = logbook.openTrip != null
        val added = logbook.onGear(gear, now, routeToll, routeName)
        if (added.isNotEmpty()) recordRows(added)
        if (!wasOpen && logbook.openTrip != null) openTripStarted()
        if (gear == Gear.PARK || gear == Gear.DRIVE) naviControl?.requestLocation()
    }

    private fun openTripStarted() {
        val open = logbook.openTrip ?: return
        val now = clock()
        openStartPoint = freshLocation(now, START_LOCATION_MAX_AGE_MS)
        val label = openStartPoint?.let { AutoClassifier.nearest(it, allPlaces()) }?.label
        _open.value = OpenTripView(open.startEpochMs, open.startOdometerKm, label)
        _lastClosedId.value = null
        log("TRIP_OPENED odo=${open.startOdometerKm}")
    }

    /** Rows just produced by the logbook: gap rows and closed trips go into the ledger as they are. */
    private fun recordRows(added: List<TripRecord>) {
        for (row in added) {
            if (row.purpose == TripPurpose.UNLOGGED_GAP) {
                appendLedger(LedgerKind.GAP, row, Ledger.tripBody(row, _vehicle.value.vin))
                log("GAP_RECORDED start=${row.startOdometerKm} end=${row.endOdometerKm}")
            } else {
                tripClosed(row)
            }
        }
        saveTrips()
    }

    private fun tripClosed(row: TripRecord) {
        val now = clock()
        _open.value = null
        _lastClosedId.value = row.id
        val start = openStartPoint
        openStartPoint = null
        val arrival = freshLocation(now, ARRIVAL_LOCATION_MAX_AGE_MS)
        if (_settings.value.autoClassify && arrival == null && (start != null || _navi.value.ready)) {
            // the position at the moment of parking is not in yet: write the row now, run the place rules shortly
            appendLedger(LedgerKind.TRIP, row, Ledger.tripBody(row, _vehicle.value.vin))
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
        if (writeTrip) appendLedger(LedgerKind.TRIP, placed, Ledger.tripBody(placed, _vehicle.value.vin))
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

    private fun appendLedger(kind: String, trip: TripRecord, body: JsonObject): LedgerEntry {
        val entry = ledger.append(kind, trip.id, body, clock())
        store.saveLedger(space, ledger.all)
        log("LEDGER seq=${entry.seq} kind=$kind hash=${entry.hash.take(8)}")
        if (_settings.value.archiveWebhookUrl.isNotBlank()) {
            enqueue("ledger-${space.dir}-${entry.seq}", "ledger", ledgerPayload(entry))
        }
        return entry
    }

    private fun carJson() = buildJsonObject {
        put("vin", _vehicle.value.vin)
        put("plate", _settings.value.plateNumber)
        put("model", _settings.value.vehicleModel)
        put("entity", _settings.value.entityName)
    }

    private fun ledgerPayload(e: LedgerEntry): String = buildJsonObject {
        put("type", "ledger_entry")
        put("app", "autobrief")
        put("appVersion", APP_VERSION)
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

    private fun saveTrips() {
        _trips.value = logbook.records
        store.saveTrips(space, logbook.records)
        refreshCheck()
    }

    private fun refreshCheck() {
        _check.value = Ledger.check(ledger.all, logbook.records)
    }

    private fun enqueue(key: String, kind: String, payload: String) = enqueueAll(listOf(Triple(key, kind, payload)))

    /** One update and ONE write for a whole batch: the outbox file is rewritten whole, so a write per item is quadratic. */
    private fun enqueueAll(items: List<Triple<String, String, String>>) {
        if (items.isEmpty()) return
        _outbox.update { list ->
            val known = list.mapTo(HashSet()) { it.key }
            val fresh = items.filter { known.add(it.first) }.map { (key, kind, payload) -> OutboxItem(key, kind, payload, clock()) }
            if (fresh.isEmpty()) list else prune(list + fresh)
        }
        store.saveOutbox(space, _outbox.value)
    }

    /** Bounded queue: delivered items are kept only as a short history, undelivered ones up to a hard cap. */
    private fun prune(list: List<OutboxItem>): List<OutboxItem> {
        val delivered = list.filter { it.deliveredEpochMs != null }.takeLast(OUTBOX_DELIVERED_KEEP)
        val pending = list.filter { it.deliveredEpochMs == null }.takeLast(OUTBOX_PENDING_MAX)
        return (delivered + pending).sortedBy { it.createdEpochMs }
    }

    /** One send result for an outbox item. */
    data class Delivery(val key: String, val delivered: Boolean, val error: String?)

    /** Called from the sender's I/O thread with a batch of results (one write); update{} keeps a concurrent enqueue. */
    fun markDeliveries(results: List<Delivery>) {
        if (results.isEmpty()) return
        val byKey = results.associateBy { it.key }
        _outbox.update { list ->
            prune(list.map { item ->
                val d = byKey[item.key] ?: return@map item
                item.copy(attempts = item.attempts + 1, deliveredEpochMs = if (d.delivered) clock() else null, lastError = d.error)
            })
        }
        store.saveOutbox(space, _outbox.value)
    }

    // ---------------------------------------------------------------- settings, PIN, demo space

    val adminPinSet: Boolean get() = _settings.value.adminPinHash.isNotEmpty()

    private fun pinMatches(pin: String?, s: AppSettings = _settings.value): Boolean =
        s.adminPinHash.isNotEmpty() && pin != null && Ledger.sha256(s.adminPinSalt + pin) == s.adminPinHash

    enum class SettingsResult { SAVED, PIN_REQUIRED, PIN_LOCKED, INVALID_URL, TRIP_IN_PROGRESS }

    /** Until when PIN entry is locked (epoch ms); 0 = not locked. */
    val pinLockedUntil: Long get() = _settings.value.adminPinLockedUntilMs.takeIf { it > clock() } ?: 0

    /**
     * The one place an admin PIN is checked. Every fifth wrong PIN in a row locks entry for 5 minutes, doubling up
     * to 80 minutes; the counters live in the settings file so a restart does not reset them.
     */
    private fun checkAdminPin(pin: String?): SettingsResult {
        val s = _settings.value
        if (s.adminPinHash.isEmpty()) return SettingsResult.SAVED
        val now = clock()
        if (now < s.adminPinLockedUntilMs) return SettingsResult.PIN_LOCKED
        if (pinMatches(pin, s)) {
            if (s.adminPinFailures != 0) writeSettings(s.copy(adminPinFailures = 0, adminPinLockedUntilMs = 0))
            return SettingsResult.SAVED
        }
        val failures = s.adminPinFailures + 1
        val rounds = failures / PIN_ATTEMPTS_BEFORE_LOCK
        val lockedUntil = if (failures % PIN_ATTEMPTS_BEFORE_LOCK == 0) now + PIN_LOCK_MS * (1L shl (rounds - 1).coerceAtMost(4)) else s.adminPinLockedUntilMs
        writeSettings(s.copy(adminPinFailures = failures, adminPinLockedUntilMs = lockedUntil))
        log("ADMIN_PIN_MISS count=$failures")
        return if (now < lockedUntil) SettingsResult.PIN_LOCKED else SettingsResult.PIN_REQUIRED
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
            fiscalYearStartMonth = new.fiscalYearStartMonth.coerceIn(1, 12),
            defaultUserIndex = new.defaultUserIndex.coerceIn(0, (new.users.size - 1).coerceAtLeast(0)),
        )
        if (newPin != null) {
            require(newPin.length in 4..8 && newPin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
            val salt = clock().toString(16)
            next = next.copy(adminPinSalt = salt, adminPinHash = Ledger.sha256(salt + newPin))
        }
        writeSettings(next)
        if (old.archiveWebhookUrl.isBlank() && url.isNotBlank()) backfillLedger()
        return SettingsResult.SAVED
    }

    private fun backfillLedger() {
        enqueueAll(ledger.all.takeLast(BACKFILL_MAX).map { e -> Triple("ledger-${space.dir}-${e.seq}", "ledger", ledgerPayload(e)) })
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
        store.saveLedger(Space.DEMO, sampleLedger.all)
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
        return TaxBenefit.compute(s.annualCarCostKrw, s.annualDepreciationKrw, ratio, s.taxRate.ratePct)
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
                put("car", carJson())
                put("fileName", name)
                put("periodStart", period.start.toString())
                put("periodEnd", period.endInclusive.toString())
                put("verificationCode", _check.value.code)
                put("xlsxBase64", Base64.getEncoder().encodeToString(xlsx))
            }
            enqueue("archive-${space.dir}-$name-${ledger.size}", "archive", payload.toString())
        }
        log("EXPORT file=$name rows=${sheet.rows.size} queued=$queued")
        return ExportResult(name, savedTo, queued, sheet.rows.size)
    }

    /** On the first event of a new month, the previous month's workbook is written (and sent) once. */
    private fun closeMonthIfDue() {
        val current = YearMonth.from(Instant.ofEpochMilli(clock()).atZone(KST))
        val previous = current.minusMonths(1)
        val s = _settings.value
        val done = s.lastMonthlyCloseYm.takeIf { it.isNotBlank() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        if (done != null && !done.isBefore(previous)) return
        if (logbook.records.isEmpty()) return
        val rows = logbook.recordsIn(TaxPeriods.month(previous))
        writeSettings(s.copy(lastMonthlyCloseYm = previous.toString()))
        if (rows.isEmpty()) return
        val result = exportWorkbook(TaxPeriods.month(previous), businessYear = false)
        val sum = TripLogbook.summarize(rows)
        _notice.value = Notice(
            "${previous.monthValue}월 운행기록부를 만들었습니다: ${rows.size}건, 업무사용비율 ${sum.businessUseRatio?.let { "%.1f%%".format(it * 100) } ?: "-"}" +
                if (result.queued) ", 보내기 주소로 전송 대기" else "",
            result.fileName,
        )
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
        const val PLACE_LOCATION_MAX_AGE_MS = 2 * 60_000L
        const val AUTO_WAIT_MS = 12_000L
        const val OUTBOX_PENDING_MAX = 10_000
        const val OUTBOX_DELIVERED_KEEP = 200
        const val BACKFILL_MAX = 5_000
        const val SEND_COOLDOWN_MS = 60_000L
        const val PIN_ATTEMPTS_BEFORE_LOCK = 5
        const val PIN_LOCK_MS = 5 * 60_000L

        /** The Gleo voice assistant of Pleos Connect v2.1 (AmbientAiApp); it defines the signature permission below. */
        const val GLEO_PACKAGE = "ai.umos.ambientai"
        const val GLEO_SENDER_PERMISSION = "ai.umos.gleo.permission.APP_ACTION"
        private const val MEMO_MAX = 80

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
