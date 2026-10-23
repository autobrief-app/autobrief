package app.autobrief.car.data

import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.CustodyReport
import app.autobrief.car.model.OutboxItem
import app.autobrief.car.model.TripRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON files in the app's private storage; each save writes a temp file and renames it. */
class JsonFiles(private val dir: File) : AutoBriefCore.Persistence {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val tripsFile = File(dir, "trips.json")
    private val settingsFile = File(dir, "settings.json")
    private val outboxFile = File(dir, "outbox.json")
    private val reportsFile = File(dir, "custody_reports.json")

    fun loadTrips(): List<TripRecord> = read(tripsFile, ListSerializer(TripRecord.serializer())) ?: emptyList()
    fun loadSettings(): AppSettings = read(settingsFile, AppSettings.serializer()) ?: AppSettings()
    fun loadOutbox(): List<OutboxItem> = read(outboxFile, ListSerializer(OutboxItem.serializer())) ?: emptyList()
    fun loadReports(): List<CustodyReport> = read(reportsFile, ListSerializer(CustodyReport.serializer())) ?: emptyList()

    override fun saveTrips(trips: List<TripRecord>) = write(tripsFile, ListSerializer(TripRecord.serializer()), trips)
    override fun saveSettings(settings: AppSettings) = write(settingsFile, AppSettings.serializer(), settings)
    override fun saveOutbox(items: List<OutboxItem>) = write(outboxFile, ListSerializer(OutboxItem.serializer()), items)
    override fun saveReports(reports: List<CustodyReport>) = write(reportsFile, ListSerializer(CustodyReport.serializer()), reports)

    private fun <T> read(file: File, serializer: KSerializer<T>): T? =
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else null

    @Synchronized
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not save ${file.name}" }
        }
    }
}
