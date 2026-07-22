package com.brushlesswhoop.missinglynk

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device diagnostics log. Persists to a file in the app's external files dir so it
 * survives a session and can be read or shared on the phone (no computer needed). Captures
 * the things that explain a bad session: which decoder was chosen, pipeline rebuilds, and
 * state transitions. Self-trimming so it never grows without bound.
 */
object Diag {

    private const val MAX_BYTES = 256 * 1024L
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.getExternalFilesDir(null), "diag.log")
        log("app", "----- launched -----")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(tail)
            }
            f.appendText("${stamp.format(Date())} $tag: $msg\n")
        } catch (_: Exception) {
            // diagnostics must never crash the app
        }
    }

    fun read(): String =
        file?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()
            ?: "(no diagnostics yet)"

    fun clear() {
        file?.runCatching { writeText("") }
    }

    fun file(): File? = file
}
