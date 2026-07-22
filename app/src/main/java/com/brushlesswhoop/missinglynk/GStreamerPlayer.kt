package com.brushlesswhoop.missinglynk

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import org.freedesktop.gstreamer.GStreamer

/**
 * GStreamer-backed player. The goggle's HEVC RTSP has no usable clock, which libVLC
 * renders choppily; GStreamer with a `sync=false` sink displays frames as they arrive
 * (smooth). The pipeline + GLib loop live in native code (jni/gstplayer.c); this class is
 * the JNI peer and renders into a TextureView (capture-friendly for Twitch).
 */
class GStreamerPlayer(context: Context) : StreamPlayer, TextureView.SurfaceTextureListener {

    // owned by the native side (a CustomData*). Name/type must match GetFieldID in the JNI.
    private var nativeCustomData: Long = 0

    override var onState: ((PlayerState) -> Unit)? = null

    override val frameCount: Int get() = nativeFrameCount()

    private var surface: Surface? = null
    private var videoView: TextureView? = null

    init {
        GStreamer.init(context)
        nativeInit()
    }

    override fun attachTo(container: ViewGroup) {
        val view = TextureView(container.context)
        view.surfaceTextureListener = this
        container.addView(
            view, 0,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        videoView = view
    }

    override fun setVideoVisible(visible: Boolean) {
        // INVISIBLE (not GONE) keeps the surface valid so the pipeline can render to it
        videoView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    override fun play(url: String) {
        nativeSetUri(url)
        nativePlay()
    }

    override fun release() {
        nativeFinalize()
        surface?.release()
        surface = null
        (videoView?.parent as? ViewGroup)?.removeView(videoView)
        videoView = null
    }

    /** Invoked from the native GStreamer thread with a diagnostic line (decoder choice,
     *  pipeline rebuilds); persisted to the on-device log. */
    private fun onNativeLog(msg: String) {
        Diag.log("gst", msg)
    }

    /** Invoked from the native GStreamer thread; MainActivity marshals onState to the UI. */
    private fun onNativeState(state: Int) {
        val mapped = when (state) {
            0 -> PlayerState.CONNECTING
            1 -> PlayerState.PLAYING
            2 -> PlayerState.ERROR
            else -> PlayerState.ENDED
        }
        onState?.invoke(mapped)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        nativeSurfaceInit(s)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        nativeSurfaceFinalize()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private external fun nativeInit()
    private external fun nativeFinalize()
    private external fun nativeSetUri(uri: String)
    private external fun nativePlay()
    private external fun nativeSurfaceInit(surface: Surface)
    private external fun nativeSurfaceFinalize()
    private external fun nativeFrameCount(): Int

    companion object {
        @JvmStatic
        private external fun nativeClassInit(): Boolean

        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("gstplayer")
            nativeClassInit()
        }
    }
}
