package app.autobrief.car

import android.content.Context
import android.os.Looper
import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.domain.AutoBriefCore.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * The app's start on a head unit that reads its storage slowly. In rehearsal 40475583018 the main thread spent 0.6 s
 * running and 18.8 s waiting for the CPU while Application.onCreate read and parsed the stored logbook (a year of the
 * demo sample), and Android closed the process for not completing its start. Here a year of the sample is on disk and
 * every read of a space takes 1.5 s longer: Application.onCreate, and each message it leaves for the main looper, must
 * stay under [MAIN_BUDGET_MS] while the logbook is read; the logbook then arrives whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SlowStorageApp::class)
class StartupTest {
    @get:Rule val stalePleosBinds = RefuseStalePleosBinds()

    private val app get() = RuntimeEnvironment.getApplication() as SlowStorageApp

    @Test fun theStartLeavesTheMainThreadFreeWhileTheLogbookIsRead() {
        assertTrue("Application.onCreate took ${app.onCreateMs} ms on the main thread", app.onCreateMs in 0 until MAIN_BUDGET_MS)
        assertNull("still being read when the main looper starts to run", app.coreState.value)
        val longest = runMainLooperUntil("the stored logbook to be read") { app.coreState.value != null }
        assertTrue("a main-thread message took $longest ms", longest < MAIN_BUDGET_MS)
        assertTrue("every read of a space ran off the main thread: ${app.readThreads}", app.readThreads.isNotEmpty() && "main" !in app.readThreads)
        val core = app.coreState.value!!
        assertEquals(Space.DEMO, core.activeSpace.value)
        assertEquals(app.sampleRows, core.trips.value.size)
        assertTrue(core.integrity.value.ok)
        assertEquals(app.sampleEntries, core.integrity.value.entryCount)
    }

    companion object {
        /** A main-thread message longer than this is a visible stall; Android's input limit is 5 s. */
        const val MAIN_BUDGET_MS = 200L
    }
}

/**
 * The app over a store that takes [READ_MS] longer to read a space, with a year of the demo sample on disk (put there
 * before the app starts, as on a car that ran the demo).
 */
class SlowStorageApp : AutoBriefApp() {
    var onCreateMs = -1L
        private set
    var sampleRows = 0
        private set
    var sampleEntries = 0
        private set

    /** The thread of every read of a space ("main" for the main thread). */
    val readThreads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AutoBriefCore(JsonFiles(File(filesDir, "logbook"))).apply {
            check(setDemoModeNow(true, null) == AutoBriefCore.SettingsResult.SAVED)
            sampleRows = loadSampleHistoryNow()
            sampleEntries = ledgerEntries.size
        }
        // the coroutine library's first use loads its classes (slow in a fresh Robolectric sandbox): not what is measured
        runBlocking { withContext(Dispatchers.IO) {} }
    }

    override fun logbookStore(): AutoBriefCore.Store {
        val files = super.logbookStore()
        return object : AutoBriefCore.Store by files {
            override fun load(space: Space): AutoBriefCore.SpaceData {
                readThreads += if (Looper.myLooper() == Looper.getMainLooper()) "main" else Thread.currentThread().name.substringBefore(" @")
                Thread.sleep(READ_MS)
                return files.load(space)
            }
        }
    }

    override fun onCreate() {
        val started = System.nanoTime()
        super.onCreate()
        onCreateMs = (System.nanoTime() - started) / 1_000_000
    }

    companion object {
        const val READ_MS = 1_500L
    }
}
