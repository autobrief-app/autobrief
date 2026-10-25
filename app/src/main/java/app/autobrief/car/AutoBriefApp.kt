package app.autobrief.car

import android.app.Application
import android.util.Log
import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore
import java.io.File

/** One core per process, shared by the screen, the monitor service and the Gleo receiver. */
class AutoBriefApp : Application() {
    lateinit var core: AutoBriefCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = AutoBriefCore(
            store = JsonFiles(File(filesDir, "logbook")),
            files = { space, name, bytes ->
                // app-specific external storage: no storage permission, removed with the app
                val dir = File(getExternalFilesDir("exports") ?: File(filesDir, "exports"), space.dir).apply { mkdirs() }
                File(dir, name).apply { writeBytes(bytes) }.absolutePath
            },
            log = { line -> Log.i(LOG_TAG, line) },
        )
    }

    companion object {
        /** Logcat tag the demo director and the end-to-end test read (`adb logcat -s AutoBriefLog`). */
        const val LOG_TAG = "AutoBriefLog"
    }
}

val android.content.Context.core: AutoBriefCore
    get() = (applicationContext as AutoBriefApp).core
