package app.autobrief.car

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore
import app.autobrief.car.vehicle.CarServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
    private var storeRef: AutoBriefCore.Store? = null
    private var initFuture: CompletableFuture<LoadedInit>? = null

    private class LoadedInit(val stored: AutoBriefCore.StoredSpace, val restricted: Boolean?)

    /** The core once the stored logbook is read; null while it is being read. */
    val coreState: StateFlow<AutoBriefCore?> = loaded.asStateFlow()

    private var startedScreens = 0
    private val screenStarted = MutableStateFlow(false)

    /**
     * A screen of the app is started (MainActivity between onStart and onStop): the monitor's status pill over other apps
     * hides then.
     */
    val appVisible: StateFlow<Boolean> = screenStarted.asStateFlow()

    /** The core, once the stored logbook is read. */
    suspend fun awaitCore(): AutoBriefCore = loaded.filterNotNull().first()

    /** Where the logbook is kept: the app's private files (a test passes a slower store). */
    protected open fun logbookStore(): AutoBriefCore.Store = JsonFiles(File(filesDir, "logbook"))

    /** Blocks until the core's saves so far are on the disk, at most [timeoutMs]; true when there is nothing to write. */
    fun flushWrites(timeoutMs: Long = EXIT_FLUSH_MS): Boolean = loaded.value?.flushWrites(timeoutMs) ?: true

    override fun onCreate() {
        super.onCreate()
        flushOnExit()
        watchScreens()
        val store = logbookStore()
        storeRef = store
        val future = CompletableFuture<LoadedInit>()
        initFuture = future
        background.scope.launch {
            val init = withContext(background.io) {
                try {
                    LoadedInit(AutoBriefCore.StoredSpace.read(store), CarServices.queryRestricted(this@AutoBriefApp))
                        .also { future.complete(it) }
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                    throw t
                }
            }
            installCore(store, init)
        }
    }

    /**
     * When the background read of the stored logbook has already finished (or finishes within [waitMs], as on an empty or
     * small store), builds the core on the main thread before the first Compose frame so Frame 1 draws [AutoBriefScreen]
     * directly instead of [Loading] followed by a full-screen replacement during the window-focus handshake.
     */
    fun claimLoadedCoreIfReady(waitMs: Long = 500L) {
        if (loaded.value != null) return
        val store = storeRef ?: return
        val future = initFuture ?: return
        val init = try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            return
        }
        installCore(store, init)
    }

    /** Counts the started activities (a counter and a flag: nothing is done at the start of the process). */
    private fun watchScreens() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedScreens++
                screenStarted.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedScreens = (startedScreens - 1).coerceAtLeast(0)
                screenStarted.value = startedScreens > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun installCore(store: AutoBriefCore.Store, init: LoadedInit) {
        if (loaded.value != null) return
        val core = AutoBriefCore.fromStored(
            store = store,
            stored = init.stored,
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
        if (init.restricted != null) core.setRestricted(init.restricted)
        loaded.value = core
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
