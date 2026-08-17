package at.websium.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The host's subnet as a dotted prefix: "192.168.3.101" -> "192.168.3.". Null when the host
 *  is not a dotted address (a name, an IPv6 literal, a bare label), since there is then no
 *  prefix to match interfaces against. */
internal fun subnetPrefix(host: String?): String? {
    if (host == null) return null
    val cut = host.lastIndexOf('.')
    if (cut <= 0) return null
    return host.substring(0, cut + 1)
}

/** Whether any of an interface's addresses sits in [prefix]'s subnet. The dot terminating
 *  the prefix is what keeps 192.168.3. from matching 192.168.30.x. */
internal fun addressesMatch(prefix: String, addresses: List<String?>): Boolean =
    addresses.any { it?.startsWith(prefix) == true }

/** What the app can observe about one of the phone's networks. */
internal data class NetworkFacts(
    val ethernet: Boolean = false,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val interfaceName: String? = null,
    val addresses: List<String?> = emptyList(),
)

/** A CDC-ECM gadget presents as ethernet on the phones we have seen, but the transport is not
 *  guaranteed to be reported, so the interface name is accepted as well. */
private val GADGET_INTERFACE = Regex("^(usb|eth|rndis|ncm)\\d*$")

internal fun looksLikeGadget(f: NetworkFacts): Boolean =
    f.ethernet || (f.interfaceName != null && GADGET_INTERFACE.matches(f.interfaceName))

/**
 * Pick the goggle out of the phone's networks.
 *
 * An address in the stream host's subnet is necessary but NOT sufficient: 192.168.x.x is
 * exactly the range home routers hand out, so a WiFi network can hold a matching address
 * and binding the process to it would take every socket away from the goggle. Among the
 * address matches the gadget-looking one therefore wins; a network that is positively
 * something else (WiFi, cellular) is never chosen, and anything unidentifiable is taken only
 * as a last resort so an unusual gadget still connects.
 */
internal fun <T> pickGoggle(prefix: String, candidates: List<T>, facts: (T) -> NetworkFacts): T? {
    val onTheSubnet = candidates.filter { addressesMatch(prefix, facts(it).addresses) }
    return onTheSubnet.firstOrNull { looksLikeGadget(facts(it)) }
        ?: onTheSubnet.firstOrNull { !facts(it).wifi && !facts(it).cellular }
}

/**
 * The goggle's USB link: the configured stream URL, finding/binding the USB-ethernet
 * Network it lives on, and probing whether the RTSP server is up. Keeps this plumbing out
 * of the Activity. Construct with an Activity/Context; call [shutdown] when done.
 *
 * Networks are tracked through a [ConnectivityManager.NetworkCallback] rather than polled:
 * the platform's own advice against `getAllNetworks` is that polling it is "inefficient and
 * prone to race conditions". The request has its capabilities cleared because the gadget
 * offers no internet, which the default request would filter out.
 */
class GoggleLink(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var probeInFlight = false

    /** written from the callback thread, read from the main thread */
    private val networks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    /** the network the process is currently routed over, so binding is idempotent */
    private var bound: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.add(network)
        }

        override fun onLost(network: Network) {
            networks.remove(network)
        }
    }

    init {
        // The builder's defaults (TRUSTED, NOT_VPN, NOT_RESTRICTED) already match a USB
        // gadget, and INTERNET is deliberately not required because the goggle offers none.
        // NOT_RESTRICTED is dropped as well so nothing about how the phone classifies the
        // gadget can hide it; picking the right network is pickGoggle's job, not the
        // request's. clearCapabilities() would say this outright but needs API 30.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        cm?.registerNetworkCallback(request, callback)
    }

    /** the configured RTSP URL (the user can change it in Settings) */
    fun streamUrl(): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.pref_url_key), context.getString(R.string.default_rtsp_url))
            ?: context.getString(R.string.default_rtsp_url)

    /** the local Network that carries the goggle, or null when it is not attached */
    fun goggleNetwork(): Network? {
        val prefix = subnetPrefix(Uri.parse(streamUrl()).host) ?: return null
        val mgr = cm ?: return null
        return pickGoggle(prefix, networks.toList()) { factsFor(mgr, it) }
    }

    private fun factsFor(mgr: ConnectivityManager, network: Network): NetworkFacts {
        val lp = mgr.getLinkProperties(network)
        val caps = mgr.getNetworkCapabilities(network)
        return NetworkFacts(
            ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            interfaceName = lp?.interfaceName,
            addresses = lp?.linkAddresses?.map { it.address.hostAddress } ?: emptyList(),
        )
    }

    /**
     * Route the process (and so the player's sockets) over the goggle interface, or release
     * the routing when [network] is null. Idempotent: repeated calls with the same network do
     * nothing, and a vanished goggle is unbound rather than left bound to a dead network.
     */
    fun bindTo(network: Network?) {
        if (network == bound) return
        bound = network
        cm?.bindProcessToNetwork(network)
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
        bindTo(null)
        cm?.unregisterNetworkCallback(callback)
        probeExecutor.shutdownNow()
    }

    private companion object {
        private const val RTSP_PORT = 554
        private const val PROBE_TIMEOUT_MS = 2000
    }
}
