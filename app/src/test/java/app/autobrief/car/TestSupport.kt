package app.autobrief.car

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
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.PlaceKind
import app.autobrief.car.model.TripRecord
import java.time.LocalDate
import java.time.ZonedDateTime

/** In-memory storage with the same two spaces as the device files. */
class MemoryStore(var active: Space = Space.LIVE) : AutoBriefCore.Store {
    val data = mutableMapOf(Space.LIVE to AutoBriefCore.SpaceData(), Space.DEMO to AutoBriefCore.SpaceData())
    override fun activeSpace() = active
    override fun setActiveSpace(space: Space) { active = space }
    override fun load(space: Space) = data.getValue(space)
    override fun saveSettings(space: Space, settings: AppSettings) { data[space] = data.getValue(space).copy(settings = settings) }
    override fun saveTrips(space: Space, trips: List<TripRecord>) { data[space] = data.getValue(space).copy(trips = trips) }
    override fun saveLedger(space: Space, entries: List<LedgerEntry>) { data[space] = data.getValue(space).copy(ledger = entries) }
    /** How many times the outbox was written (each write is a whole-file rewrite on the device). */
    var outboxSaves = 0
    override fun saveOutbox(space: Space, items: List<OutboxItem>) { outboxSaves++; data[space] = data.getValue(space).copy(outbox = items) }
    override fun wipe(space: Space) { data[space] = AutoBriefCore.SpaceData() }
}

/** A core on a controllable clock, with the log lines and written files captured. */
class Harness(
    settings: AppSettings = AppSettings(),
    startAt: Long = at(LocalDate.of(2026, 10, 23), 8, 0),
) {
    var now = startAt
    val store = MemoryStore().also { it.saveSettings(Space.LIVE, settings) }
    val logs = mutableListOf<String>()
    val files = linkedMapOf<String, ByteArray>()
    var locationRequests = 0
    val core = AutoBriefCore(store, { space, name, bytes -> files["${space.dir}/$name"] = bytes; name }, { now }, { logs += it })
        .also { it.naviControl = AutoBriefCore.NaviControl { locationRequests++ } }

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
