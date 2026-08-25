package at.websium.ml

import android.view.ViewGroup

/**
 * What the player reports about the current attempt.
 */
sealed interface PlayerEvent {
    data object Connecting : PlayerEvent
    data object Playing : PlayerEvent

    /**
     * The attempt failed. [reason] is the line jni/gstplayer.c's `error_cb` builds, which is what
     * separates a refused connection from a missing decoder or a broken SDP.
     */
    data class Failed(val reason: String?) : PlayerEvent

    data object Ended : PlayerEvent

    companion object {
        /**
         * Map a native state code and its reason to an event. The codes are the `ST_*` defines in
         * jni/gstplayer.c and the mapping is a hand-maintained contract with that file. Anything
         * unrecognised counts as the stream having ended.
         */
        fun fromNative(code: Int, reason: String?): PlayerEvent {
            return when (code) {
                0 -> Connecting
                1 -> Playing
                2 -> Failed(reason)
                else -> Ended
            }
        }
    }
}

/**
 * Thin player abstraction. GStreamer backs it; the interface keeps the Activity decoupled.
 */
interface StreamPlayer {
    /** events, delivered on a player thread: marshal to the UI thread yourself */
    var onEvent: ((PlayerEvent) -> Unit)?

    /**
     * The codec the SDP negotiated, "H264" or "H265". Separate from [onEvent] because it says
     * nothing about whether the connection is up: it is what a destination is judged against.
     * Delivered on a player thread.
     */
    var onCodec: ((String) -> Unit)?

    /**
     * The egress gave up, carrying the reason. Playback is unaffected and keeps running,
     * so this is not a [PlayerEvent.Failed]. Delivered on a player thread.
     */
    var onRestreamFailed: ((String) -> Unit)?

    /**
     * Whether the restream is carrying to its destination. False while it is between attempts,
     * which the automatic reconnect makes otherwise invisible. Delivered on a player thread.
     */
    var onRestreamLive: ((Boolean) -> Unit)?

    /**
     * Start the restream to [url], or stop it with null. Takes effect immediately and does not
     * disturb playback: the egress is a pipeline of its own, started and stopped beside the
     * player.
     */
    fun setRestream(url: String?)

    /** monotonic count of frames that have reached the sink; 0 means no media is flowing */
    val frameCount: Int

    /**
     * The player creates its own render view and adds it into [container], behind the other
     * children. Call once.
     */
    fun attachTo(container: ViewGroup)

    /**
     * Show or hide the render view. Hide it when not playing, so no frozen last frame shows.
     */
    fun setVideoVisible(visible: Boolean)

    /**
     * Open and play [url].
     */
    fun play(url: String)

    fun release()
}
