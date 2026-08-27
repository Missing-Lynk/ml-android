package at.websium.ml

import androidx.annotation.StringRes

/**
 * The restream state machine. It owns whether a broadcast is armed, whether the egress is
 * carrying, and the codec the destination is judged against; the Activity owns the player, the
 * keep-alive service and the views, and applies the effects returned from each event. Nothing
 * here touches the Android framework, so the whole table is exercisable as a JVM test.
 *
 * It is a peer of [ConnectionMachine] rather than part of it: a broadcast is armed across the
 * connection states rather than inside one of them, and survives the player being torn down and
 * rebuilt underneath it.
 *
 * The destination is passed in on each arming event rather than held from an earlier one, so
 * editing it in Settings and coming back takes effect without restarting the session. Its URL
 * carries a stream key, so everything a person or a log sees names it by its label instead.
 */
class RestreamMachine {

    enum class State {
        /** nothing armed; the egress does not exist */
        OFF,

        /**
         * Armed and not carrying. Either the egress is between reconnect attempts, or there is
         * no player under it to feed one.
         */
        ARMED,

        /** armed and carrying to the destination */
        CARRYING,
    }

    sealed interface Effect {
        /**
         * Point the player's egress at [url], which carries the stream key.
         */
        data class ArmEgress(val url: String) : Effect

        data object DisarmEgress : Effect

        /**
         * Bring the keep-alive service up for a broadcast to the destination named [label], which
         * the service shows in a notification.
         */
        data class StartKeepAlive(val label: String) : Effect

        data object StopKeepAlive : Effect

        /**
         * Ask for the notification permission, which is what makes the keep-alive service's
         * notification visible.
         */
        data object RequestNotificationPermission : Effect

        data class Toast(@param:StringRes val textResource: Int) : Effect

        data class Log(val tag: String, val message: String) : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    var state: State = State.OFF
        private set

    /** whether a broadcast is armed, which is what keeps stall detection on off screen */
    val isArmed: Boolean
        get() = state != State.OFF

    /** the destination the egress is armed to */
    private var armed: Destination? = null

    /**
     * Which destination the broadcast was armed to, so a re-arm follows the one in flight rather
     * than whichever is active by then. Null when nothing is armed.
     */
    val armedDestinationId: String?
        get() = armed?.id

    /** what the broadcast is called on screen, null when nothing is armed */
    val armedLabel: String?
        get() = armed?.label

    /** the codec the SDP named, which decides whether a destination will take the stream */
    private var negotiatedCodec: String? = null

    /**
     * Whether the egress is carrying the microphone. Reported by the player rather than read from
     * the setting, because a microphone that will not open falls back to silence and the badge
     * has to name what is being sent rather than what was asked for.
     */
    var isUsingMicrophone: Boolean = false
        private set

    /** what the goggle is sending, "H264" or "H265", null until a session has negotiated one */
    val streamCodec: String?
        get() = negotiatedCodec

    /**
     * The user tapped the toggle. [selected] is the active destination, or null when none is
     * saved. Its URL is judged here rather than by the caller.
     */
    fun onToggleTapped(selected: Destination?): Step {
        if (isArmed) {
            return disarm(
                Effect.Log("stream", "stopped by the user"),
                Effect.Toast(R.string.stream_stopped),
            )
        }

        if (selected == null || !isRestreamUrl(selected.url)) {
            return Step(state, listOf(Effect.Toast(R.string.stream_needs_destination)))
        }

        val target = selected.copy(url = selected.url.trim())
        val codec = negotiatedCodec
        if (codec != null && !isCodecAccepted(target.url, codec)) {
            return Step(
                state,
                listOf(
                    Effect.Log("stream", "refused: $codec to ${target.label}"),
                    Effect.Toast(R.string.stream_codec_rejected),
                ),
            )
        }

        armed = target
        state = State.ARMED
        return Step(
            state,
            listOf(
                Effect.RequestNotificationPermission,
                Effect.ArmEgress(target.url),
                Effect.StartKeepAlive(target.label),
                Effect.Log("stream", "started to ${target.label}"),
                Effect.Toast(R.string.stream_started),
            ),
        )
    }

