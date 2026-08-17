package com.brushlesswhoop.missinglynk

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Drives the connection state machine:
 *   SEARCHING (find goggle USB network) -> STREAM_DOWN (RTSP port closed) ->
 *   CONNECTING -> NO_QUAD (connected, no frames) / PLAYING (landscape video) ->
 *   RECONNECTING (frames stalled; "stay tuned" + auto-retry).
 * A goggle answering on the RTSP port is connected to straight away. Disconnecting parks in
 * READY, where the Connect button is the way back in; unplugging the goggle drops to
 * SEARCHING and re-arms auto-connect.
 */
class MainActivity : AppCompatActivity() {

    private enum class St { SEARCHING, STREAM_DOWN, READY, CONNECTING, NO_QUAD, PLAYING, RECONNECTING }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var videoContainer: FrameLayout
    private lateinit var statusPanel: View
    private lateinit var statusImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var progress: CircularProgressIndicator
    private lateinit var fullscreenBack: ImageButton

    private var player: StreamPlayer? = null
    private var state = St.SEARCHING
    private lateinit var link: GoggleLink

    private val ticker = Handler(Looper.getMainLooper())

    // frame-flow tracking (ms via elapsedRealtime)
    private var lastFrame = 0
    private var lastFrameAtMs = 0L
    private var sessionStartMs = 0L
    private var lastPlayMs = 0L
    private var lastSessionProbeMs = 0L
    @Volatile private var playerError = false
    private var foreground = true

    // set when the user leaves a session, so the probe parks in READY instead of reconnecting
    // straight away. Cleared when the goggle goes away and on a Connect tap.
    private var userDisconnected = false

    private val sessionStates = setOf(St.CONNECTING, St.NO_QUAD, St.PLAYING, St.RECONNECTING)
    private val fullscreenStates = setOf(St.PLAYING, St.RECONNECTING)

