package app.autobrief.car.domain

import app.autobrief.car.model.ActiveCustody
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
import app.autobrief.car.model.CustodyEndReason
import app.autobrief.car.model.CustodyReport
import app.autobrief.car.model.Gear
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.SeatPos
import app.autobrief.car.model.SeatState
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import app.autobrief.car.model.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The three modes on one vehicle state, free of Android types so every rule is unit-testable.
 * The Android layer feeds [onVehicle]/[onNavi]/[tick] and persists what [Persistence] is handed.
 */
class AutoBriefCore(
    initialSettings: AppSettings = AppSettings(),
    initialTrips: List<TripRecord> = emptyList(),
    initialOutbox: List<OutboxItem> = emptyList(),
    initialReports: List<CustodyReport> = emptyList(),
    initialActiveCustody: ActiveCustody? = null,
    private val persistence: Persistence = Persistence.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Persistence {
        fun saveTrips(trips: List<TripRecord>) {}
        fun saveSettings(settings: AppSettings) {}
        fun saveOutbox(items: List<OutboxItem>) {}
        fun saveReports(reports: List<CustodyReport>) {}
        fun saveActiveCustody(active: ActiveCustody?) {}

        companion object {
            val NONE = object : Persistence {}
        }
    }

    var control: VehicleControl? = null

    private val logbook = TripLogbook(initialTrips)
    private val sleepDetector = SleepCareDetector()

    private val _settings = MutableStateFlow(initialSettings)
    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _trips = MutableStateFlow(logbook.records)
    private val _sleep = MutableStateFlow(SleepCareState(emptySet(), emptyMap()))
    private val _custody = MutableStateFlow<CustodySession?>(null)
    private val _custodyTick = MutableStateFlow(0L)
    private val _reports = MutableStateFlow(initialReports)
    private val _outbox = MutableStateFlow(initialOutbox)
    private val _destination = MutableStateFlow<NaviEvent.Destination?>(null)
    private val _restricted = MutableStateFlow(false)
    private val _ambientRefused = MutableStateFlow(false)

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val vehicle: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
    val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()
    val sleep: StateFlow<SleepCareState> = _sleep.asStateFlow()
    val custody: StateFlow<CustodySession?> = _custody.asStateFlow()

    /** Bumps on every counted custody event so a screen observing it redraws. */
    val custodyTick: StateFlow<Long> = _custodyTick.asStateFlow()
    val reports: StateFlow<List<CustodyReport>> = _reports.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()
    val destination: StateFlow<NaviEvent.Destination?> = _destination.asStateFlow()

    /** CarUxRestrictions: true while the screen must stay in its glanceable driving layout. */
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    /** The vehicle refused the ambient-light request (outside the approved control scope). */
    val ambientRefused: StateFlow<Boolean> = _ambientRefused.asStateFlow()

    private var routeToll: Int? = null
    private var routeName: String? = null

    init {
        // a session that was running when the process died: report that recording stopped
        initialActiveCustody?.let { a ->
            val now = clock()
            val report = CustodyReport(
                sessionId = a.sessionId, startEpochMs = a.startEpochMs, endEpochMs = now,
                startOdometerKm = a.startOdometerKm, endOdometerKm = null, destinationLabel = a.destinationLabel,
                maxSpeedKph = 0.0, overspeedEpisodes = 0, harshAccelEvents = 0, harshBrakeEvents = 0,
                endReason = CustodyEndReason.INTERRUPTED, doorsLocked = null, driverConsented = true,
            )
            _reports.value = _reports.value + report
            persistence.saveReports(_reports.value)
            if (a.notifyFamily) enqueueFamily("${a.sessionId}-interrupted", "custody_end", CustodyMessages.end(report, timeText(now)))
            persistence.saveActiveCustody(null)
        }
    }

    fun setRestricted(value: Boolean) {
        _restricted.value = value
    }

    // ---------------------------------------------------------------- vehicle and navigation input

    fun onVehicle(e: VehicleEvent) {
        val now = clock()
        val s = _vehicle.value
        when (e) {
            is VehicleEvent.Connection -> _vehicle.value = s.copy(sdkConnected = e.connected)
            is VehicleEvent.Odometer -> {
                val added = logbook.onOdometer(e.km)
                _vehicle.value = s.copy(odometerKm = e.km)
                if (added.isNotEmpty()) saveTrips()
            }
            is VehicleEvent.GearChanged -> onGear(e.gear, now)
            is VehicleEvent.Speed -> {
                _vehicle.value = s.copy(speedKph = e.kph)
                _custody.value?.let {
                    it.onSpeed(e.kph, now)
                    _custodyTick.value = now
                }
            }
            is VehicleEvent.Vin -> _vehicle.value = s.copy(vin = e.vin)
            is VehicleEvent.SeatOccupancy -> updateSeat(e.seat) { it.copy(occupied = e.occupied) }
            is VehicleEvent.SeatBelt -> updateSeat(e.seat) { it.copy(belted = e.buckled) }
            is VehicleEvent.Backrest -> updateSeat(e.seat) { it.copy(backrestPosition = e.position) }
            is VehicleEvent.BackrestBounds -> _vehicle.value = s.copy(
                seats = SeatPos.values().associateWith { pos ->
                    val b = e.bounds[pos]
                    (s.seats[pos] ?: SeatState()).copy(backrestMin = b?.first, backrestMax = b?.last)
                },
            )
            is VehicleEvent.ReadingLight -> updateSeat(e.seat) { it.copy(readingLightOn = e.on) }
            is VehicleEvent.DoorsLocked -> {
                _vehicle.value = s.copy(doorsLocked = e.locked)
                if (e.locked == true) releaseHeldArrival(lockObserved = true)
            }
            is VehicleEvent.AmbientBounds -> _vehicle.value = s.copy(ambientBrightnessBounds = e.range)
        }
        if (e is VehicleEvent.SeatOccupancy || e is VehicleEvent.SeatBelt || e is VehicleEvent.Backrest ||
            e is VehicleEvent.ReadingLight || e is VehicleEvent.BackrestBounds
        ) {
            evaluateSleep(now)
        }
    }

    fun onNavi(e: NaviEvent) {
        when (e) {
            is NaviEvent.RouteStarted -> {
                routeName = e.destinationName
                routeToll = e.tollFareKrw
            }
            is NaviEvent.Destination -> {
                _destination.value = e
                routeName = e.name ?: routeName
                if (e.tollFareKrw != null) routeToll = e.tollFareKrw
            }
            is NaviEvent.Arrived -> _custody.value?.onArrivedSignal()
            is NaviEvent.SpeedLimit -> _custody.value?.onSpeedLimit(e.kph)
            NaviEvent.RouteEnded -> {
                routeName = null
                routeToll = null
                _destination.value = null
            }
        }
    }

    /** Periodic call (every few seconds) so time-based rules advance: the 60 s sleep hold, the lock wait. */
    fun tick() {
        val now = clock()
        evaluateSleep(now)
        heldArrival?.let { if (now >= it.deadline) releaseHeldArrival(lockObserved = false) }
    }

    private fun updateSeat(seat: SeatPos, change: (SeatState) -> SeatState) {
        val s = _vehicle.value
        _vehicle.value = s.copy(seats = s.seats + (seat to change(s.seats[seat] ?: SeatState())))
    }

    private fun onGear(gear: Gear, now: Long) {
        _vehicle.value = _vehicle.value.copy(gear = gear)
        val session = _custody.value
        val added = logbook.onGear(gear, now, routeToll, routeName, designatedDriver = session != null)
        if (added.isNotEmpty()) saveTrips()
        if (session != null) {
            val report = session.onGear(gear, now, _vehicle.value.odometerKm, _destination.value?.remainDistanceM, _vehicle.value.doorsLocked)
            _custodyTick.value = now
            if (report != null) finishCustody(report)
        }
    }

    private fun saveTrips() {
        _trips.value = logbook.records
        persistence.saveTrips(logbook.records)
    }

    // ---------------------------------------------------------------- Mode 1: logbook

    fun classifyTrip(id: String, purpose: TripPurpose, user: CarUser?, memo: String, source: String): TripRecord? {
        val updated = logbook.classify(id, purpose, user ?: defaultUser(), sanitizeMemo(memo), source) ?: return null
        saveTrips()
        return updated
    }

    fun summary(period: ClosedRange<LocalDate>): LogbookSummary = logbook.summarize(period)

    fun exportSheet(period: ClosedRange<LocalDate>, banner: String? = null, businessYear: Boolean = true): LogbookExport.Sheet =
        LogbookExport.build(logbook.records, _settings.value, period, banner, businessYear)

    /** Queues the period's logbook for the owner's archive webhook; false when no webhook is set. */
    fun queueArchive(period: ClosedRange<LocalDate>, fileName: String, xlsxBase64: String): Boolean {
        if (_settings.value.archiveWebhookUrl.isBlank()) return false
        val payload = buildJsonObject {
            put("type", "logbook_archive")
            put("fileName", fileName)
            put("periodStart", period.start.toString())
            put("periodEnd", period.endInclusive.toString())
            put("xlsxBase64", xlsxBase64)
        }
        enqueue("archive-$fileName-${clock()}", "archive", payload.toString())
        return true
    }

    private fun defaultUser(): CarUser? = _settings.value.users.getOrNull(_settings.value.defaultUserIndex)

    // ---------------------------------------------------------------- Mode 2: sleep care

    fun setSleepCare(enabled: Boolean) {
        writeSettings(_settings.value.copy(sleepCareEnabled = enabled))
        evaluateSleep(clock())
    }

    fun setDimAmbient(enabled: Boolean) = writeSettings(_settings.value.copy(dimAmbientOnSleep = enabled))

    private var ambientBefore: Int? = null
    private var ambientDimmed = false
    private var ambientPending = false

    private fun evaluateSleep(now: Long) {
        val state = sleepDetector.update(_vehicle.value.seats, now, _settings.value.sleepCareEnabled)
        _sleep.value = state
        val bounds = _vehicle.value.ambientBrightnessBounds ?: return
        val ctl = control ?: return
        val wantDim = state.sleeping.isNotEmpty() && _settings.value.dimAmbientOnSleep
        if (wantDim == ambientDimmed || ambientPending) return
        ambientPending = true
        if (wantDim) {
            // remember the level the cabin had, then dim; only a confirmed set counts
            ctl.readAmbientBrightness { before ->
                ambientBefore = before
                ctl.setAmbientBrightness(bounds.first + (bounds.last - bounds.first) / 5) { ok ->
                    ambientPending = false
                    ambientDimmed = ok
                    _ambientRefused.value = !ok
                }
            }
        } else {
            ctl.setAmbientBrightness(ambientBefore ?: bounds.last) { ok ->
                ambientPending = false
                if (ok) ambientDimmed = false
            }
        }
    }

    // ---------------------------------------------------------------- Mode 3: designated driver

    val custodyPinSet: Boolean get() = _settings.value.custodyPinHash.isNotEmpty()

    private fun pinMatches(pin: String?): Boolean {
        val st = _settings.value
        return st.custodyPinHash.isNotEmpty() && pin != null && sha256(st.custodyPinSalt + pin) == st.custodyPinHash
    }

    enum class SettingsResult { SAVED, LOCKED_DURING_CUSTODY, PIN_REQUIRED }

    /**
     * Owner settings from the settings screen. Refused while a designated-driver session runs, and a PIN,
     * notice addresses or demo mode can only change with the current PIN once one exists.
     */
    fun saveOwnerSettings(new: AppSettings, newPin: String?, currentPin: String?): SettingsResult {
        if (_custody.value != null) return SettingsResult.LOCKED_DURING_CUSTODY
        val old = _settings.value
        val sensitive = newPin != null || new.familyWebhookUrl != old.familyWebhookUrl ||
            new.archiveWebhookUrl != old.archiveWebhookUrl || new.demoMode != old.demoMode
        if (sensitive && custodyPinSet && !pinMatches(currentPin)) return SettingsResult.PIN_REQUIRED
        var next = new.copy(custodyPinHash = old.custodyPinHash, custodyPinSalt = old.custodyPinSalt)
        if (newPin != null) {
            require(newPin.length in 4..8 && newPin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
            val salt = clock().toString(16)
            next = next.copy(custodyPinSalt = salt, custodyPinHash = sha256(salt + newPin))
        }
        writeSettings(next)
        return SettingsResult.SAVED
    }

    /** Convenience for the first PIN (no PIN yet) or a change with the current one. */
    fun setCustodyPin(newPin: String, currentPin: String? = null): SettingsResult =
        saveOwnerSettings(_settings.value, newPin, currentPin)

    private var familyNotify = false
    private var wrongPinAttempts = 0
    private var pinLockedUntil = 0L

    /** Starts only with the driver's on-screen consent and an owner PIN already set; returns null otherwise. */
    fun startCustody(driverConsented: Boolean, notifyFamily: Boolean): CustodySession? {
        if (!driverConsented || !custodyPinSet) return null
        _custody.value?.let { return it }
        val now = clock()
        val dest = _destination.value
        val destLabel = (dest?.name ?: routeName)?.let(DisplayMasking::mask)
        val session = CustodySession("CUSTODY-$now", now, _vehicle.value.odometerKm, destLabel, driverConsented = true)
        _custody.value = session
        familyNotify = notifyFamily
        wrongPinAttempts = 0
        pinLockedUntil = 0L
        persistence.saveActiveCustody(ActiveCustody(session.sessionId, now, _vehicle.value.odometerKm, destLabel, notifyFamily))
        if (notifyFamily) {
            val eta = dest?.remainTimeS?.let { timeText(now + it * 1000L) }
            enqueueFamily("${session.sessionId}-departure", "custody_departure", CustodyMessages.departure(destLabel, eta))
        }
        return session
    }

    enum class EndResult { ENDED, WRONG_PIN, LOCKED, NOT_ACTIVE }

    /** Owner PIN check: fails closed without a PIN, and locks for a growing time after repeated misses. */
    fun endCustodyEarly(pin: String): EndResult {
        val session = _custody.value ?: return EndResult.NOT_ACTIVE
        val now = clock()
        if (now < pinLockedUntil) return EndResult.LOCKED
        if (!pinMatches(pin)) {
            wrongPinAttempts++
            if (wrongPinAttempts % PIN_ATTEMPTS_BEFORE_LOCK == 0) {
                val rounds = wrongPinAttempts / PIN_ATTEMPTS_BEFORE_LOCK
                pinLockedUntil = now + PIN_LOCK_MS * (1L shl (rounds - 1).coerceAtMost(4))
                // always tell the owner's contacts, whatever the departure-notice choice was
                enqueueFamily("${session.sessionId}-pin-$wrongPinAttempts", "custody_pin_alert",
                    "[오토브리프] 대리운전 모드 해제 PIN이 ${wrongPinAttempts}회 틀렸습니다.")
                return EndResult.LOCKED
            }
            return EndResult.WRONG_PIN
        }
        wrongPinAttempts = 0
        finishCustody(session.endEarly(now, _vehicle.value.odometerKm, _vehicle.value.doorsLocked))
        return EndResult.ENDED
    }

    private class HeldArrival(val report: CustodyReport, val deadline: Long, val notify: Boolean)

    /** An arrival waits for the doors to lock (or [LOCK_WAIT_MS]) so the message states what was observed. */
    private var heldArrival: HeldArrival? = null

    private fun finishCustody(report: CustodyReport) {
        _custody.value = null
        persistence.saveActiveCustody(null)
        _reports.value = _reports.value + report
        persistence.saveReports(_reports.value)
        // the lock is observed after parking even when no notice goes out, so the screen shows it too
        if (report.endReason == CustodyEndReason.ARRIVED && report.doorsLocked != true) {
            heldArrival = HeldArrival(report, clock() + LOCK_WAIT_MS, familyNotify)
            return
        }
        if (familyNotify) enqueueFamily("${report.sessionId}-end", "custody_end", CustodyMessages.end(report, timeText(report.endEpochMs)))
    }

    private fun releaseHeldArrival(lockObserved: Boolean) {
        val held = heldArrival ?: return
        heldArrival = null
        val report = held.report.copy(doorsLocked = lockObserved)
        _reports.update { list -> list.map { if (it.sessionId == report.sessionId) report else it } }
        persistence.saveReports(_reports.value)
        if (held.notify) {
            enqueueFamily("${report.sessionId}-end", "custody_end", CustodyMessages.end(report, timeText(report.endEpochMs), lockObserved))
        }
    }

    /** No family notice leaves the car in demo mode (demo signals are not real driving). */
    private fun enqueueFamily(key: String, kind: String, text: String) {
        if (_settings.value.familyWebhookUrl.isBlank() || _settings.value.demoMode) return
        val payload = buildJsonObject {
            put("type", kind)
            put("text", text)
        }
        enqueue(key, kind, payload.toString())
    }

    // ---------------------------------------------------------------- outbox

    private fun enqueue(key: String, kind: String, payload: String) {
        _outbox.update { list -> if (list.any { it.key == key }) list else list + OutboxItem(key, kind, payload, clock()) }
        persistence.saveOutbox(_outbox.value)
    }

    /** Called from the sender's I/O thread; update{} keeps a concurrent enqueue from being lost. */
    fun markDelivery(key: String, delivered: Boolean, error: String?) {
        _outbox.update { list ->
            list.map {
                if (it.key != key) it
                else it.copy(attempts = it.attempts + 1, deliveredEpochMs = if (delivered) clock() else null, lastError = error)
            }
        }
        persistence.saveOutbox(_outbox.value)
    }

    private fun writeSettings(new: AppSettings) {
        _settings.value = new
        persistence.saveSettings(new)
    }

    // ---------------------------------------------------------------- Gleo function calls

    data class GleoReply(val status: String, val message: String, val data: JsonArray? = null)

    /** Handles the two background functions; the third (START_DESIGNATED_DRIVER_MODE) opens the consent screen. */
    fun handleGleo(functionName: String, params: JsonObject): GleoReply = when (functionName) {
        GleoContract.LOG_TRIP_PURPOSE -> {
            val purpose = when (params.str("purpose")?.uppercase()) {
                "COMMUTE" -> TripPurpose.COMMUTE
                "BUSINESS" -> TripPurpose.BUSINESS
                "PERSONAL" -> TripPurpose.PERSONAL
                else -> null
            }
            // voice may only label the last trip, and only right after it ended
            val trip = logbook.lastTrip()?.takeIf {
                it.purpose == TripPurpose.UNCLASSIFIED && clock() - it.endEpochMs <= VOICE_CLASSIFY_WINDOW_MS
            }
            val name = params.str("user_name")
            val user = name?.let { n -> _settings.value.users.firstOrNull { it.name == n } }
            when {
                purpose == null -> GleoReply("asking", "Please say whether the trip was for commuting, business or personal use.")
                trip == null -> GleoReply("fail", "There is no just-finished trip waiting for a purpose.")
                name != null && user == null -> GleoReply("asking", "That person is not registered as a user of this car.")
                else -> {
                    val updated = classifyTrip(trip.id, purpose, user, params.str("memo") ?: "", source = "음성")!!
                    // the reply is a broadcast: only distance and purpose, never destinations or addresses
                    GleoReply(
                        "success",
                        "The last trip of ${"%.0f".format(updated.distanceKm)} km was logged as ${purpose.name.lowercase()} use.",
                        JsonArray(listOf(buildJsonObject {
                            put("distanceKm", TripLogbook.round1(updated.distanceKm))
                            put("purpose", purpose.label)
                        })),
                    )
                }
            }
        }
        GleoContract.SET_SLEEP_CARE -> {
            val enabled = params.bool("enabled")
            if (enabled == null) GleoReply("asking", "Please say whether to turn passenger sleep care on or off.")
            else {
                setSleepCare(enabled)
                GleoReply("success", if (enabled) "Passenger sleep care is on." else "Passenger sleep care is off.")
            }
        }
        else -> GleoReply("fail", "This function is not supported.")
    }

    /** AutoBrief's own Gleo replies use a beep instead of speech while a passenger sleeps. */
    fun gleoMode(): String = if (_sleep.value.quietMode) "beep" else "voice"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.lowercase()?.let {
        when (it) { "true" -> true; "false" -> false; else -> null }
    }

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val VOICE_CLASSIFY_WINDOW_MS = 30 * 60_000L
        const val PIN_ATTEMPTS_BEFORE_LOCK = 5
        const val PIN_LOCK_MS = 5 * 60_000L
        const val LOCK_WAIT_MS = 5 * 60_000L
        private const val MEMO_MAX = 80

        fun timeText(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(KST))

        /** Memo text arrives from voice or the screen: one line, no control characters, bounded length. */
        fun sanitizeMemo(raw: String?): String =
            (raw ?: "").replace(Regex("[\\p{Cntrl}]"), " ").trim().take(MEMO_MAX)

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
