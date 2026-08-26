package at.websium.ml

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

    // owned by the native side (a CustomData*); the name and type must match GetFieldID in the JNI
    private var nativeCustomData: Long = 0

    override var onEvent: ((PlayerEvent) -> Unit)? = null

    override var onCodec: ((String) -> Unit)? = null

    override var onRestreamFailed: ((String) -> Unit)? = null

    override var onRestreamLive: ((Boolean) -> Unit)? = null

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
        // INVISIBLE rather than GONE keeps the surface valid, so the pipeline can render to it
        if (visible) {
            videoView?.visibility = View.VISIBLE
        } else {
            videoView?.visibility = View.INVISIBLE
        }
    }

    override fun play(url: String) {
        nativeSetUri(url)
        nativePlay()
    }

    override fun setRestream(url: String?, audio: AudioSource) {
        nativeSetRestream(url, audio == AudioSource.MICROPHONE)
    }

    override fun release() {
        nativeFinalize()
        surface?.release()
        surface = null
        (videoView?.parent as? ViewGroup)?.removeView(videoView)
        videoView = null
    }

    /**
     * Invoked from the native GStreamer thread with a diagnostic line, such as the decoder
     * chosen or a pipeline rebuild, and persisted to the on-device log.
     */
    private fun onNativeLog(message: String) {
        Diagnostics.log("gst", message)
    }

    /**
     * Invoked from the native GStreamer thread once the SDP names the codec.
     */
    private fun onNativeCodec(codec: String) {
        onCodec?.invoke(codec)
    }

    /**
     * Invoked from the native GStreamer thread when the egress fails. The player pipeline keeps
     * playing, so this is deliberately not routed through [onEvent].
     */
    private fun onNativeRestreamFailed(reason: String) {
        onRestreamFailed?.invoke(reason)
    }

    /**
     * Invoked from the native GStreamer thread when the egress starts or stops carrying.
     */
    private fun onNativeRestreamLive(live: Boolean) {
        onRestreamLive?.invoke(live)
    }

    /**
     * Invoked from the native GStreamer thread; MainActivity marshals onEvent to the UI.
     * [reason] carries the error text and is null for every other state.
     */
    private fun onNativeState(stateCode: Int, reason: String?) {
        onEvent?.invoke(PlayerEvent.fromNative(stateCode, reason))
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        val created = Surface(surfaceTexture)
        surface = created
        nativeSurfaceInit(created)
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        nativeSurfaceFinalize()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }

    private external fun nativeInit()
    private external fun nativeFinalize()
    private external fun nativeSetUri(uri: String)
    private external fun nativeSetRestream(url: String?, useMicrophone: Boolean)
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
