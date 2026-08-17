package at.websium.ml

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

    enum class State {
        SEARCHING, STREAM_DOWN, READY, CONNECTING, NO_QUAD, PLAYING, RECONNECTING,

        /**
         * The player could not be built on this device. Terminal for the process: a native
         * library that failed to load will not load on a later attempt.
         */
        UNAVAILABLE,
    }

    sealed interface Effect {
        /**
         * Start an RTSP port probe; answer through [onProbeResult].
         */
        data object Probe : Effect

        /**
         * Probe from inside a session that has no media; answer through [onSessionProbeResult].
         */
        data object SessionProbe : Effect

        data object CreatePlayer : Effect
        data object TeardownPlayer : Effect

        /**
         * Point the player at the stream URL and start it.
         */
        data object StartStream : Effect

        data class Log(val tag: String, val message: String) : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    /**
     * One sample of everything the machine cannot observe for itself.
     */
    data class Tick(
        val hasNetwork: Boolean,
        /** frames counted by the sink, or null when no player exists */
        val frameCount: Int?,
        /** false while the activity is stopped: stall detection pauses, the feed does not */
        val foreground: Boolean,
        val nowMs: Long,
    )

    var state: State = State.SEARCHING
        private set

    /**
     * Set when the user leaves a session, so a probe parks in READY instead of reconnecting
     * straight away. Cleared when the goggle goes away and on a Connect tap.
     */
    private var userDisconnected = false
    private var playerFailed = false

    private var lastFrameCount = 0
    private var lastFrameAtMs = 0L
    private var sessionStartMs = 0L
    private var lastStartStreamMs = 0L
    private var lastSessionProbeMs = 0L

    fun onTick(tick: Tick): Step {
        if (state == State.UNAVAILABLE) {
            return Step(state)
        }

        if (!tick.hasNetwork) {
            userDisconnected = false
            if (state == State.SEARCHING) {
                return Step(state)
            }
            state = State.SEARCHING
            return Step(state, listOf(Effect.TeardownPlayer))
        }

        return when (state) {
            State.SEARCHING, State.STREAM_DOWN -> Step(state, listOf(Effect.Probe))
            // waiting for the Connect tap
            State.READY -> Step(state)
            State.CONNECTING, State.NO_QUAD, State.RECONNECTING -> stepConnecting(tick)
            State.PLAYING -> stepPlaying(tick)
            // returned above, before the network check
            State.UNAVAILABLE -> Step(state)
        }
    }

    /**
     * Success connects straight away, unless the user disconnected on purpose.
     */
    fun onProbeResult(portOpen: Boolean, nowMs: Long): Step {
        if (state != State.SEARCHING && state != State.STREAM_DOWN) {
            return Step(state)
        }
        if (!portOpen) {
            state = State.STREAM_DOWN
            return Step(state)
        }
        if (userDisconnected) {
            state = State.READY
            return Step(state)
        }
        return connect(nowMs, Effect.Log("conn", "RTSP up, connecting"))
    }

    /**
     * A closed port during a session means the server went away (goggle reboot, stream
     * switched off), so hand back to the auto-connect path. A probe takes up to its timeout,
     * so media may have started flowing in the meantime.
     */
    fun onSessionProbeResult(portOpen: Boolean, frameCount: Int, nowMs: Long): Step {
        if (portOpen || state !in SESSION_STATES || frameCount > 0) {
            return Step(state)
        }
        state = State.STREAM_DOWN
        return Step(
            state,
            listOf(
                Effect.Log("conn", "RTSP port closed, waiting for the stream"),
                Effect.TeardownPlayer,
            )
        )
    }

    fun onConnectTapped(nowMs: Long): Step {
        if (state == State.UNAVAILABLE) {
            return Step(state)
        }
        return connect(nowMs)
    }

    /**
     * The player could not be constructed: GStreamer failed to initialise, or its native
     * libraries are missing for this device's ABI. Nothing the app does later changes that,
     * so it stops here with something on screen instead of crashing out of
     * [Effect.CreatePlayer].
     */
    fun onPlayerUnavailable(detail: String?): Step {
        state = State.UNAVAILABLE
        return Step(state, listOf(Effect.Log("player", "unavailable: ${detail ?: "unknown"}")))
    }

    fun onDisconnect(hasNetwork: Boolean, nowMs: Long): Step {
        if (state == State.UNAVAILABLE) {
            return Step(state)
        }
        userDisconnected = hasNetwork
        if (hasNetwork) {
            state = State.READY
        } else {
            state = State.SEARCHING
        }
        return Step(state, listOf(Effect.TeardownPlayer))
    }

    /**
     * ERROR and ENDED both mean the current attempt failed.
     */
    fun onPlayerState(playerState: PlayerState) {
        if (playerState == PlayerState.ERROR || playerState == PlayerState.ENDED) {
            playerFailed = true
        }
    }

    /**
     * Waiting for media: promote to PLAYING when frames arrive; rebuild only once the current
     * attempt has actually failed (rebuilding on a timer interrupts rtspsrc mid-handshake and
     * yields "can't get sdp"), with a long fallback for an attempt that simply hangs.
     */
    private fun stepConnecting(tick: Tick): Step {
        val frames = tick.frameCount
        if (frames == null) {
            return Step(state)
        }
        if (frames > 0) {
            resetFrameTracking(frames, tick.nowMs)
            state = State.PLAYING
            return Step(state)
        }

        val effects = mutableListOf<Effect>()

        if (tick.nowMs - lastSessionProbeMs >= SESSION_PROBE_MS) {
            lastSessionProbeMs = tick.nowMs
            effects += Effect.SessionProbe
        }

        val sinceStartStream = tick.nowMs - lastStartStreamMs
        if (playerFailed && sinceStartStream >= REBUILD_GAP_MS) {
            playerFailed = false
            effects += Effect.Log("conn", "rebuild after player error")
            effects += startStream(tick.nowMs)
        } else if (sinceStartStream >= STUCK_MS) {
            effects += Effect.Log("conn", "rebuild after ${STUCK_MS}ms stuck (no frames)")
            effects += startStream(tick.nowMs)
        }

        if (state == State.CONNECTING && tick.nowMs - sessionStartMs > NO_VIDEO_MS) {
            state = State.NO_QUAD
        }
        return Step(state, effects)
    }

    /**
     * Playing: watch for a frozen stream and flip to reconnecting.
     */
    private fun stepPlaying(tick: Tick): Step {
        val frames = tick.frameCount
        if (frames == null) {
            return Step(state)
        }
        if (frames != lastFrameCount) {
            lastFrameCount = frames
            lastFrameAtMs = tick.nowMs
            return Step(state)
        }
        if (!tick.foreground || tick.nowMs - lastFrameAtMs <= STALL_MS) {
            return Step(state)
        }

        state = State.RECONNECTING
        return Step(
            state,
            listOf(
                Effect.Log("play", "stalled ${STALL_MS}ms (last frame=$frames) -> reconnecting"),
                startStream(tick.nowMs),
            )
        )
    }

    private fun connect(nowMs: Long, vararg leadingEffects: Effect): Step {
        userDisconnected = false
        sessionStartMs = nowMs
        lastSessionProbeMs = nowMs
        state = State.CONNECTING
        return Step(
            state,
            leadingEffects.toList() + listOf(Effect.CreatePlayer, startStream(nowMs)),
        )
    }

    /**
     * Baseline the stall detector: no frames counted since right now.
     */
    private fun startStream(nowMs: Long): Effect {
        lastStartStreamMs = resetFrameTracking(0, nowMs)
        return Effect.StartStream
    }

    private fun resetFrameTracking(frameCount: Int, nowMs: Long): Long {
        lastFrameCount = frameCount
        lastFrameAtMs = nowMs
        return nowMs
    }

    internal companion object {
        /**
         * States with a live player behind them.
         */
        val SESSION_STATES =
            setOf(State.CONNECTING, State.NO_QUAD, State.PLAYING, State.RECONNECTING)

        /** CONNECTING with no frames for this long blames the quad */
        const val NO_VIDEO_MS = 7000L

        /**
         * Minimum gap before rebuilding after a failed attempt, so a rebuild cannot interrupt
         * an rtspsrc handshake still in progress.
         */
        const val REBUILD_GAP_MS = 4000L

        /** fallback for an attempt that neither fails nor delivers */
        const val STUCK_MS = 20000L

        /**
         * PLAYING with no new frames for this long reconnects. Kept high: FPV feeds blip for a
         * few seconds on Tx-side link resets, and only a real drop should reconnect.
         */
        const val STALL_MS = 8000L

        /** how often a session with no media re-checks that the RTSP port is still open */
        const val SESSION_PROBE_MS = 5000L
    }
}
