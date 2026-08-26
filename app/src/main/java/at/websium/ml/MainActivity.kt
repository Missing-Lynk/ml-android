package at.websium.ml

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Hosts the video and renders whatever state the two machines are in. [ConnectionMachine] decides
 * the connection states and their timing, [RestreamMachine] decides whether a broadcast is armed
 * and carrying; this class owns the player, the link, the keep-alive service and the views, and
 * turns both machines' effects into calls against them.
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
    private lateinit var streamToggle: ImageButton
    private lateinit var streamBadge: TextView

    private var player: StreamPlayer? = null
    private var lastPlayerFailure: String? = null
    private lateinit var link: GoggleLink
    private lateinit var destinations: DestinationStore

    private val machine = ConnectionMachine()
    private val restream = RestreamMachine()

    /** what the views currently show; rendering is skipped while it matches */
    private var rendered: Screen? = null

    /** the connection state the transition log last reported */
    private var loggedState: ConnectionMachine.State? = null

    /** whether the transient controls over the video are showing */
    private var areControlsRevealed = false

    private val ticker = Handler(Looper.getMainLooper())
    private var foreground = true

    private val leaveSession = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            disconnect()
        }
    }

    private val hideControls = Runnable {
        areControlsRevealed = false
        render()
    }

    /**
     * Stop tapped in the keep-alive notification, which is the only way to end a broadcast
     * without bringing the app to the front.
     */
    private val stopFromNotification = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyRestream(restream.onStopRequested())
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        link = GoggleLink(this)
        destinations = DestinationStore(this)

        toolbar = findViewById(R.id.toolbar)
        videoContainer = findViewById(R.id.video_container)
        statusPanel = findViewById(R.id.status_panel)
        statusImage = findViewById(R.id.status_image)
        statusText = findViewById(R.id.status_text)
        statusHint = findViewById(R.id.status_hint)
        connectButton = findViewById(R.id.connect_button)
        progress = findViewById(R.id.progress)
        fullscreenBack = findViewById(R.id.fullscreen_back)
        streamToggle = findViewById(R.id.stream_toggle)
        streamBadge = findViewById(R.id.stream_badge)

        setSupportActionBar(toolbar)

        connectButton.setOnClickListener { apply(machine.onConnectTapped(now())) }
        // tap the video while fullscreen to reveal the controls; the back one disconnects
        videoContainer.setOnClickListener { revealControls() }
        fullscreenBack.setOnClickListener { disconnect() }
        streamToggle.setOnClickListener { toggleStreaming() }
        onBackPressedDispatcher.addCallback(this, leaveSession)

        ContextCompat.registerReceiver(
            this,
            stopFromNotification,
            IntentFilter(RestreamService.ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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
        render()
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
        /*
         * Coming back from Settings, where the armed destination may have been deleted. An edit
         * to it is picked up at the next re-arm instead, but a deletion leaves the broadcast with
         * nowhere to go and has to end it now.
         */
        if (restream.isArmed && armedDestination() == null) {
            applyRestream(restream.onDestinationDeleted())
        }
    }

    override fun onStop() {
        // keep the player running in the background; off screen and not broadcasting, stop
        // reacting to stalls (the GL sink can't render without a surface, so the feed
        // reconnects to live on return). A broadcast keeps stall detection on: see Tick.
        super.onStop()
        foreground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tick)
        unregisterReceiver(stopFromNotification)
        applyRestream(restream.onShutdown())
        teardownPlayer()
        link.shutdown()
    }

    // driving the machines
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
                    isRestreaming = restream.isArmed,
                    nowMs = now(),
                )
            )
        )
    }

    private fun disconnect() {
        applyRestream(restream.onSessionLeft())
        apply(machine.onDisconnect(link.goggleNetwork() != null, now()))
    }

    /**
     * Carry out the connection machine's effects, then bring the views up to date.
     */
    private fun apply(step: ConnectionMachine.Step) {
        var playerFailure: String? = null
        step.effects.forEach { effect ->
            when (effect) {
                is ConnectionMachine.Effect.Log -> {
                    Diagnostics.log(effect.tag, effect.message)
                }
                ConnectionMachine.Effect.CreatePlayer -> {
                    if (playerFailure == null) {
                        val hadPlayer = player != null
                        if (ensurePlayer() == null) {
                            playerFailure = lastPlayerFailure
                        } else if (!hadPlayer) {
                            applyRestream(restream.onPlayerCreated(armedDestination()))
                        }
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
        render()
    }

    /**
     * Carry out the restream machine's effects, then bring the views up to date.
     */
    private fun applyRestream(step: RestreamMachine.Step) {
        step.effects.forEach { effect ->
            when (effect) {
                is RestreamMachine.Effect.Log -> {
                    Diagnostics.log(effect.tag, effect.message)
                }
                is RestreamMachine.Effect.ArmEgress -> {
                    player?.setRestream(effect.url)
                }
                RestreamMachine.Effect.DisarmEgress -> {
                    player?.setRestream(null)
                }
                is RestreamMachine.Effect.StartKeepAlive -> {
                    RestreamService.start(this, effect.label)
                }
                RestreamMachine.Effect.StopKeepAlive -> {
                    RestreamService.stop(this)
                }
                RestreamMachine.Effect.RequestNotificationPermission -> {
                    requestNotificationPermission()
                }
                is RestreamMachine.Effect.Toast -> {
                    toast(effect.textResource)
                }
                is RestreamMachine.Effect.ToastDetail -> {
                    toast(effect.text)
                }
            }
        }
        render()
    }

    private fun now(): Long {
        return SystemClock.elapsedRealtime()
    }

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
            created.onCodec = { codec ->
                runOnUiThread { applyRestream(restream.onCodecNegotiated(codec)) }
            }
            created.onRestreamFailed = { reason ->
                runOnUiThread { applyRestream(restream.onEgressFailed(reason)) }
            }
            created.onRestreamLive = { live ->
                runOnUiThread { applyRestream(restream.onEgressLive(live)) }
            }
            player = created
            created
        } catch (failure: Throwable) {
            lastPlayerFailure = failure.message ?: failure.javaClass.simpleName
            null
        }
    }

    private fun teardownPlayer() {
        if (player == null) {
            return
        }
        player?.release()
        player = null
        applyRestream(restream.onPlayerGone())
    }

    /**
     * Build the screen both machines currently describe and write it, unless the views already
     * show it.
     */
    private fun render() {
        if (isDestroyed) {
            // the shutdown effects run after onDestroy, where there are no views left to write
            return
        }

        val state = machine.state
        if (loggedState != null && loggedState != state) {
            Diagnostics.log("state", "$loggedState -> $state")
        }
        loggedState = state

        val next = screenFor(
            state, machine.failureReason, restream.state, restream.armedLabel, areControlsRevealed
        )
        if (next.chrome != Chrome.IMMERSIVE) {
            // the controls belong to the immersive chrome, and their timer leaves with them
            ticker.removeCallbacks(hideControls)
            areControlsRevealed = false
        }

        if (next == rendered) {
            return
        }
        rendered = next
        applyScreen(next)
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

        val controls = screen.controls
        fullscreenBack.visibility = visibilityOf(controls.isBackVisible)

        val toggle = controls.toggle
        streamToggle.visibility = visibilityOf(toggle != null)
        if (toggle != null) {
            streamToggle.setImageResource(toggle.iconResource)
            streamToggle.contentDescription = getString(toggle.descriptionResource)
        }

        val badge = controls.badge
        streamBadge.visibility = visibilityOf(badge != null)
        if (badge != null) {
            streamBadge.text = getString(badge.textResource, badge.label)
            streamBadge.setCompoundDrawablesRelativeWithIntrinsicBounds(badge.iconResource, 0, 0, 0)
        }

        // show the video only while actually playing, so no frozen last frame leaks through
        player?.setVideoVisible(status == null)

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
     * The destination the broadcast was armed to, as the store holds it now. Looked up by id
     * rather than taken from the active selection, so an edit to it lands on the next re-arm
     * while changing which destination is active leaves a broadcast in flight where it is.
     */
    private fun armedDestination(): Destination? {
        return destinations.read().byId(restream.armedDestinationId)
    }

    /**
     * Read at the moment of arming rather than cached, so editing the destination in Settings and
     * coming back takes effect without restarting the session.
     */
    private fun toggleStreaming() {
        applyRestream(restream.onToggleTapped(destinations.read().active))
    }

    /**
     * Ask for the notification permission the first time a broadcast is armed.
     *
     * The foreground service runs either way; without the permission its notification is simply
     * not shown, which loses the only indication the app is still broadcasting once it is off
     * screen. Asking at the moment of arming is what makes the request make sense to the user.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun revealControls() {
        if (rendered?.chrome != Chrome.IMMERSIVE) {
            return
        }
        areControlsRevealed = true
        render()
        ticker.removeCallbacks(hideControls)
        ticker.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
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
