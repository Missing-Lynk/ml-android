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
 * editing it in Settings and coming back takes effect without restarting the session. It carries
 * a stream key on the end, so it reaches an effect only as [redactStreamKey] leaves it.
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
         * Bring the keep-alive service up for a broadcast to [destination], which is already
         * redacted: the service shows it in a notification.
         */
        data class StartKeepAlive(val destination: String) : Effect

        data object StopKeepAlive : Effect

        /**
         * Ask for the notification permission, which is what makes the keep-alive service's
         * notification visible.
         */
        data object RequestNotificationPermission : Effect

        data class Toast(@StringRes val textResource: Int) : Effect

        /** a message the app did not write, such as the reason the egress gave up */
        data class ToastDetail(val text: String) : Effect

        data class Log(val tag: String, val message: String) : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    var state: State = State.OFF
        private set

    /** whether a broadcast is armed, which is what keeps stall detection on off screen */
    val isArmed: Boolean
        get() = state != State.OFF

    /** the destination the egress is armed to, key included */
    private var destination: String? = null

    /** the codec the SDP named, which decides whether a destination will take the stream */
    private var negotiatedCodec: String? = null

    /**
     * The user tapped the toggle. [configured] is the destination as Settings currently holds
     * it, unvalidated.
     */
    fun onToggleTapped(configured: String?): Step {
        if (isArmed) {
            return disarm(
                Effect.Log("stream", "stopped by the user"),
                Effect.Toast(R.string.stream_stopped),
            )
        }

        if (!isRestreamUrl(configured)) {
            return Step(state, listOf(Effect.Toast(R.string.stream_needs_destination)))
        }

        val target = configured!!.trim()
        val codec = negotiatedCodec
        if (codec != null && !isCodecAccepted(target, codec)) {
            return Step(
                state,
                listOf(
                    Effect.Log("stream", "refused: $codec to ${redactStreamKey(target)}"),
                    Effect.Toast(R.string.stream_codec_rejected),
                ),
            )
        }

        destination = target
        state = State.ARMED
        return Step(
            state,
            listOf(
                Effect.RequestNotificationPermission,
                Effect.ArmEgress(target),
                Effect.StartKeepAlive(redactStreamKey(target)),
                Effect.Log("stream", "started to ${redactStreamKey(target)}"),
                Effect.Toast(R.string.stream_started),
            ),
        )
    }

    /**
     * A player was built. The destination is armed on the player rather than held natively
     * across instances, so an armed broadcast is armed again here: losing the goggle and getting
     * it back carries the broadcast with it.
     *
     * A destination that has gone from Settings in the meantime ends the broadcast, because
     * there is nothing left to arm.
     */
    fun onPlayerCreated(configured: String?): Step {
        if (!isArmed) {
            return Step(state)
        }

        if (!isRestreamUrl(configured)) {
            return disarm(Effect.Log("stream", "the destination is gone; broadcast ended"))
        }

        val target = configured!!.trim()
        destination = target
        return Step(
            state,
            listOf(
                Effect.ArmEgress(target),
                Effect.Log("stream", "re-armed ${redactStreamKey(target)}"),
            ),
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
     */
    fun onEgressFailed(reason: String): Step {
        return Step(
            state,
            listOf(
                Effect.Log("stream", "restream failed: $reason"),
                Effect.ToastDetail(reason),
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
        destination = null
        negotiatedCodec = null
        state = State.OFF

        return Step(state, listOf(Effect.StopKeepAlive))
    }

    private fun disarm(vararg leadingEffects: Effect): Step {
        destination = null
        state = State.OFF
        return Step(
            state,
            leadingEffects.toList() + listOf(Effect.DisarmEgress, Effect.StopKeepAlive),
        )
    }
}
