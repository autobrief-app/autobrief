package app.autobrief.car

import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore.Space
import app.autobrief.car.model.AppSettings
import app.autobrief.car.model.TripPurpose
import app.autobrief.car.model.TripRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The files on the car's storage survive a torn write, a crash between renames and a save that fails. */
class JsonFilesTest {
    private val root: File = Files.createTempDirectory("autobrief-files").toFile()
    private val live = File(root, "live")
    private val row = TripRecord("TRIP-1", 1_000, 2_000, 100.0, 112.0, TripPurpose.UNCLASSIFIED, null, "")

    @After fun clean() {
        root.deleteRecursively()
    }

    /**
     * The admin PIN's count is saved on the main thread before a guess is checked: it must not wait for the store's thread
     * rewriting another file (a queue of a megabyte takes seconds on a loaded car), and it wins over the settings' copy.
     */
    @Test fun thePinCountIsSavedWhileAnotherFileIsBeingWrittenAndWinsOverTheSettingsCopy() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = JsonFiles(root) { name -> if (name == "outbox.json") { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        files.saveSettings(Space.LIVE, AppSettings(adminPinFailures = 0))
        val queue = thread { files.saveOutbox(Space.LIVE, emptyList()) }
        assertTrue("the queue write is held", held.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        files.saveAdminPin(Space.LIVE, 3, 0)
        val ms = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        queue.join(5_000)
        assertTrue("the PIN count waited $ms ms for the queue file", ms < 1_000)
        assertEquals("the count wins over the settings' copy", 3, files.load(Space.LIVE).settings.adminPinFailures)
    }

    @Test fun aDamagedFileIsReadFromTheLastGoodVersionNotFromDefaults() {
        val files = JsonFiles(root)
        files.saveSettings(Space.LIVE, AppSettings(entityName = "첫 번째"))
        files.saveSettings(Space.LIVE, AppSettings(entityName = "두 번째"))
        File(live, "settings.json").writeText("{\"entityName\":\"두 번") // torn by a power cut
        assertEquals("첫 번째", files.load(Space.LIVE).settings.entityName)
        assertTrue("the damaged file is kept for a look", live.list()!!.any { it.startsWith("settings.json.broken-") })
    }

    @Test fun dyingBetweenTheTwoRenamesLosesNothing() {
        val files = JsonFiles(root)
        files.saveTrips(Space.LIVE, listOf(row))
        files.saveTrips(Space.LIVE, listOf(row, row.copy(id = "TRIP-2", startEpochMs = 3_000, endEpochMs = 4_000)))
        // the next save got as far as: new version written and synced to .tmp, the current one moved to .bak
        val main = File(live, "trips.json")
        File(live, "trips.json.tmp").writeText(main.readText().replace("TRIP-2", "TRIP-3"))
        assertTrue(main.renameTo(File(live, "trips.json.bak")))
        assertEquals(listOf("TRIP-1", "TRIP-3"), files.load(Space.LIVE).trips.map { it.id })
    }

    @Test fun aSaveThatFailsLeavesTheGoodFileAndItsBackup() {
        val files = JsonFiles(root)
        files.saveTrips(Space.LIVE, listOf(row))
        files.saveTrips(Space.LIVE, listOf(row.copy(memo = "두 번째")))
        assertThrows(IllegalArgumentException::class.java) {
            files.saveTrips(Space.LIVE, listOf(row.copy(endOdometerKm = Double.NaN))) // the encoder refuses NaN
        }
        assertEquals("두 번째", files.load(Space.LIVE).trips.single().memo)
        File(live, "trips.json").writeText("[")
        assertEquals("the backup is still the version before", "", files.load(Space.LIVE).trips.single().memo)
    }

    @Test fun everySaveLeavesTheVersionBeforeAsTheBackup() {
        val files = JsonFiles(root)
        files.saveOutbox(Space.LIVE, emptyList())
        assertTrue(File(live, "outbox.json").exists())
        files.saveSettings(Space.LIVE, AppSettings(plateNumber = "11가 1111"))
        files.saveSettings(Space.LIVE, AppSettings(plateNumber = "22나 2222"))
        assertTrue(File(live, "settings.json.bak").readText().contains("11가 1111"))
        assertTrue(File(live, "settings.json").readText().contains("22나 2222"))
        assertTrue("no temp file left behind", !File(live, "settings.json.tmp").exists())
    }
}
