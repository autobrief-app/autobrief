package app.autobrief.car.data

import android.util.Log
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.domain.LedgerEntry
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.OpenTripRecord
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small JSON files in the app's private storage, one folder per data space (live/, demo/); each save
 * writes a temp file and renames it. Which space is active is kept in app_state.json.
 */
class JsonFiles(private val root: File) : AutoBriefCore.Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(root, "app_state.json")

    @Serializable
    private data class AppState(val space: Space = Space.LIVE)

    private fun dir(space: Space) = File(root, space.dir)

    override fun activeSpace(): Space = read(stateFile, AppState.serializer())?.space ?: Space.LIVE

    override fun setActiveSpace(space: Space) = write(stateFile, AppState.serializer(), AppState(space))

    override fun load(space: Space): AutoBriefCore.SpaceData {
        val d = dir(space)
        return AutoBriefCore.SpaceData(
            settings = read(File(d, SETTINGS), AppSettings.serializer()) ?: AppSettings(),
            trips = read(File(d, TRIPS), ListSerializer(TripRecord.serializer())) ?: emptyList(),
            ledger = read(File(d, LEDGER), ListSerializer(LedgerEntry.serializer())) ?: emptyList(),
            outbox = read(File(d, OUTBOX), ListSerializer(OutboxItem.serializer())) ?: emptyList(),
            openTrip = read(File(d, OPEN_TRIP), OpenTripRecord.serializer().nullable),
        )
    }

    override fun saveSettings(space: Space, settings: AppSettings) = write(File(dir(space), SETTINGS), AppSettings.serializer(), settings)
    override fun saveTrips(space: Space, trips: List<TripRecord>) = write(File(dir(space), TRIPS), ListSerializer(TripRecord.serializer()), trips)
    override fun saveLedger(space: Space, entries: List<LedgerEntry>) = write(File(dir(space), LEDGER), ListSerializer(LedgerEntry.serializer()), entries)
    override fun saveOutbox(space: Space, items: List<OutboxItem>) = write(File(dir(space), OUTBOX), ListSerializer(OutboxItem.serializer()), items)
    override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) = write(File(dir(space), OPEN_TRIP), OpenTripRecord.serializer().nullable, trip)

    override fun wipe(space: Space) {
        require(space == Space.DEMO) { "only the demo space can be wiped" }
        dir(space).deleteRecursively()
    }

    /** A damaged file is set aside (renamed) instead of crashing every start of the app. */
    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            val aside = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
            Log.w("AutoBriefFiles", "${file.name} unreadable (${it.message}); kept as ${aside.name}")
            file.renameTo(aside)
            null
        }
    }

    @Synchronized
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not save ${file.name}" }
        }
    }

    companion object {
        private const val SETTINGS = "settings.json"
        private const val TRIPS = "trips.json"
        private const val LEDGER = "ledger.json"
        private const val OUTBOX = "outbox.json"
        private const val OPEN_TRIP = "open_trip.json"
    }
}
