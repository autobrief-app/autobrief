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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Small JSON files in the app's private storage, one folder per data space (live/, demo/). Which space is
 * active is kept in app_state.json.
 *
 * A save writes `<name>.tmp` and syncs it to the disk, moves the current file to `<name>.bak` (the last good
 * version), then moves the new one into place: at any moment one complete version exists under one of the
 * three names, and nothing is ever deleted. Reading takes the file, else the temp file (the app died between
 * the two moves), else the backup; a damaged file is set aside, never replaced by defaults while a good copy
 * exists.
 *
 * Each file has its own lock: the admin PIN's count (admin_pin.json, saved on the main thread before a guess is
 * checked) never waits for the store's thread rewriting a queue file of a megabyte.
 */
class JsonFiles(
    private val root: File,
    /** Called with a file's name once its lock is held, just before it is written (a test holds a write there). */
    private val writing: (String) -> Unit = {},
) : AutoBriefCore.Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(root, "app_state.json")

    @Serializable
    private data class AppState(val space: Space = Space.LIVE)

    /** ledger.json: the chain id and the entries in one file, so they are always written together. */
    @Serializable
    private data class LedgerFile(val chainId: String? = null, val entries: List<LedgerEntry> = emptyList())

    /** Up to 2.0.0 ledger.json was the bare list of entries (a chain without an id). */
    private object LedgerFileSerializer : JsonTransformingSerializer<LedgerFile>(LedgerFile.serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            if (element is JsonArray) JsonObject(mapOf("entries" to element)) else element
    }

    /** admin_pin.json: the PIN's miss count and lock, which win over the copies in settings.json (see [saveAdminPin]). */
    @Serializable
    private data class AdminPinFile(val failures: Int = 0, val lockedUntilMs: Long = 0)

    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockOf(file: File): Any = locks.computeIfAbsent(file.path) { Any() }

    private fun dir(space: Space) = File(root, space.dir)

    override fun activeSpace(): Space = read(stateFile, AppState.serializer())?.space ?: Space.LIVE

    override fun setActiveSpace(space: Space) = write(stateFile, AppState.serializer(), AppState(space))

    override fun load(space: Space): AutoBriefCore.SpaceData {
        val d = dir(space)
        val ledger = read(File(d, LEDGER), LedgerFileSerializer)
        val settings = read(File(d, SETTINGS), AppSettings.serializer()) ?: AppSettings()
        val pin = read(File(d, ADMIN_PIN), AdminPinFile.serializer())
        return AutoBriefCore.SpaceData(
            settings = pin?.let { settings.copy(adminPinFailures = it.failures, adminPinLockedUntilMs = it.lockedUntilMs) } ?: settings,
            trips = read(File(d, TRIPS), ListSerializer(TripRecord.serializer())) ?: emptyList(),
            ledger = ledger?.entries ?: emptyList(),
            chainId = ledger?.chainId,
            outbox = read(File(d, OUTBOX), ListSerializer(OutboxItem.serializer())) ?: emptyList(),
            openTrip = read(File(d, OPEN_TRIP), OpenTripRecord.serializer().nullable),
        )
    }

    override fun saveSettings(space: Space, settings: AppSettings) = write(File(dir(space), SETTINGS), AppSettings.serializer(), settings)
    override fun saveTrips(space: Space, trips: List<TripRecord>) = write(File(dir(space), TRIPS), ListSerializer(TripRecord.serializer()), trips)
    override fun saveLedger(space: Space, chainId: String?, entries: List<LedgerEntry>) =
        write(File(dir(space), LEDGER), LedgerFileSerializer, LedgerFile(chainId, entries))
    override fun saveOutbox(space: Space, items: List<OutboxItem>) = write(File(dir(space), OUTBOX), ListSerializer(OutboxItem.serializer()), items)
    override fun saveOpenTrip(space: Space, trip: OpenTripRecord?) = write(File(dir(space), OPEN_TRIP), OpenTripRecord.serializer().nullable, trip)
    override fun saveAdminPin(space: Space, failures: Int, lockedUntilMs: Long) =
        write(File(dir(space), ADMIN_PIN), AdminPinFile.serializer(), AdminPinFile(failures, lockedUntilMs))

    override fun wipe(space: Space) {
        require(space == Space.DEMO) { "only the demo space can be wiped" }
        val d = dir(space)
        // every file of the space is held while it goes: a save to one of them waits, then lands in the empty space
        fun under(files: List<String>) {
            if (files.isEmpty()) d.deleteRecursively() else synchronized(lockOf(File(d, files.first()))) { under(files.drop(1)) }
        }
        under(FILES)
    }

    private fun tmpOf(file: File) = File(file.parentFile, file.name + ".tmp")
    private fun backupOf(file: File) = File(file.parentFile, file.name + ".bak")

    /**
     * The file, else the temp file of a save the app died in, else the last good version. A damaged file is set aside
     * (renamed, for a look) instead of crashing every start of the app; null only when no copy is readable.
     */
    private fun <T> read(file: File, serializer: KSerializer<T>): T? = synchronized(lockOf(file)) {
        for (candidate in listOf(file, tmpOf(file), backupOf(file))) {
            if (!candidate.exists()) continue
            val value = runCatching { json.decodeFromString(serializer, candidate.readText()) }
            if (value.isSuccess) {
                if (candidate != file) Log.w(TAG, "${file.name} read from ${candidate.name}")
                return value.getOrThrow()
            }
            if (candidate == file) {
                val aside = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
                Log.w(TAG, "${file.name} unreadable (${value.exceptionOrNull()?.message}); kept as ${aside.name}")
                file.renameTo(aside)
            }
        }
        null
    }

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T): Unit = synchronized(lockOf(file)) {
        writing(file.name)
        val text = json.encodeToString(serializer, value) // a value that cannot be written fails here, before any file
        file.parentFile?.mkdirs()
        val tmp = tmpOf(file)
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync() // on the disk before any name points at it
        }
        val backup = backupOf(file)
        if (file.exists()) check(file.renameTo(backup)) { "could not keep ${file.name} as ${backup.name}" }
        if (!tmp.renameTo(file)) {
            backup.renameTo(file) // the good version goes back in place; the new one stays in .tmp
            error("could not save ${file.name}")
        }
    }

    companion object {
        private const val TAG = "AutoBriefFiles"
        private const val SETTINGS = "settings.json"
        private const val TRIPS = "trips.json"
        private const val LEDGER = "ledger.json"
        private const val OUTBOX = "outbox.json"
        private const val OPEN_TRIP = "open_trip.json"
        private const val ADMIN_PIN = "admin_pin.json"

        /** Every file of a space, in the order [wipe] takes their locks. */
        private val FILES = listOf(SETTINGS, TRIPS, LEDGER, OUTBOX, OPEN_TRIP, ADMIN_PIN)
    }
}
