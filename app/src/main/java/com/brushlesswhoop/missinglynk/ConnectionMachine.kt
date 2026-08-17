package com.brushlesswhoop.missinglynk

/**
 * The connection state machine. It owns the states and every timing decision; the Activity
 * owns the player, the link and the views, and applies the effects returned from each event.
 * Nothing here touches the Android framework, so the whole transition table is exercisable
 * as a JVM test.
 *
 * Time is passed in as a monotonic millisecond reading (`SystemClock.elapsedRealtime` in
 * production) rather than read here, so tests drive the clock.
 *
 * The two probes are asynchronous: the machine asks for one with an effect and the caller
 * feeds the answer back through [onProbeResult] or [onSessionProbeResult]. Both answers
 * re-check the state they were issued from, because the state can move while a probe is in
 * flight.
 */
class ConnectionMachine {

    enum class State { SEARCHING, STREAM_DOWN, READY, CONNECTING, NO_QUAD, PLAYING, RECONNECTING }

    sealed interface Effect {
        /** Start an RTSP port probe; answer through [onProbeResult]. */
        data object Probe : Effect

        /** Probe issued from inside a session that has no media; answer through [onSessionProbeResult]. */
        data object SessionProbe : Effect

        data object CreatePlayer : Effect
        data object TeardownPlayer : Effect

        /** (Re)point the player at the stream URL and start it. */
        data object StartStream : Effect

        data class Log(val tag: String, val msg: String) : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    /** One sample of everything the machine cannot observe for itself. */
    data class Tick(
        val hasNetwork: Boolean,
        /** Frames counted by the sink, or null when no player exists. */
        val frameCount: Int?,
        /** False while the activity is stopped: stall detection is suspended, the feed is not. */
        val foreground: Boolean,
        val nowMs: Long,
    )

    var state: State = State.SEARCHING
        private set

    // Set when the user leaves a session, so a probe parks in READY instead of reconnecting
    // straight away. Cleared when the goggle goes away and on a Connect tap.
    private var userDisconnected = false
    private var playerError = false

    // frame-flow tracking
    private var lastFrame = 0
    private var lastFrameAtMs = 0L
    private var sessionStartMs = 0L
    private var lastPlayMs = 0L
    private var lastSessionProbeMs = 0L

    fun onTick(t: Tick): Step {
        if (!t.hasNetwork) {
            userDisconnected = false
            return if (state == State.SEARCHING) {
                Step(state)
            } else {
                Step(State.SEARCHING, listOf(Effect.TeardownPlayer)).also { state = it.state }
            }
        }

        return when (state) {
            State.SEARCHING, State.STREAM_DOWN -> Step(state, listOf(Effect.Probe))
            State.READY -> Step(state)  // waiting for the Connect tap
            State.CONNECTING, State.NO_QUAD, State.RECONNECTING -> stepConnecting(t)
            State.PLAYING -> stepPlaying(t)
        }
    }

    /** Success connects straight away, unless the user disconnected on purpose. */
    fun onProbeResult(up: Boolean, nowMs: Long): Step {
        if (state != State.SEARCHING && state != State.STREAM_DOWN) return Step(state)
        return when {
            !up -> Step(State.STREAM_DOWN).also { state = it.state }
            userDisconnected -> Step(State.READY).also { state = it.state }
            else -> connect(nowMs, Effect.Log("conn", "RTSP up, connecting"))
        }
    }

    /**
     * A closed port during a session means the server went away (goggle reboot, stream
     * switched off), so hand back to the auto-connect path. A probe takes up to its timeout,
     * so media may have started flowing in the meantime.
     */
    fun onSessionProbeResult(up: Boolean, frameCount: Int, nowMs: Long): Step {
        if (up || state !in SESSION_STATES || frameCount > 0) return Step(state)
        state = State.STREAM_DOWN
        return Step(
            state,
            listOf(Effect.Log("conn", "RTSP port closed, waiting for the stream"), Effect.TeardownPlayer)
        )
    }

    fun onConnectTapped(nowMs: Long): Step = connect(nowMs)

