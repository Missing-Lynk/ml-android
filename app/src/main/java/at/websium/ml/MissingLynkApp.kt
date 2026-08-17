package at.websium.ml

import android.app.Application

/**
 * Process-scoped setup. [Diag] is a singleton whose lifetime is the process, not the main
 * screen: Android can restore any activity in the task directly after process death, so
 * initialising the log from MainActivity left the Diagnostics screen reading as empty over a
 * log that existed on disk.
 */
class MissingLynkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
    }
}
