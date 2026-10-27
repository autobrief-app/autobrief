package app.autobrief.car

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * One core per process, shared by the screen, the monitor service and the Gleo receiver. The stored logbook is read off
 * the main thread when the process starts ([AutoBriefCore.open]): meanwhile the screen shows 불러오는 중, receivers wait
 * for the core, and the monitor keeps what the car reports until the core is there.
 */
open class AutoBriefApp : Application() {
    /** The core's thread (main) and where its slow work runs; receivers start their work on its scope. */
    val background = AutoBriefCore.Background.android()

    private val loaded = MutableStateFlow<AutoBriefCore?>(null)

    /** The core once the stored logbook is read; null while it is being read. */
    val coreState: StateFlow<AutoBriefCore?> = loaded.asStateFlow()

    /** The core, once the stored logbook is read. */
    suspend fun awaitCore(): AutoBriefCore = loaded.filterNotNull().first()

    /** Where the logbook is kept: the app's private files (a test passes a slower store). */
    protected open fun logbookStore(): AutoBriefCore.Store = JsonFiles(File(filesDir, "logbook"))

    override fun onCreate() {
        super.onCreate()
        val store = logbookStore()
        background.scope.launch {
            loaded.value = AutoBriefCore.open(
                store = store,
                files = { space, name, bytes ->
                    // app-specific external storage: no storage permission, removed with the app
                    val dir = File(getExternalFilesDir("exports") ?: File(filesDir, "exports"), space.dir).apply { mkdirs() }
                    File(dir, name).apply { writeBytes(bytes) }.absolutePath
                },
                log = { line -> Log.i(LOG_TAG, line) },
                detailLogs = BuildConfig.DEBUG,
                monotonic = SystemClock::elapsedRealtime,
                background = background,
            )
        }
    }

    companion object {
        /** Logcat tag the demo director and the end-to-end test read (`adb logcat -s AutoBriefLog`). */
        const val LOG_TAG = "AutoBriefLog"
    }
}

val Context.autoBrief: AutoBriefApp
    get() = applicationContext as AutoBriefApp

val Context.background: AutoBriefCore.Background
    get() = autoBrief.background
