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

/**
 * The host's subnet as a dotted prefix: "192.168.3.101" gives "192.168.3.". Null when the host
 * is not a dotted address (a name, an IPv6 literal, a bare label), since there is then no
 * prefix to match interfaces against.
 */
internal fun subnetPrefix(host: String?): String? {
    if (host == null) {
        return null
    }
    val lastDot = host.lastIndexOf('.')
    if (lastDot <= 0) {
        return null
    }
    return host.substring(0, lastDot + 1)
}

/**
 * Whether any of an interface's addresses sits in [prefix]'s subnet. The dot terminating the
 * prefix is what keeps 192.168.3. from matching 192.168.30.x.
 */
internal fun addressesMatch(prefix: String, addresses: List<String?>): Boolean {
    return addresses.any { address -> address?.startsWith(prefix) == true }
}

/**
 * What the app can observe about one of the phone's networks.
 */
internal data class NetworkFacts(
    val ethernet: Boolean = false,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val interfaceName: String? = null,
    val addresses: List<String?> = emptyList(),
)

/**
 * A CDC-ECM gadget presents as ethernet, but the transport is not guaranteed to be reported,
 * so the interface name is accepted as well.
 */
private val GADGET_INTERFACE = Regex("^(usb|eth|rndis|ncm)\\d*$")

internal fun looksLikeGadget(facts: NetworkFacts): Boolean {
    if (facts.ethernet) {
        return true
    }
    if (facts.interfaceName == null) {
        return false
    }
    return GADGET_INTERFACE.matches(facts.interfaceName)
}

/**
 * Pick the goggle out of the phone's networks.
 *
 * An address in the stream host's subnet is necessary but not sufficient: 192.168.x.x is
 * exactly the range home routers hand out, so a WiFi network can hold a matching address and
 * binding the process to it would take every socket away from the goggle. Among the address
 * matches the gadget-looking one wins; a network that is positively something else (WiFi,
 * cellular) is never chosen, and anything unidentifiable is taken only as a last resort so an
 * unusual gadget still connects.
 */
internal fun <T> pickGoggle(
    prefix: String,
    candidates: List<T>,
    facts: (T) -> NetworkFacts,
): T? {
    val onTheSubnet = candidates.filter { candidate ->
        addressesMatch(prefix, facts(candidate).addresses)
    }
    val gadget = onTheSubnet.firstOrNull { candidate -> looksLikeGadget(facts(candidate)) }
    if (gadget != null) {
        return gadget
    }
    return onTheSubnet.firstOrNull { candidate ->
        !facts(candidate).wifi && !facts(candidate).cellular
    }
}

/**
 * The goggle's USB link: the configured stream URL, finding and binding the USB-ethernet
 * Network it lives on, and probing whether the RTSP server is up. Keeps this plumbing out of
 * the Activity. Construct with an Activity or Context; call [shutdown] when done.
 *
 * Networks are tracked through a [ConnectivityManager.NetworkCallback] rather than polled: the
 * platform's own advice against `getAllNetworks` is that polling it is inefficient and prone
 * to race conditions.
 */
class GoggleLink(private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())
    private var probeInFlight = false

    /** written from the callback thread, read from the main thread */
    private val networks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    /** the network the process is currently routed over, so binding is idempotent */
    private var boundNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.add(network)
        }

        override fun onLost(network: Network) {
            networks.remove(network)
        }
    }

    init {
        /*
         * The builder's defaults (TRUSTED, NOT_VPN, NOT_RESTRICTED) match a USB gadget, and
         * INTERNET is deliberately not required because the goggle offers none. NOT_RESTRICTED
         * is dropped as well so nothing about how the phone classifies the gadget can hide it;
         * choosing between the reported networks is pickGoggle's job. clearCapabilities() says
         * the same thing outright but needs API 30.
         */
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        connectivity?.registerNetworkCallback(request, networkCallback)
    }

    /**
     * The configured RTSP URL, which the user can change in Settings.
     */
    fun streamUrl(): String {
        val fallback = context.getString(R.string.default_rtsp_url)
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.pref_url_key), fallback)
        return stored ?: fallback
    }

    /**
     * The local Network that carries the goggle, or null when it is not attached.
     */
    fun goggleNetwork(): Network? {
        val prefix = subnetPrefix(Uri.parse(streamUrl()).host)
        if (prefix == null) {
            return null
        }
        val manager = connectivity
        if (manager == null) {
            return null
        }
        return pickGoggle(prefix, networks.toList()) { network -> factsFor(manager, network) }
    }

    private fun factsFor(manager: ConnectivityManager, network: Network): NetworkFacts {
        val linkProperties = manager.getLinkProperties(network)
        val capabilities = manager.getNetworkCapabilities(network)
        return NetworkFacts(
            ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            interfaceName = linkProperties?.interfaceName,
            addresses = linkProperties?.linkAddresses?.map { it.address.hostAddress }
                ?: emptyList(),
        )
    }

    /**
     * Route the process, and so the player's sockets, over the goggle interface; release the
     * routing when [network] is null. Idempotent: repeated calls with the same network do
     * nothing, and a vanished goggle is unbound rather than left bound to a dead network.
     */
    fun bindTo(network: Network?) {
        if (network == boundNetwork) {
            return
        }
        boundNetwork = network
        connectivity?.bindProcessToNetwork(network)
    }

    /**
     * Asynchronous TCP probe of the RTSP port. [onResult] is delivered on the main thread, and
     * is skipped entirely while an earlier probe is still running.
     */
    fun probeRtsp(onResult: (Boolean) -> Unit) {
        if (probeInFlight) {
            return
        }
        val uri = Uri.parse(streamUrl())
        val host = uri.host
        if (host == null) {
            return
        }
        val port = when {
            uri.port > 0 -> uri.port
            else -> RTSP_PORT
        }
        probeInFlight = true
        probeExecutor.execute {
            val portOpen = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                    true
                }
            } catch (_: Exception) {
                false
            }
            mainThread.post {
                probeInFlight = false
                onResult(portOpen)
            }
        }
    }

    fun shutdown() {
        bindTo(null)
        connectivity?.unregisterNetworkCallback(networkCallback)
        probeExecutor.shutdownNow()
    }

    private companion object {
        private const val RTSP_PORT = 554
        private const val PROBE_TIMEOUT_MS = 2000
    }
}
