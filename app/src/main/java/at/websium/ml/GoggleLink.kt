package at.websium.ml

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
 *
 * [acceptAnyTransport] drops the transport requirement and takes any address match, which lets a
 * debug build reach the host-side fake goggle (`tools/ml-fake-goggle.py`) over WiFi with no
 * hardware present. It is off in release builds, where taking WiFi is the failure this function
 * exists to prevent.
 */
internal fun <T> pickGoggle(
    prefix: String,
    candidates: List<T>,
    acceptAnyTransport: Boolean = false,
    facts: (T) -> NetworkFacts,
): T? {
    val onTheSubnet = candidates.filter { candidate ->
        addressesMatch(prefix, facts(candidate).addresses)
    }
    val gadget = onTheSubnet.firstOrNull { candidate -> looksLikeGadget(facts(candidate)) }
    if (gadget != null) {
        return gadget
    }
    val unidentified = onTheSubnet.firstOrNull { candidate ->
        !facts(candidate).wifi && !facts(candidate).cellular
    }
    if (unidentified != null || !acceptAnyTransport) {
        return unidentified
    }
    return onTheSubnet.firstOrNull()
}

/**
 * The goggle's USB link: the configured [StreamEndpoint], finding and binding the USB-ethernet
 * Network it lives on, and probing whether the RTSP server is up. Keeps this plumbing out of
 * the Activity. Construct with an Activity or Context; call [shutdown] when done.
 *
 * Networks are tracked through a [ConnectivityManager.NetworkCallback] rather than polled: the
 * platform's own advice against `getAllNetworks` is that polling it is inefficient and prone
 * to race conditions.
 */
class GoggleLink(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /*
     * Read from the installed package's own flag rather than a build-config constant, so it is
     * the shipped artifact that decides. A release build is never debuggable, so the relaxed
     * network selection cannot reach a user.
     */
    private val acceptAnyTransport =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())
    private var probeInFlight = false

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val urlKey = context.getString(R.string.pref_url_key)
    private val defaultUrl = context.getString(R.string.default_rtsp_url)

    /** latched so a URL that stays broken is reported once, and again after it is fixed */
    private var hasWarnedAboutUrl = false

    /** reparsed when Settings changes the URL, so the per-tick path reads a field */
    @Volatile
    private var endpoint: StreamEndpoint? = readEndpoint()

    /*
     * Held in a field because SharedPreferences keeps only a weak reference to a listener; an
     * anonymous one passed straight to register() is collected and stops firing.
     */
    private val urlListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == urlKey) {
            endpoint = readEndpoint()
        }
    }

    /** written from the callback thread, read from the main thread */
    private val networks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    /** the network the process is currently routed over, so binding is idempotent */
    /** written from the UI thread and from the player's own thread when the window closes */
    @Volatile
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
        preferences.registerOnSharedPreferenceChangeListener(urlListener)
    }

    /**
     * A URL with no host reads to the rest of the app exactly like an absent goggle, so it is
     * reported here; otherwise a typo in Settings looks like nothing being plugged in.
     */
    private fun readEndpoint(): StreamEndpoint? {
        val stored = preferences.getString(urlKey, defaultUrl) ?: defaultUrl
        val parsed = StreamEndpoint.parse(stored)
        if (parsed == null) {
            if (!hasWarnedAboutUrl) {
                hasWarnedAboutUrl = true
                Diagnostics.log("link", "stream URL has no host, nothing to look for: $stored")
            }
        } else {
            hasWarnedAboutUrl = false
        }
        return parsed
    }

    /**
     * The configured stream URL and what the link derives from it. Null when the URL carries no
     * host, which leaves nothing to look for and nothing to probe.
     */
    fun endpoint(): StreamEndpoint? {
        return endpoint
    }

    /**
     * The local Network that carries the goggle, or null when it is not attached.
     */
    fun goggleNetwork(): Network? {
        val prefix = endpoint?.subnetPrefix
        if (prefix == null) {
            return null
        }
        val manager = connectivity
        if (manager == null) {
            return null
        }
        return pickGoggle(prefix, networks.toList(), acceptAnyTransport) { network ->
            factsFor(manager, network)
        }
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
     *
     * This is held open only while the player is opening its sockets, and released once it has
     * them. The goggle interface carries no DNS servers and no default route, only its own /24,
     * so a process routed over it can reach the goggle and nothing else: a restream's destination
     * resolves and connects only while the process is on its default network. Sockets already
     * connected keep the route they were made on, which is what makes the window work -- the
     * player's stay on the goggle after the routing is released.
     *
     * The probe binds its own socket ([probeRtsp]) rather than relying on this, since it has to
     * reach the goggle whether the window is open or not.
     */
    fun bindTo(network: Network?) {
        if (network == boundNetwork) {
            return
        }

        /*
         * Every socket opened after this call is routed by it. It is logged because it decides
         * what the program does next, and because a binding that flaps looks exactly like a
         * failing stream.
         */
        Diagnostics.log("link", "routing over ${network ?: "no network"}")
        boundNetwork = network
        connectivity?.bindProcessToNetwork(network)
    }

    /**
     * Asynchronous TCP probe of the RTSP port. [onResult] is delivered on the main thread, and
     * is skipped entirely while an earlier probe is still running.
     *
     * The socket is pinned to the goggle's network rather than taking the process routing, so the
     * probe answers the same whether or not [bindTo]'s window is open. Without that it would read
     * a goggle that is present and serving as gone for as long as the process is on WiFi.
     */
    fun probeRtsp(onResult: (Boolean) -> Unit) {
        if (probeInFlight) {
            return
        }
        val target = endpoint
        if (target == null) {
            return
        }
        val network = goggleNetwork()
        probeInFlight = true
        probeExecutor.execute {
            val portOpen = try {
                Socket().use { socket ->
                    network?.bindSocket(socket)
                    socket.connect(InetSocketAddress(target.host, target.port), PROBE_TIMEOUT_MS)
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
        preferences.unregisterOnSharedPreferenceChangeListener(urlListener)
        probeExecutor.shutdownNow()
    }

    private companion object {
        private const val PROBE_TIMEOUT_MS = 2000
    }
}
