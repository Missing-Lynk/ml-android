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
        SEARCHING, STREAM_DOWN, READY, CONNECTING, NO_AIR_UNIT, PLAYING, RECONNECTING,

        /**
         * The feed stopped while the RTSP session stayed open, which is what a battery swap
         * looks like: the goggle is still serving, the air unit is not sending. The player and
         * its pipeline are kept, so the decoder holds the last picture and the feed resumes
         * without a rebuild.
         */
        FEED_LOST,

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

        /**
         * Whether a broadcast is armed. A stalled feed off screen is nothing to act on while the
         * only consumer is a surface that is gone, and everything to act on while it is also
         * being sent somewhere: a broadcast rides on the feed being reconnected.
         */
        val isRestreaming: Boolean = false,
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

    /**
     * Why the last attempt failed, shown under the status line while the next one runs. Cleared
     * when a session starts and when frames arrive.
     */
    var failureReason: String? = null
        private set

    private var lastFrameCount = 0
    private var lastFrameAtMs = 0L
    private var sessionStartMs = 0L
    private var lastStartStreamMs = 0L
    private var lastSessionProbeMs = 0L

    /** the last answer a session probe gave, which separates a lost feed from a lost goggle */
    private var isPortOpen = true

    /** the last tick's broadcast state, so a probe answer knows whether a player may be released */
    private var isRestreaming = false

    /** when the feed was lost, for the bound on waiting it out */
    private var feedLostAtMs = 0L

    fun onTick(tick: Tick): Step {
        isRestreaming = tick.isRestreaming
        if (state == State.UNAVAILABLE) {
            return Step(state)
        }

        if (!tick.hasNetwork) {
            userDisconnected = false
            if (state == State.SEARCHING) {
                return Step(state)
            }

            state = State.SEARCHING
            return Step(state, releasePlayer())
        }

        return when (state) {
            State.SEARCHING, State.STREAM_DOWN -> Step(state, listOf(Effect.Probe))
            // waiting for the Connect tap
            State.READY -> Step(state)
            State.CONNECTING, State.NO_AIR_UNIT, State.RECONNECTING -> stepConnecting(tick)
            State.PLAYING -> stepPlaying(tick)
            State.FEED_LOST -> stepFeedLost(tick)
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
        isPortOpen = portOpen
        if (portOpen || state !in SESSION_STATES) {
            return Step(state)
        }

        /*
         * A frame count carries the answer only while a session is still trying to get its
         * first: FEED_LOST is reached with frames already counted, and they say nothing about
         * whether the server is still there.
         */
        if (frameCount > 0 && state != State.FEED_LOST) {
            return Step(state)
        }

        state = State.STREAM_DOWN
        return Step(state, listOf(Effect.Log("conn", "RTSP port closed, waiting for the stream")) +
            releasePlayer())
    }

    /**
     * Releasing the player takes the egress pipeline with it, because the egress runs on the
     * player's own loop. A broadcast therefore keeps its player: the picture is gone either way,
     * and what is being held open is the RTMP session and the audio track carrying it.
     */
    private fun releasePlayer(): List<Effect> {
        return if (isRestreaming) emptyList() else listOf(Effect.TeardownPlayer)
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
     * A failure and an end of stream both mean the current attempt is over. A failure also
     * carries what went wrong.
     */
    fun onPlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Failed -> {
                playerFailed = true
                failureReason = event.reason
            }
            PlayerEvent.Ended -> {
                playerFailed = true
            }
            PlayerEvent.Connecting, PlayerEvent.Playing -> {
            }
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
            failureReason = null
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
            state = State.NO_AIR_UNIT
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

        /*
         * Two thresholds, because the two outcomes cost different things. Saying the feed has
         * stopped is cosmetic and undone by the next frame, so it is said as soon as the picture
         * is visibly frozen; rebuilding throws a session away and keeps the higher bar the RF
         * link's routine blips were the reason for.
         */
        val watchesStalls = tick.foreground || tick.isRestreaming
        val quietMs = tick.nowMs - lastFrameAtMs
        if (!watchesStalls || quietMs <= (if (isPortOpen) FEED_QUIET_MS else STALL_MS)) {
            return Step(state)
        }

        /*
         * A session whose port is still open has not gone anywhere; only the pictures stopped.
         * Rebuilding would throw away a working RTSP session and the decoder's last frame with
         * it, and gains nothing, because there is nothing to reconnect to.
         */
        if (isPortOpen) {
            state = State.FEED_LOST
            feedLostAtMs = tick.nowMs
            return Step(
                state,
                listOf(Effect.Log("play", "feed stopped after $frames frames; session still open"))
            )
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

    /**
     * Feed lost: hold the picture and wait, re-checking that the session is still there.
     *
     * Returning to PLAYING costs no rebuild, which is the point: a battery swap resumes at the
     * goggle's next IRAP. The bound exists because a session can answer on the port and be dead
     * in every way that matters, and a rebuild is the only thing that recovers that.
     */
    private fun stepFeedLost(tick: Tick): Step {
        val frames = tick.frameCount
        if (frames == null) {
            return Step(state)
        }

        if (frames != lastFrameCount) {
            resetFrameTracking(frames, tick.nowMs)
            state = State.PLAYING
            return Step(state, listOf(Effect.Log("play", "feed resumed")))
        }

        val effects = mutableListOf<Effect>()
        if (tick.nowMs - lastSessionProbeMs >= SESSION_PROBE_MS) {
            lastSessionProbeMs = tick.nowMs
            effects += Effect.SessionProbe
        }

        if (tick.nowMs - feedLostAtMs >= FEED_LOST_MS) {
            state = State.RECONNECTING
            effects += Effect.Log("play", "feed gone for ${FEED_LOST_MS}ms -> reconnecting")
            effects += startStream(tick.nowMs)
        }

        return Step(state, effects)
    }

    private fun connect(nowMs: Long, vararg leadingEffects: Effect): Step {
        userDisconnected = false
        failureReason = null
        sessionStartMs = nowMs
        lastSessionProbeMs = nowMs
        // a session is only ever entered from a probe that answered
        isPortOpen = true
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
            setOf(State.CONNECTING, State.NO_AIR_UNIT, State.PLAYING, State.RECONNECTING,
                  State.FEED_LOST)

        /** CONNECTING with no frames for this long blames the air unit */
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

        /**
         * PLAYING with no new frames for this long, while the session is still answering, says
         * so on screen. Low because nothing is torn down: the picture is already frozen by the
         * time it shows, and the next frame clears it.
         */
        const val FEED_QUIET_MS = 2000L

        /** how often a session with no media re-checks that the RTSP port is still open */
        const val SESSION_PROBE_MS = 5000L

        /**
         * Longest a lost feed is waited out before the session is rebuilt anyway. Past the 40 s
         * worst case of a battery swap, which is the gap this state exists to hold the picture
         * through.
         */
        const val FEED_LOST_MS = 60000L
    }
}
