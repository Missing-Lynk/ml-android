package at.websium.ml

import android.app.Application

/**
 * Process-scoped setup. [Diagnostics] is a singleton whose lifetime is the process, not the
 * main screen: Android can restore any activity in the task directly after process death, so
 * the log has to be available before any of them runs.
 */
class MissingLynkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
    }
}
