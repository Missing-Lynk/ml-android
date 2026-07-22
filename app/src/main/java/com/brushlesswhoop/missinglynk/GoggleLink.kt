package com.brushlesswhoop.missinglynk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The goggle's USB link: the configured stream URL, finding/binding the USB-ethernet
 * Network it lives on, and probing whether the RTSP server is up. Keeps this plumbing out
 * of the Activity. Construct with an Activity/Context; call [shutdown] when done.
 */
class GoggleLink(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var probeInFlight = false

    /** the configured RTSP URL (the user can change it in Settings) */
    fun streamUrl(): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.pref_url_key), context.getString(R.string.default_rtsp_url))
            ?: context.getString(R.string.default_rtsp_url)

    /** the local Network whose interface is on the stream host's /24 (the goggle USB link) */
    fun goggleNetwork(): Network? {
        val host = Uri.parse(streamUrl()).host ?: return null
        val cut = host.lastIndexOf('.')
        if (cut <= 0) return null
        val prefix = host.substring(0, cut + 1)
        val mgr = cm ?: return null
        for (network in mgr.allNetworks) {
            val lp = mgr.getLinkProperties(network) ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress?.startsWith(prefix) == true }) {
                return network
            }
        }
        return null
    }

    /** route the process (and so the player's sockets) over the goggle interface */
    fun bind(network: Network) {
        cm?.bindProcessToNetwork(network)
    }

    fun unbind() {
        cm?.bindProcessToNetwork(null)
    }

    /** async TCP probe of the RTSP port; [onResult] is delivered on the main thread.
     *  A no-op (no callback) while a probe is already running. */
    fun probeRtsp(onResult: (Boolean) -> Unit) {
        if (probeInFlight) return
        val uri = Uri.parse(streamUrl())
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else RTSP_PORT
        probeInFlight = true
        probeExecutor.execute {
            val up = try {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS); true }
            } catch (e: Exception) {
                false
            }
            main.post {
                probeInFlight = false
                onResult(up)
            }
        }
    }

    fun shutdown() {
        unbind()
        probeExecutor.shutdownNow()
    }

    private companion object {
        private const val RTSP_PORT = 554
        private const val PROBE_TIMEOUT_MS = 2000
    }
}
