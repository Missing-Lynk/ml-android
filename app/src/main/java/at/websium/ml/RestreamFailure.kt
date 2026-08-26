package at.websium.ml

import androidx.annotation.StringRes

/**
 * What the egress's failure means, read out of the text `rtmp2sink` put on its bus.
 *
 * That text is written for whoever is debugging GStreamer: a rejected stream key arrives as
 * `Failed to connect: gst-resource-error-quark 5 publish denied: ...`, which reads as a socket
 * problem. The cause is in the inner clause, and that is what is matched here: `rtmp2sink` wraps
 * one cause in `Failed to connect`, `Connection refused` or `Not authorized to connect` depending
 * on which GIO error carried it, while the inner clause comes from a single place in
 * `rtmp_rtmpclient.c`.
 *
 * Order is load-bearing. The wrapper of a rejected key contains `failed to connect`, so the
 * clauses that name a cause are tested before the ones that name a transport.
 *
 * Anything unrecognised falls back to a generic line. The bus text is written in GStreamer's
 * vocabulary and means nothing on screen; the diagnostics log keeps it verbatim, which is where
 * a bug report reads it from.
 */
@StringRes
internal fun egressFailureText(reason: String): Int {
    val text = reason.lowercase()

    return when {
        // `NetStream.Publish.BadName`, which every ingest sends for a key that is already live
        text.contains("stream already exists") -> R.string.stream_error_key_in_use

        /*
         * `publish denied` is `NetStream.Publish.Denied`, and `connect.rejected` is where Twitch
         * puts its `AccessManager.Reject` description. Both mean the key, since the URL in front
         * of it resolved and the server answered.
         */
        text.contains("publish denied") ||
            text.contains("not authorized") ||
            text.contains("authentication failed") ||
            text.contains("connect.rejected") -> R.string.stream_error_key_rejected

        /*
         * Matched on two whole words rather than `tls`, which is a substring of ingest hostnames.
         */
        text.contains("tls handshake") || text.contains("certificate") ->
            R.string.stream_error_tls

        /*
         * `rtmp_rtmpclient.c` names whichever part of the location it could not read. Only the
         * scheme is checked before arming, so `rtmp://` with nothing after it arms and lands
         * here as `Failed to connect: Host is not set`.
         */
        text.contains("host is not set") ||
            text.contains("port is not set") ||
            text.contains("application is not set") ||
            text.contains("stream is not set") -> R.string.stream_error_url_incomplete

        text.contains("connection refused") ||
            text.contains("unreachable") ||
            text.contains("no route to host") ||
            text.contains("error resolving") ||
            text.contains("name or service not known") ||
            text.contains("timed out") ||
            text.contains("failed to connect") -> R.string.stream_error_unreachable

        else -> R.string.stream_error_failed
    }
}
