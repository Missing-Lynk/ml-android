package at.websium.ml

import android.view.ViewGroup

enum class PlayerState {
    CONNECTING, PLAYING, ERROR, ENDED;

    companion object {
        /**
         * Map a native state code to a state. The codes are the `ST_*` defines in
         * jni/gstplayer.c and the mapping is a hand-maintained contract with that file.
         * Anything unrecognised counts as the stream having ended.
         */
        fun fromNative(code: Int): PlayerState {
            return when (code) {
                0 -> CONNECTING
                1 -> PLAYING
                2 -> ERROR
                else -> ENDED
            }
        }
    }
}

/**
 * Thin player abstraction. GStreamer backs it; the interface keeps the Activity decoupled.
 */
interface StreamPlayer {
    /** state changes, delivered on a player thread: marshal to the UI thread yourself */
    var onState: ((PlayerState) -> Unit)?

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
