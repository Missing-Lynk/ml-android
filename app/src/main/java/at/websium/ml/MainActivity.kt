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
 * CONNECTING -> NO_AIR_UNIT (connected, no frames) / PLAYING (landscape video) ->
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

    /** what the views currently show, so tap handling reads the value instead of re-deriving it */
    private var screen: Screen = screenFor(ConnectionMachine.State.SEARCHING)

    private val ticker = Handler(Looper.getMainLooper())
    private var foreground = true

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
                    link.endpoint()?.let { target -> player?.play(target.url) }
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
            created.onEvent = { event ->
                runOnUiThread { machine.onPlayerEvent(event) }
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
        screen = screenFor(state, machine.failureReason)
        applyScreen(screen)
    }

    /**
     * Write [screen] to the views. Every decision was taken in [screenFor]; this assigns.
     */
    private fun applyScreen(screen: Screen) {
        val immersive = screen.chrome == Chrome.IMMERSIVE
        val status = screen.status

        toolbar.visibility = visibilityOf(!immersive)
        statusPanel.visibility = visibilityOf(status != null)
        progress.visibility = visibilityOf(status?.isSpinnerVisible == true)
        connectButton.visibility = visibilityOf(status?.isConnectVisible == true)
        statusImage.visibility = visibilityOf(status?.isImageVisible == true)

        if (status != null) {
            statusText.text = getString(status.textResource)
        }

        val hint = status?.hint
        statusHint.visibility = visibilityOf(hint != null)
        when (hint) {
            is Hint.Copy -> statusHint.setText(hint.textResource)
            is Hint.Detail -> statusHint.text = hint.text
            null -> {
            }
        }

        // show the video only while actually playing, so no frozen last frame leaks through
        player?.setVideoVisible(status == null)

        // the fullscreen back control is only shown transiently on tap
        ticker.removeCallbacks(hideFullscreenBack)
        fullscreenBack.visibility = View.GONE

        requestedOrientation = when {
            immersive -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setSystemBarsVisible(!immersive)
        leaveSession.isEnabled = screen.isInSession
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
        if (screen.chrome != Chrome.IMMERSIVE) {
            return
        }
        fullscreenBack.visibility = View.VISIBLE
        ticker.removeCallbacks(hideFullscreenBack)
        ticker.postDelayed(hideFullscreenBack, CONTROLS_TIMEOUT_MS)
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
    }
}
