package at.websium.ml

import java.net.URI

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
 * The configured stream URL together with everything the link derives from it: the host to
 * reach, the port to probe, and the subnet that identifies the goggle's interface. Parsed once
 * per URL, so the per-tick path reads fields.
 */
data class StreamEndpoint(
    /** the configured URL verbatim, which is what the player is pointed at */
    val url: String,
    val host: String,
    val port: Int,
    /** the host's subnet as a dotted prefix; null when the host is a name or an IPv6 literal */
    val subnetPrefix: String?,
) {
    companion object {
        /** RTSP's port, used for a URL that names none */
        const val DEFAULT_PORT = 554

        /**
         * Null when [url] is not a URL carrying a host. The link treats that the same way it
         * treats an absent goggle: there is no interface to look for and no port to probe.
         */
        fun parse(url: String): StreamEndpoint? {
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null) {
                return null
            }

            val host = uri.host
            if (host.isNullOrEmpty()) {
                return null
            }

            val port = when {
                uri.port > 0 -> uri.port
                else -> DEFAULT_PORT
            }

            return StreamEndpoint(url = url, host = host, port = port, subnetPrefix = subnetPrefix(host))
        }
    }
}
