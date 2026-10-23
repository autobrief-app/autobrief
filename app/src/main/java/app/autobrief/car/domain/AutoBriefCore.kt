package app.autobrief.car.domain

import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CarUser
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
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
    private val persistence: Persistence = Persistence.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Persistence {
        fun saveTrips(trips: List<TripRecord>) {}
        fun saveSettings(settings: AppSettings) {}
        fun saveOutbox(items: List<OutboxItem>) {}
        fun saveReports(reports: List<CustodyReport>) {}

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

    private var routeToll: Int? = null
    private var routeName: String? = null
    private var ambientDimmed = false

    // ---------------------------------------------------------------- vehicle and navigation input

    fun onVehicle(e: VehicleEvent) {
        val now = clock()
        val s = _vehicle.value
        when (e) {
            is VehicleEvent.Connection -> _vehicle.value = s.copy(sdkConnected = e.connected)
            is VehicleEvent.Odometer -> {
                logbook.onOdometer(e.km)
                _vehicle.value = s.copy(odometerKm = e.km)
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
            is VehicleEvent.DoorsLocked -> _vehicle.value = s.copy(doorsLocked = e.locked)
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

    /** Periodic call (every few seconds) so time-based rules such as the 60 s sleep hold advance. */
    fun tick() = evaluateSleep(clock())

    private fun updateSeat(seat: SeatPos, change: (SeatState) -> SeatState) {
        val s = _vehicle.value
        _vehicle.value = s.copy(seats = s.seats + (seat to change(s.seats[seat] ?: SeatState())))
    }

    private fun onGear(gear: Gear, now: Long) {
        _vehicle.value = _vehicle.value.copy(gear = gear)
        val session = _custody.value
        val added = logbook.onGear(gear, now, routeToll, routeName, designatedDriver = session != null)
        if (added.isNotEmpty()) {
            _trips.value = logbook.records
            persistence.saveTrips(logbook.records)
        }
        if (session != null) {
            val report = session.onGear(gear, now, _vehicle.value.odometerKm, _destination.value?.remainDistanceM, _vehicle.value.doorsLocked)
            _custodyTick.value = now
            if (report != null) finishCustody(report)
        }
    }

    // ---------------------------------------------------------------- Mode 1: logbook

    fun classifyTrip(id: String, purpose: TripPurpose, user: CarUser?, memo: String, source: String): TripRecord? {
        val updated = logbook.classify(id, purpose, user ?: defaultUser(), memo, source) ?: return null
        _trips.value = logbook.records
        persistence.saveTrips(logbook.records)
        return updated
    }

    fun summary(period: ClosedRange<java.time.LocalDate>): LogbookSummary = logbook.summarize(period)

    fun exportSheet(period: ClosedRange<java.time.LocalDate>, banner: String? = null): LogbookExport.Sheet =
        LogbookExport.build(logbook.records, _settings.value, period, banner)

    /** Queues the period's logbook for the owner's archive webhook; false when no webhook is set. */
    fun queueArchive(period: ClosedRange<java.time.LocalDate>, fileName: String, xlsxBase64: String): Boolean {
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
        updateSettings(_settings.value.copy(sleepCareEnabled = enabled))
        evaluateSleep(clock())
    }

    private fun evaluateSleep(now: Long) {
        val state = sleepDetector.update(_vehicle.value.seats, now, _settings.value.sleepCareEnabled)
        _sleep.value = state
        val bounds = _vehicle.value.ambientBrightnessBounds
        val wantDim = state.sleeping.isNotEmpty() && _settings.value.dimAmbientOnSleep && bounds != null
        val ctl = control
        if (ctl != null && bounds != null && wantDim != ambientDimmed) {
            val target = if (wantDim) bounds.first + (bounds.last - bounds.first) / 5 else bounds.last
            if (ctl.setAmbientBrightness(target)) ambientDimmed = wantDim
        }
    }

    // ---------------------------------------------------------------- Mode 3: designated driver

    fun setCustodyPin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
        val salt = clock().toString(16)
        updateSettings(_settings.value.copy(custodyPinSalt = salt, custodyPinHash = sha256(salt + pin)))
    }

    val custodyPinSet: Boolean get() = _settings.value.custodyPinHash.isNotEmpty()

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
        if (notifyFamily) {
            val eta = dest?.remainTimeS?.let { TIME.format(Instant.ofEpochMilli(now + it * 1000L).atZone(KST)) }
            enqueueFamily("${session.sessionId}-departure", "custody_departure", CustodyMessages.departure(destLabel, eta))
        }
        return session
    }

    enum class EndResult { ENDED, WRONG_PIN, LOCKED, NOT_ACTIVE }

    private var wrongPinAttempts = 0
    private var pinLockedUntil = 0L

    /** Owner PIN check: fails closed without a PIN, and locks for a growing time after repeated misses. */
    fun endCustodyEarly(pin: String): EndResult {
        val session = _custody.value ?: return EndResult.NOT_ACTIVE
        val now = clock()
        if (now < pinLockedUntil) return EndResult.LOCKED
        val st = _settings.value
        if (st.custodyPinHash.isEmpty() || sha256(st.custodyPinSalt + pin) != st.custodyPinHash) {
            wrongPinAttempts++
            if (wrongPinAttempts % PIN_ATTEMPTS_BEFORE_LOCK == 0) {
                val rounds = wrongPinAttempts / PIN_ATTEMPTS_BEFORE_LOCK
                pinLockedUntil = now + PIN_LOCK_MS * (1L shl (rounds - 1).coerceAtMost(4))
                if (familyNotify) enqueueFamily("${session.sessionId}-pin-$wrongPinAttempts", "custody_pin_alert",
                    "[오토브리프] 대리운전 모드 해제 PIN이 ${wrongPinAttempts}회 틀렸습니다.")
                return EndResult.LOCKED
            }
            return EndResult.WRONG_PIN
        }
        wrongPinAttempts = 0
        finishCustody(session.endEarly(now, _vehicle.value.odometerKm, _vehicle.value.doorsLocked))
        return EndResult.ENDED
    }

    private var familyNotify = false

    private fun finishCustody(report: CustodyReport) {
        _custody.value = null
        _reports.value = _reports.value + report
        persistence.saveReports(_reports.value)
        if (familyNotify) {
            val time = TIME.format(Instant.ofEpochMilli(report.endEpochMs).atZone(KST))
            enqueueFamily("${report.sessionId}-end", "custody_end", CustodyMessages.end(report, time))
        }
    }

    private fun enqueueFamily(key: String, kind: String, text: String) {
        if (_settings.value.familyWebhookUrl.isBlank()) return
        val payload = buildJsonObject {
            put("type", kind)
            put("text", text)
        }
        enqueue(key, kind, payload.toString())
    }

    // ---------------------------------------------------------------- outbox

    private fun enqueue(key: String, kind: String, payload: String) {
        if (_outbox.value.any { it.key == key }) return
        _outbox.value = _outbox.value + OutboxItem(key, kind, payload, clock())
        persistence.saveOutbox(_outbox.value)
    }

    fun markDelivery(key: String, delivered: Boolean, error: String?) {
        _outbox.value = _outbox.value.map {
            if (it.key != key) it
            else it.copy(attempts = it.attempts + 1, deliveredEpochMs = if (delivered) clock() else null, lastError = error)
        }
        persistence.saveOutbox(_outbox.value)
    }

    fun updateSettings(new: AppSettings) {
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
            // voice may only label the trip that just ended, never older history
            val trip = logbook.latestUnclassified()?.takeIf { clock() - it.endEpochMs <= VOICE_CLASSIFY_WINDOW_MS }
            when {
                purpose == null -> GleoReply("asking", "Please say whether the trip was for commuting, business or personal use.")
                trip == null -> GleoReply("fail", "There is no recently finished trip waiting for a purpose.")
                else -> {
                    val user = params.str("user_name")?.let { n -> _settings.value.users.firstOrNull { it.name == n } }
                    val updated = classifyTrip(trip.id, purpose, user, sanitizeMemo(params.str("memo")), source = "음성")!!
                    GleoReply(
                        "success",
                        "The last trip of ${"%.0f".format(updated.distanceKm)} km was logged as ${purpose.name.lowercase()} use.",
                        // the reply is a broadcast: only distance and purpose, never destinations or addresses
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

    /** Gleo replies with a beep instead of speech while a passenger sleeps. */
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
        private const val MEMO_MAX = 80

        /** Memo text arrives from voice or the screen: one line, no control characters, bounded length. */
        fun sanitizeMemo(raw: String?): String =
            (raw ?: "").replace(Regex("[\\p{Cntrl}]"), " ").trim().take(MEMO_MAX)

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