    /**
     * A player was built. The destination is armed on the player rather than held natively
     * across instances, so an armed broadcast is armed again here: losing the goggle and getting
     * it back carries the broadcast with it.
     *
     * [current] is the armed destination as the store holds it now, looked up by
     * [armedDestinationId], so an edit to it lands while a change of which destination is active
     * does not move a broadcast in flight. A destination deleted in the meantime arrives as null
     * and ends the broadcast, because there is nothing left to arm.
     */
    fun onPlayerCreated(current: Destination?): Step {
        if (!isArmed) {
            return Step(state)
        }

        if (current == null || !isRestreamUrl(current.url)) {
            return disarm(Effect.Log("stream", "the destination is gone; broadcast ended"))
        }

        val target = current.copy(url = current.url.trim())
        armed = target
        return Step(
            state,
            listOf(
                Effect.ArmEgress(target.url),
                Effect.Log("stream", "re-armed ${target.label}"),
            ),
        )
    }

    /**
     * Stop was tapped in the keep-alive notification. Distinct from the toggle because it can
     * arrive from a notification the system is still showing after a broadcast has already
     * ended, and stopping twice has to mean nothing rather than arming.
     */
    fun onStopRequested(): Step {
        if (!isArmed) {
            return Step(state)
        }

        return disarm(
            Effect.Log("stream", "stopped from the notification"),
            Effect.Toast(R.string.stream_stopped),
        )
    }

    /**
     * The destination the broadcast is armed to was deleted. Nothing is left to broadcast to, so
     * the broadcast ends rather than carrying on to a destination the user has thrown away.
     */
    fun onDestinationDeleted(): Step {
        if (!isArmed) {
            return Step(state)
        }

        return disarm(
            Effect.Log("stream", "the destination was deleted; broadcast ended"),
            Effect.Toast(R.string.stream_stopped),
        )
    }

    /**
     * The player was released. The egress went with it, so an armed broadcast is no longer
     * carrying and waits for the next player. The codec belongs to the released player's SDP and
     * is forgotten with it.
     */
    fun onPlayerGone(): Step {
        negotiatedCodec = null
        if (state == State.CARRYING) {
            state = State.ARMED
        }

        return Step(state)
    }

    /**
     * The egress settled on an audio source.
     */
    fun onAudioSource(usingMicrophone: Boolean): Step {
        isUsingMicrophone = usingMicrophone
        return Step(state)
    }

    /**
     * The SDP named the codec, "H264" or "H265".
     */
    fun onCodecNegotiated(codec: String): Step {
        negotiatedCodec = codec
        return Step(state)
    }

    /**
     * Whether the egress is carrying to its destination, as the player reports it.
     */
    fun onEgressLive(live: Boolean): Step {
        if (!isArmed) {
            return Step(state)
        }

        state = if (live) State.CARRYING else State.ARMED
        return Step(state)
    }

    /**
     * The egress gave up. The picture is unaffected and the player reconnects on its own, so
     * this reports and leaves the broadcast armed.
     *
     * The toast carries the cause [egressFailureText] reads out of the reason, since a rejected
     * stream key is something the user can fix and GStreamer's own wording says only that a
     * socket failed. The log keeps the text as it arrived, because that is the half a bug report
     * needs and the only place it is worth reading.
     */
    fun onEgressFailed(reason: String): Step {
        return Step(
            state,
            listOf(
                Effect.Log("stream", "restream failed: $reason"),
                Effect.Toast(egressFailureText(reason)),
            ),
        )
    }

    /**
     * The user left the session. Backing out of a session says the flight is over, so the
     * broadcast ends with it rather than waiting armed for the next Connect.
     */
    fun onSessionLeft(): Step {
        if (!isArmed) {
            return Step(state)
        }

        return disarm(
            Effect.Log("stream", "stopped by leaving the session"),
            Effect.Toast(R.string.stream_stopped),
        )
    }

    /**
     * The app is going away. The keep-alive service exists to hold this session's broadcast up
     * and the session ends here, so it is stopped whether or not anything was armed.
     */
    fun onShutdown(): Step {
        armed = null
        negotiatedCodec = null
        state = State.OFF

        return Step(state, listOf(Effect.StopKeepAlive))
    }

    private fun disarm(vararg leadingEffects: Effect): Step {
        armed = null
        isUsingMicrophone = false
        state = State.OFF
        return Step(
            state,
            leadingEffects.toList() + listOf(Effect.DisarmEgress, Effect.StopKeepAlive),
        )
    }
}
