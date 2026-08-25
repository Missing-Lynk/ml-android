package at.websium.ml

/**
 * Where the restream goes. Twitch, YouTube and Kick each hand out a server URL and a stream key
 * separately; joining them with a slash is what every encoder asks for, so the app takes the
 * whole thing as one pasted string rather than two fields.
 *
 * The trailing segment is a credential. Anything that displays or logs a destination shows
 * [redactStreamKey] instead, because Diagnostics has a Share button and a stream key in a bug
 * report lets a stranger broadcast to the reporter's channel.
 */
private const val KEY_MASK = "***"

/**
 * [url] with its last path segment replaced by a mask. Returns the input unchanged when there is
 * nothing that looks like a key to hide, so a half-typed URL still reads back usefully.
 *
 * The last segment is masked whether or not it is really a key, because nothing in the string
 * distinguishes them: `rtmp://host/app` masks `app`, which costs nothing.
 */
internal fun redactStreamKey(url: String): String {
    val scheme = url.indexOf("://")
    val searchFrom = if (scheme >= 0) scheme + 3 else 0
    val lastSlash = url.lastIndexOf('/')
    if (lastSlash < searchFrom || lastSlash == url.length - 1) {
        return url
    }

    return url.substring(0, lastSlash + 1) + KEY_MASK
}

/**
 * Whether [destination] is a URL the restream can be pushed to at all. Only the scheme is
 * checked: whether the ingest accepts the stream is a question only the ingest can answer, and
 * it answers it by rejecting the connection.
 */
internal fun isRestreamUrl(destination: String?): Boolean {
    if (destination == null) {
        return false
    }

    val trimmed = destination.trim()
    return trimmed.startsWith("rtmp://") || trimmed.startsWith("rtmps://")
}

/**
 * Whether a destination will take [codec], which is "H264" or "H265" as the SDP named it.
 *
 * Twitch and Kick ingest H.264 only. YouTube and self-hosted servers (MediaMTX, SRS) also take
 * H.265 over enhanced RTMP. The host is what identifies the destination, so a self-hosted server
 * is anything not recognised, which is the permissive answer: an unknown ingest is given the
 * stream and allowed to refuse it itself.
 */
internal fun isCodecAccepted(destination: String, codec: String): Boolean {
    if (!codec.equals("H265", ignoreCase = true)) {
        return true
    }

    val hostAndPort = destination.substringAfter("://", "").substringBefore('/').lowercase()
    val host = hostAndPort.substringBefore(':')

    /*
     * live-video.net carries Twitch's real ingests: the URL its dashboard hands out is a regional
     * name like sfo.contribute.live-video.net, and twitch.tv appears only in documentation.
     * Matching the documented name alone would pass every genuine Twitch URL straight through.
     */
    val h264Only = listOf("twitch.tv", "live-video.net", "kick.com")
    return h264Only.none { rejected -> host == rejected || host.endsWith(".$rejected") }
}