    fun onDisconnect(hasNetwork: Boolean, nowMs: Long): Step {
        userDisconnected = hasNetwork
        state = if (hasNetwork) State.READY else State.SEARCHING
        return Step(state, listOf(Effect.TeardownPlayer))
    }

    /** The player reported a state; ERROR and ENDED both mean the attempt failed. */
    fun onPlayerState(s: PlayerState) {
        if (s == PlayerState.ERROR || s == PlayerState.ENDED) playerError = true
    }

    /**
     * Waiting for media: promote to PLAYING when frames arrive; rebuild only once the current
     * attempt has actually failed (rebuilding on a timer interrupts rtspsrc mid-handshake and
     * yields "can't get sdp"), with a long fallback for an attempt that simply hangs.
     */
    private fun stepConnecting(t: Tick): Step {
        val frames = t.frameCount ?: return Step(state)
        if (frames > 0) {
            resetFrameTracking(frames, t.nowMs)
            state = State.PLAYING
            return Step(state)
        }

        val effects = mutableListOf<Effect>()

        if (t.nowMs - lastSessionProbeMs >= SESSION_PROBE_MS) {
            lastSessionProbeMs = t.nowMs
            effects += Effect.SessionProbe
        }

        val sinceRebuild = t.nowMs - lastPlayMs
        if (playerError && sinceRebuild >= REBUILD_GAP_MS) {
            playerError = false
            effects += Effect.Log("conn", "rebuild after player error")
            effects += startStream(t.nowMs)
        } else if (sinceRebuild >= STUCK_MS) {
            effects += Effect.Log("conn", "rebuild after ${STUCK_MS}ms stuck (no frames)")
            effects += startStream(t.nowMs)
        }

        if (state == State.CONNECTING && t.nowMs - sessionStartMs > NO_VIDEO_MS) {
            state = State.NO_QUAD
        }
        return Step(state, effects)
    }

    /** Playing: watch for a frozen stream and flip to reconnecting. */
    private fun stepPlaying(t: Tick): Step {
        val frames = t.frameCount ?: return Step(state)
        if (frames != lastFrame) {
            lastFrame = frames
            lastFrameAtMs = t.nowMs
            return Step(state)
        }
        if (!t.foreground || t.nowMs - lastFrameAtMs <= STALL_MS) return Step(state)

        state = State.RECONNECTING
        return Step(
            state,
            listOf(
                Effect.Log("play", "stalled ${STALL_MS}ms (last frame=$frames) -> reconnecting"),
                startStream(t.nowMs),
            )
        )
    }

    private fun connect(nowMs: Long, vararg leading: Effect): Step {
        userDisconnected = false
        sessionStartMs = nowMs
        lastSessionProbeMs = nowMs
        state = State.CONNECTING
        return Step(state, leading.toList() + listOf(Effect.CreatePlayer, startStream(nowMs)))
    }

    /** Baseline the stall detector: no frames counted since right now. */
    private fun startStream(nowMs: Long): Effect {
        lastPlayMs = resetFrameTracking(0, nowMs)
        return Effect.StartStream
    }

    private fun resetFrameTracking(frameCount: Int, nowMs: Long): Long {
        lastFrame = frameCount
        lastFrameAtMs = nowMs
        return nowMs
    }

    internal companion object {
        /** States with a live player behind them. */
        val SESSION_STATES = setOf(State.CONNECTING, State.NO_QUAD, State.PLAYING, State.RECONNECTING)

        const val NO_VIDEO_MS = 7000L      // CONNECTING with no frames -> NO_QUAD
        // Min gap before rebuilding after a failed attempt (debounce; do not interrupt an
        // in-progress rtspsrc handshake), and a long fallback if an attempt just hangs.
        const val REBUILD_GAP_MS = 4000L
        const val STUCK_MS = 20000L
        // PLAYING with no new frames -> RECONNECTING. Kept high: FPV feeds blip for a few
        // seconds (Tx-side link resets); only a real drop (battery swap) should reconnect.
        const val STALL_MS = 8000L
        // How often a session with no media re-checks that the RTSP port is still open.
        const val SESSION_PROBE_MS = 5000L
    }
}
