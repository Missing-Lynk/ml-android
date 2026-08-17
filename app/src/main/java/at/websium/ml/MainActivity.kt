package at.websium.ml

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
import androidx.annotation.StringRes
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
    private var lastPlayerFailure: String? = null
    private lateinit var link: GoggleLink

    private val machine = ConnectionMachine()

    /** the state the views currently show; rendering is skipped while it matches the machine */
    private var rendered: ConnectionMachine.State? = null

    private val ticker = Handler(Looper.getMainLooper())
    private var foreground = true

    private val fullscreenStates =
        setOf(ConnectionMachine.State.PLAYING, ConnectionMachine.State.RECONNECTING)

    private val leaveSession = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            disconnect()
        }
    }

    private val hideFullscreenBack = Runnable { fullscreenBack.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        link = GoggleLink(this)

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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        val network = link.goggleNetwork()
        link.bindTo(network)
        apply(
            machine.onTick(
                ConnectionMachine.Tick(
                    hasNetwork = network != null,
                    frameCount = player?.frameCount,
                    foreground = foreground,
                    nowMs = now(),
                )
            )
        )
    }

    private fun disconnect() {
        apply(machine.onDisconnect(link.goggleNetwork() != null, now()))
    }

    /**
     * Carry out the machine's effects, then bring the views up to date.
     */
    private fun apply(step: ConnectionMachine.Step) {
        var playerFailure: String? = null
        step.effects.forEach { effect ->
            when (effect) {
                is ConnectionMachine.Effect.Log -> {
                    Diagnostics.log(effect.tag, effect.message)
                }
                ConnectionMachine.Effect.CreatePlayer -> {
                    if (playerFailure == null && ensurePlayer() == null) {
                        playerFailure = lastPlayerFailure
                    }
                }
                ConnectionMachine.Effect.TeardownPlayer -> {
                    teardownPlayer()
                }
                ConnectionMachine.Effect.StartStream -> {
                    player?.play(link.streamUrl())
                }
                ConnectionMachine.Effect.Probe -> {
                    link.probeRtsp { portOpen ->
                        apply(machine.onProbeResult(portOpen, now()))
                    }
                }
                ConnectionMachine.Effect.SessionProbe -> {
                    link.probeRtsp { portOpen ->
                        val frames = player?.frameCount ?: 0
                        apply(machine.onSessionProbeResult(portOpen, frames, now()))
                    }
                }
            }
        }
        // a player that cannot be built supersedes whatever state this step reached
        val failure = playerFailure
        if (failure != null) {
            apply(machine.onPlayerUnavailable(failure))
            return
        }
        if (step.state != rendered) {
            render(step.state)
        }
    }

    private fun now(): Long {
        return SystemClock.elapsedRealtime()
    }

    // ---- player ----

    /**
     * Null when the player cannot be built here; [lastPlayerFailure] then says why.
     */
    private fun ensurePlayer(): StreamPlayer? {
        val existing = player
        if (existing != null) {
            return existing
        }
        /*
         * Throwable, not Exception: GStreamer.init declares a checked Exception, but the
         * companion's System.loadLibrary raises UnsatisfiedLinkError, which arrives wrapped in
         * ExceptionInInitializerError.
         */
        return try {
            val created = GStreamerPlayer(this)
            created.attachTo(videoContainer)
            created.onState = { playerState ->
                runOnUiThread { machine.onPlayerState(playerState) }
            }
            player = created
            created
        } catch (failure: Throwable) {
            lastPlayerFailure = failure.message ?: failure.javaClass.simpleName
            null
        }
    }

    private fun teardownPlayer() {
        player?.release()
        player = null
    }

    // ---- rendering ----

    private fun render(state: ConnectionMachine.State) {
        if (rendered != null) {
            Diagnostics.log("state", "$rendered -> $state")
        }
        rendered = state

        val playing = state == ConnectionMachine.State.PLAYING
        val fullscreen = state in fullscreenStates
        val inSession = state in ConnectionMachine.SESSION_STATES
        val waitingForGoggle = state == ConnectionMachine.State.SEARCHING ||
            state == ConnectionMachine.State.STREAM_DOWN

        toolbar.visibility = visibilityOf(!fullscreen)
        statusPanel.visibility = visibilityOf(!playing)
        progress.visibility = visibilityOf(state in BUSY_STATES)
        connectButton.visibility = visibilityOf(state == ConnectionMachine.State.READY)
        statusImage.visibility = visibilityOf(state == ConnectionMachine.State.RECONNECTING)
        // the setup hint is for a user who has no goggle or no stream yet
        statusHint.visibility = visibilityOf(waitingForGoggle)

        val statusText = statusTextFor(state)
        if (statusText != null) {
            this.statusText.text = getString(statusText)
        }

        // show the video only while actually playing, so no frozen last frame leaks through
        player?.setVideoVisible(playing)

        // the fullscreen back control is only shown transiently on tap
        ticker.removeCallbacks(hideFullscreenBack)
        fullscreenBack.visibility = View.GONE

        requestedOrientation = when {
            fullscreen -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setSystemBarsVisible(!fullscreen)
        leaveSession.isEnabled = inSession
    }

    private fun visibilityOf(visible: Boolean): Int {
        if (visible) {
            return View.VISIBLE
        }
        return View.GONE
    }

    /**
     * Tap to reveal the back control while fullscreen; it hides itself again.
     */
    private fun revealFullscreenBack() {
        if (rendered !in fullscreenStates) {
            return
        }
        fullscreenBack.visibility = View.VISIBLE
        ticker.removeCallbacks(hideFullscreenBack)
        ticker.postDelayed(hideFullscreenBack, CONTROLS_TIMEOUT_MS)
    }

    /**
     * Null while playing: the status panel is hidden then, so there is no copy to show.
     */
    @StringRes
    private fun statusTextFor(state: ConnectionMachine.State): Int? {
        return when (state) {
            ConnectionMachine.State.SEARCHING -> R.string.state_searching
            ConnectionMachine.State.STREAM_DOWN -> R.string.state_stream_down
            ConnectionMachine.State.READY -> R.string.state_ready
            ConnectionMachine.State.CONNECTING -> R.string.state_connecting
            ConnectionMachine.State.NO_QUAD -> R.string.state_no_quad
            ConnectionMachine.State.RECONNECTING -> R.string.state_reconnecting
            ConnectionMachine.State.UNAVAILABLE -> R.string.state_unavailable
            ConnectionMachine.State.PLAYING -> null
        }
    }

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

        /** states that show the spinner, being the ones waiting on something */
        private val BUSY_STATES = setOf(
            ConnectionMachine.State.SEARCHING,
            ConnectionMachine.State.STREAM_DOWN,
            ConnectionMachine.State.CONNECTING,
            ConnectionMachine.State.NO_QUAD,
            ConnectionMachine.State.RECONNECTING,
        )
    }
}
