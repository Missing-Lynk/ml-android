package com.brushlesswhoop.missinglynk

import android.view.ViewGroup

enum class PlayerState { CONNECTING, PLAYING, ERROR, ENDED }

/**
 * Thin player abstraction (GStreamer backs it; the interface keeps the Activity decoupled).
 */
interface StreamPlayer {
    /** State changes (delivered on a player thread; marshal to the UI thread yourself). */
    var onState: ((PlayerState) -> Unit)?

    /** Monotonic count of frames that have reached the sink (0 = no media flowing yet). */
    val frameCount: Int get() = 0

    /** Player creates its own render view and adds it (behind other children) into [container]. Call once. */
    fun attachTo(container: ViewGroup)

    /** Show/hide the render view (hide it when not playing so no frozen last frame shows). */
    fun setVideoVisible(visible: Boolean) {}

    /** Open and play [url]. */
    fun play(url: String)

    fun release()
}
