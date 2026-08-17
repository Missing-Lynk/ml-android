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
 * Hosts the video and renders whatever state [ConnectionMachine] is in. The machine decides
 * the states and the timing; this class owns the player, the link and the views, and turns
 * the machine's effects into calls against them.
 *
 * States: SEARCHING (find goggle USB network) -> STREAM_DOWN (RTSP port closed) ->
 * CONNECTING -> NO_QUAD (connected, no frames) / PLAYING (landscape video) ->
 * RECONNECTING (frames stalled; "stay tuned" + auto-retry). A goggle answering on the RTSP
 * port is connected to straight away. Disconnecting parks in READY, where the Connect button
 * is the way back in; unplugging the goggle drops to SEARCHING and re-arms auto-connect.
 */
class MainActivity : AppCompatActivity() {

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
    private lateinit var link: GoggleLink

    private val machine = ConnectionMachine()

    /** the state the views currently show; rendering is skipped while it matches the machine */
    private var rendered: ConnectionMachine.State? = null

    private val ticker = Handler(Looper.getMainLooper())
    private var foreground = true

    private val fullscreenStates =
        setOf(ConnectionMachine.State.PLAYING, ConnectionMachine.State.RECONNECTING)

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

        connectButton.setOnClickListener { apply(machine.onConnectTapped(now())) }
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
        render(machine.state)
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

    // ---- driving the machine ----

    private val tick = object : Runnable {
        override fun run() {
            step()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        val net = link.goggleNetwork()
        if (net != null) link.bind(net)
        apply(
            machine.onTick(
                ConnectionMachine.Tick(
                    hasNetwork = net != null,
                    frameCount = player?.frameCount,
                    foreground = foreground,
                    nowMs = now(),
                )
            )
        )
    }

    private fun disconnect() = apply(machine.onDisconnect(link.goggleNetwork() != null, now()))

    /** carry out the machine's effects, then bring the views up to date */
    private fun apply(step: ConnectionMachine.Step) {
        step.effects.forEach { effect ->
            when (effect) {
                is ConnectionMachine.Effect.Log -> Diag.log(effect.tag, effect.msg)
                ConnectionMachine.Effect.CreatePlayer -> ensurePlayer()
                ConnectionMachine.Effect.TeardownPlayer -> teardownPlayer()
                ConnectionMachine.Effect.StartStream -> player?.play(link.streamUrl())
                ConnectionMachine.Effect.Probe ->
                    link.probeRtsp { up -> apply(machine.onProbeResult(up, now())) }
                ConnectionMachine.Effect.SessionProbe -> link.probeRtsp { up ->
                    apply(machine.onSessionProbeResult(up, player?.frameCount ?: 0, now()))
                }
            }
        }
        if (step.state != rendered) render(step.state)
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ---- player ----

    private fun ensurePlayer(): StreamPlayer {
        player?.let { return it }
        return GStreamerPlayer(this).also {
            it.attachTo(videoContainer)
            it.onState = { s -> runOnUiThread { machine.onPlayerState(s) } }
            player = it
        }
    }

    private fun teardownPlayer() {
        player?.release()
        player = null
    }

    // ---- rendering ----

    private fun render(state: ConnectionMachine.State) {
        if (rendered != null) Diag.log("state", "$rendered -> $state")
        rendered = state

        val playing = state == ConnectionMachine.State.PLAYING
        val fullscreen = state in fullscreenStates
        val inSession = state in ConnectionMachine.SESSION_STATES

        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        statusPanel.visibility = if (playing) View.GONE else View.VISIBLE

        progress.visibility = when (state) {
            ConnectionMachine.State.READY, ConnectionMachine.State.PLAYING -> View.GONE
            else -> View.VISIBLE
        }
        connectButton.visibility =
            if (state == ConnectionMachine.State.READY) View.VISIBLE else View.GONE
        statusImage.visibility =
            if (state == ConnectionMachine.State.RECONNECTING) View.VISIBLE else View.GONE
        statusText.text = statusTextFor(state)
        // setup hint only while there's no goggle/stream yet
        statusHint.visibility = if (
            state == ConnectionMachine.State.SEARCHING || state == ConnectionMachine.State.STREAM_DOWN
        ) View.VISIBLE else View.GONE

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
        if (rendered !in fullscreenStates) return
        fullscreenBack.visibility = View.VISIBLE
        ticker.removeCallbacks(hideFullscreenBack)
        ticker.postDelayed(hideFullscreenBack, CONTROLS_TIMEOUT_MS)
    }

    private fun statusTextFor(s: ConnectionMachine.State): CharSequence = getString(
        when (s) {
            ConnectionMachine.State.SEARCHING -> R.string.state_searching
            ConnectionMachine.State.STREAM_DOWN -> R.string.state_stream_down
            ConnectionMachine.State.READY -> R.string.state_ready
            ConnectionMachine.State.CONNECTING -> R.string.state_connecting
            ConnectionMachine.State.NO_QUAD -> R.string.state_no_quad
            ConnectionMachine.State.RECONNECTING -> R.string.state_reconnecting
            ConnectionMachine.State.PLAYING -> R.string.app_name
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
        private const val CONTROLS_TIMEOUT_MS = 3000L
    }
}
