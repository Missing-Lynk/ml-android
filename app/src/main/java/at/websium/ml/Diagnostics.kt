package at.websium.ml

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device diagnostics log. Persists to a file in the app's external files dir so it
 * survives a session and can be read or shared on the phone, with no computer involved. It
 * captures what explains a bad session: which decoder was chosen, pipeline rebuilds, stream
 * errors, and state transitions. Self-trimming, so it never grows without bound.
 */
object Diagnostics {

    internal const val MAX_BYTES = 256 * 1024L
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        init(File(context.getExternalFilesDir(null), "diag.log"))
    }

    /**
     * Context-free entry point, so appending, trimming, reading and clearing are exercisable
     * without an Android framework.
     */
    internal fun init(logFile: File) {
        file = logFile
        log("app", "----- launched -----")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val logFile = file
        if (logFile == null) {
            return
        }
        try {
            if (logFile.length() > MAX_BYTES) {
                val tail = logFile.readText().takeLast((MAX_BYTES / 2).toInt())
                logFile.writeText(tail)
            }
            logFile.appendText("${timestamp.format(Date())} $tag: $message\n")
        } catch (_: Exception) {
            // diagnostics must never crash the app
        }
    }

    /**
     * The log's contents, or null when there is nothing to show. There is no Context here to
     * resolve a placeholder string, so the caller supplies one.
     */
    fun read(): String? {
        val logFile = file
        if (logFile == null || !logFile.exists()) {
            return null
        }
        return logFile.runCatching { readText() }.getOrNull()?.ifBlank { null }
    }

    fun clear() {
        file?.runCatching { writeText("") }
    }

    fun file(): File? {
        return file
    }

    /**
     * Return to the pre-init state, so tests can exercise the uninitialised path.
     */
    internal fun reset() {
        file = null
    }
}
