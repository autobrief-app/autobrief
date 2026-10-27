package app.autobrief.car

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Blocks until the core's saves so far are on the disk, at most [timeoutMs]; true when there is nothing to write. */
    fun flushWrites(timeoutMs: Long = EXIT_FLUSH_MS): Boolean = loaded.value?.flushWrites(timeoutMs) ?: true

    override fun onCreate() {
        super.onCreate()
        flushOnExit()
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

    /**
     * Saves still being written reach the disk before the process ends, wherever Android says it is ending: the device
     * shutting down (ACTION_SHUTDOWN, to running apps), an exception nothing caught (before the process is killed), and
     * the runtime's own exit. A kill (low memory, force stop, ANR) gives no notice: then what the writer already wrote
     * stands, in an order that loading repairs (see StoreWriter).
     */
    private fun flushOnExit() {
        current = this
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pending = goAsync() // the flush waits for the writing thread, not on the main thread
                    Thread({ try { flushWrites() } finally { pending.finish() } }, "AutoBriefShutdownFlush").start()
                }
            },
            IntentFilter(Intent.ACTION_SHUTDOWN),
        )
        if (!processHooks.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread({ current?.flushWrites() }, "AutoBriefExitFlush"))
        val crashed = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            current?.flushWrites()
            crashed?.uncaughtException(thread, e)
        }
    }

    companion object {
        /** Logcat tag the demo director and the end-to-end test read (`adb logcat -s AutoBriefLog`). */
        const val LOG_TAG = "AutoBriefLog"

        /** How long the end of the process waits for saves still being written (Android allows a few seconds there). */
        const val EXIT_FLUSH_MS = 3_000L

        /** The process's app, for its exit hooks, which are installed once per process. */
        @Volatile private var current: AutoBriefApp? = null
        private val processHooks = AtomicBoolean(false)
    }
}

val Context.autoBrief: AutoBriefApp
    get() = applicationContext as AutoBriefApp

val Context.background: AutoBriefCore.Background
    get() = autoBrief.background