    private val leaveSession = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = disconnect()
    }

    private val hideFullscreenBack = Runnable { fullscreenBack.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        link = GoggleLink(this)
        Diag.init(this)

        toolbar = findViewById(R.id.toolbar)
        videoContainer = findViewById(R.id.video_container)
        statusPanel = findViewById(R.id.status_panel)
        statusImage = findViewById(R.id.status_image)
        statusText = findViewById(R.id.status_text)
        statusHint = findViewById(R.id.status_hint)
        connectButton = findViewById(R.id.connect_button)
        progress = findViewById(R.id.progress)
        fullscreenBack = findViewById(R.id.fullscreen_back)

        setSupportActionBar(toolbar)

        connectButton.setOnClickListener { connect() }
        // tap the video while fullscreen to reveal a back control; tap it to disconnect
        videoContainer.setOnClickListener { revealFullscreenBack() }
        fullscreenBack.setOnClickListener { disconnect() }
        onBackPressedDispatcher.addCallback(this, leaveSession)

        // targetSdk 36 forces edge-to-edge; pad the content by the system-bar insets so the
        // toolbar sits below the status bar. When bars are hidden (playing) the insets are 0,
        // so the video stays fullscreen.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // keep the screen on the whole time the app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // run the state machine for the activity's whole life. We deliberately do NOT stop
        // the player on background: the feed keeps decoding so it's live (not frozen) on
        // return, which is what Twitch screen-share needs.
        setState(St.SEARCHING)
        ticker.post(tick)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java)); true
            }
            R.id.action_diagnostics -> {
                startActivity(Intent(this, DiagnosticsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        // keep the player running in the background; just stop reacting to stalls (the GL
        // sink can't render without a surface, so the feed reconnects to live on return).
        super.onStop()
        foreground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tick)
        teardownPlayer()
        link.shutdown()
    }

    // ---- state machine ----

    private val tick = object : Runnable {
        override fun run() {
            step()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val net = link.goggleNetwork()
        if (net == null) {
            userDisconnected = false
            if (state != St.SEARCHING) {
                teardownPlayer()
                setState(St.SEARCHING)
            }
            return
        }
        link.bind(net)

        when (state) {
            St.SEARCHING, St.STREAM_DOWN -> probeRtsp()
            St.READY -> Unit // waiting for the Connect tap
            St.CONNECTING, St.NO_QUAD, St.RECONNECTING -> stepConnecting()
            St.PLAYING -> stepPlaying()
        }
    }

    /** probe the RTSP port; success -> connect (or READY after a manual disconnect),
     *  failure -> STREAM_DOWN (guarded internally) */
    private fun probeRtsp() {
        link.probeRtsp { up ->
            if (state != St.SEARCHING && state != St.STREAM_DOWN) return@probeRtsp
            when {
                !up -> setState(St.STREAM_DOWN)
                userDisconnected -> setState(St.READY)
                else -> {
                    Diag.log("conn", "RTSP up, connecting")
                    connect()
                }
            }
        }
    }

    /** while a session has no media, re-check that the goggle still serves RTSP. A closed port
     *  means the server went away (goggle reboot, stream switched off), so hand back to the
     *  auto-connect path, which reconnects as soon as it answers again. */
    private fun probeSessionAlive() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSessionProbeMs < SESSION_PROBE_MS) return
        lastSessionProbeMs = now
        link.probeRtsp { up ->
            // the probe takes up to its timeout, so re-check that media is still absent:
            // frames may have started flowing while it was outstanding
            if (up || state !in sessionStates || (player?.frameCount ?: 0) > 0) return@probeRtsp
            Diag.log("conn", "RTSP port closed, waiting for the stream")
            teardownPlayer()
            setState(St.STREAM_DOWN)
        }
    }

    /** waiting for media: promote to PLAYING when frames arrive; rebuild only once the
     *  current attempt has actually failed (rebuilding on a timer interrupts rtspsrc
     *  mid-handshake -> "can't get sdp"), with a long fallback for a hung attempt. */
    private fun stepConnecting() {
        val p = player ?: return
        if (p.frameCount > 0) {
            enterPlaying()
            return
        }
        probeSessionAlive()
        val now = SystemClock.elapsedRealtime()
        val sinceRebuild = now - lastPlayMs
        if (playerError && sinceRebuild >= REBUILD_GAP_MS) {
            playerError = false
            Diag.log("conn", "rebuild after player error")
            startStream(p)
        } else if (sinceRebuild >= STUCK_MS) {
            Diag.log("conn", "rebuild after ${STUCK_MS}ms stuck (no frames)")
            startStream(p)
        }
        if (state == St.CONNECTING && now - sessionStartMs > NO_VIDEO_MS) {
            setState(St.NO_QUAD)
        }
    }

    /** playing: watch for a frozen stream and flip to reconnecting */
    private fun stepPlaying() {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        val fc = p.frameCount
        if (fc != lastFrame) {
            lastFrame = fc
            lastFrameAtMs = now
        } else if (foreground && now - lastFrameAtMs > STALL_MS) {
            Diag.log("play", "stalled ${STALL_MS}ms (last frame=$fc) -> reconnecting")
            enterReconnecting()
        }
    }

    private fun connect() {
        userDisconnected = false
        val p = ensurePlayer()
        sessionStartMs = SystemClock.elapsedRealtime()
        lastSessionProbeMs = sessionStartMs
        setState(St.CONNECTING)
        startStream(p)
    }

    /** baseline the stall detector: no new frames counted since right now */
    private fun resetFrameTracking(frameCount: Int): Long {
        lastFrame = frameCount
        lastFrameAtMs = SystemClock.elapsedRealtime()
        return lastFrameAtMs
    }

    private fun startStream(p: StreamPlayer) {
        p.play(link.streamUrl())
        lastPlayMs = resetFrameTracking(0)
    }

    private fun enterPlaying() {
        resetFrameTracking(player?.frameCount ?: 0)
        setState(St.PLAYING)
    }

    private fun enterReconnecting() {
        setState(St.RECONNECTING)
        player?.let { startStream(it) }
    }

    private fun disconnect() {
        val net = link.goggleNetwork()
        userDisconnected = net != null
        teardownPlayer()
        setState(if (net != null) St.READY else St.SEARCHING)
    }

    // ---- player ----

    private fun ensurePlayer(): StreamPlayer {
        player?.let { return it }
        return GStreamerPlayer(this).also {
            it.attachTo(videoContainer)
            it.onState = { s -> runOnUiThread { onPlayerState(s) } }
            player = it
        }
    }

    private fun onPlayerState(s: PlayerState) {
        if (s == PlayerState.ERROR || s == PlayerState.ENDED) {
            playerError = true
        }
    }

    private fun teardownPlayer() {
        player?.release()
        player = null
    }

    // ---- rendering ----

    private fun setState(s: St) {
        if (s != state) Diag.log("state", "$state -> $s")
        state = s
        render()
    }

    private fun render() {
        val playing = state == St.PLAYING
        val fullscreen = state in fullscreenStates
        val inSession = state in sessionStates

        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        statusPanel.visibility = if (playing) View.GONE else View.VISIBLE

        progress.visibility = when (state) {
            St.READY, St.PLAYING -> View.GONE
            else -> View.VISIBLE
        }
        connectButton.visibility = if (state == St.READY) View.VISIBLE else View.GONE
        statusImage.visibility = if (state == St.RECONNECTING) View.VISIBLE else View.GONE
        statusText.text = statusTextFor(state)
        // setup hint only while there's no goggle/stream yet
        statusHint.visibility =
            if (state == St.SEARCHING || state == St.STREAM_DOWN) View.VISIBLE else View.GONE

        // show the video only while actually playing, so no frozen last frame leaks through
        player?.setVideoVisible(playing)

        // the fullscreen back control is only shown transiently on tap
        ticker.removeCallbacks(hideFullscreenBack)
        fullscreenBack.visibility = View.GONE

        requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setSystemBarsVisible(!fullscreen)
        leaveSession.isEnabled = inSession
    }

    /** tap-to-reveal the back control while fullscreen; auto-hides */
    private fun revealFullscreenBack() {
        if (state !in fullscreenStates) return
        fullscreenBack.visibility = View.VISIBLE
        ticker.removeCallbacks(hideFullscreenBack)
        ticker.postDelayed(hideFullscreenBack, CONTROLS_TIMEOUT_MS)
    }

    private fun statusTextFor(s: St): CharSequence = getString(
        when (s) {
            St.SEARCHING -> R.string.state_searching
            St.STREAM_DOWN -> R.string.state_stream_down
            St.READY -> R.string.state_ready
            St.CONNECTING -> R.string.state_connecting
            St.NO_QUAD -> R.string.state_no_quad
            St.RECONNECTING -> R.string.state_reconnecting
            St.PLAYING -> R.string.app_name
        }
    )

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        private const val TICK_MS = 1000L
        private const val NO_VIDEO_MS = 7000L     // CONNECTING with no frames -> NO_QUAD
        // min gap before rebuilding after a failed attempt (debounce; don't interrupt an
        // in-progress rtspsrc handshake), and a long fallback if an attempt just hangs.
        private const val REBUILD_GAP_MS = 4000L
        private const val STUCK_MS = 20000L
        // PLAYING with no new frames -> RECONNECTING. Kept high: FPV feeds blip for a few
        // seconds (Tx-side link resets); only a real drop (battery swap) should reconnect.
        private const val STALL_MS = 8000L
        // how often a session with no media re-checks that the RTSP port is still open
        private const val SESSION_PROBE_MS = 5000L
        private const val CONTROLS_TIMEOUT_MS = 3000L
    }
}
