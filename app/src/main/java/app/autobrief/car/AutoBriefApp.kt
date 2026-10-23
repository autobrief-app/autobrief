package app.autobrief.car

import android.app.Application
import app.autobrief.car.data.JsonFiles
import app.autobrief.car.domain.AutoBriefCore

/** One core per process, shared by the screen, the monitor service and the Gleo receiver. */
class AutoBriefApp : Application() {
    lateinit var core: AutoBriefCore
        private set

    override fun onCreate() {
        super.onCreate()
        val files = JsonFiles(filesDir)
        core = AutoBriefCore(
            initialSettings = files.loadSettings(),
            initialTrips = files.loadTrips(),
            initialOutbox = files.loadOutbox(),
            initialReports = files.loadReports(),
            initialActiveCustody = files.loadActiveCustody(),
            persistence = files,
        )
    }
}

val android.content.Context.core: AutoBriefCore
    get() = (applicationContext as AutoBriefApp).core
