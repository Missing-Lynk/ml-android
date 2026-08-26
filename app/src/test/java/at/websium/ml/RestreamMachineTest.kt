package at.websium.ml

import at.websium.ml.RestreamMachine.Effect
import at.websium.ml.RestreamMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast state machine: when a restream is armed, when it is carrying, and what the app
 * is asked to do at each edge.
 *
 * The trap running through it is the stream key on the end of the destination. Every effect that
 * displays or logs one is asserted on in full here, because Diagnostics has a Share button.
 */
class RestreamMachineTest {

    private val machine = RestreamMachine()

    private val url = "rtmp://live.twitch.tv/app/live_123_SECRET"
    private val destination = Destination(id = "d1", label = "Twitch live", url = url)

    /** arm the way a toggle tap does, from a state with a codec already negotiated */
    private fun arm(codec: String = "H264", target: Destination = destination) {
        machine.onCodecNegotiated(codec)
        machine.onToggleTapped(target)
    }

    // arming

    @Test
    fun startsOff() {
        assertEquals(State.OFF, machine.state)
        assertFalse(machine.isArmed)
    }

    @Test
    fun armingWithNoDestinationSetAsksForOneAndStaysOff() {
        val step = machine.onToggleTapped(null)
        assertEquals(State.OFF, step.state)
        assertEquals(listOf(Effect.Toast(R.string.stream_needs_destination)), step.effects)
    }

    @Test
    fun armingWithSomethingThatIsNotAnIngestUrlStaysOff() {
        val step = machine.onToggleTapped(destination.copy(url = "https://example.com/live"))
        assertEquals(State.OFF, step.state)
        assertEquals(listOf(Effect.Toast(R.string.stream_needs_destination)), step.effects)
    }

    @Test
    fun armingPointsThePlayerAtTheKeyAndNamesTheDestinationEverywhereElse() {
        val step = machine.onToggleTapped(destination.copy(url = "  $url  "))

        assertEquals(State.ARMED, step.state)
        assertTrue(machine.isArmed)
        assertEquals("d1", machine.armedDestinationId)
        assertEquals(
            listOf(
                Effect.RequestNotificationPermission,
                Effect.ArmEgress(url),
                Effect.StartKeepAlive("Twitch live"),
                Effect.Log("stream", "started to Twitch live"),
                Effect.Toast(R.string.stream_started),
            ),
            step.effects,
        )
    }

    @Test
    fun nothingAnArmingEmitsCarriesTheStreamKeyExceptTheEgressItself() {
        val step = machine.onToggleTapped(destination)
        val leaked = step.effects
            .filter { effect -> effect !is Effect.ArmEgress }
            .filter { effect -> effect.toString().contains("SECRET") }
        assertEquals(emptyList<Effect>(), leaked)
    }

    @Test
    fun armingIsRejectedWhenTheDestinationWillNotTakeTheNegotiatedCodec() {
        machine.onCodecNegotiated("H265")
        val step = machine.onToggleTapped(destination)

        assertEquals(State.OFF, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "refused: H265 to Twitch live"),
                Effect.Toast(R.string.stream_codec_rejected),
            ),
            step.effects,
        )
    }

    @Test
    fun anUnrecognisedIngestIsGivenTheStreamAndAllowedToRefuseItItself() {
        machine.onCodecNegotiated("H265")
        val step = machine.onToggleTapped(
            Destination(id = "d2", label = "Local", url = "rtmp://mediamtx.local/live/KEY")
        )
        assertEquals(State.ARMED, step.state)
    }

    @Test
    fun aDestinationIsJudgedOnlyOnceACodecHasBeenNegotiated() {
        val step = machine.onToggleTapped(destination)
        assertEquals(State.ARMED, step.state)
    }

    // disarming

    @Test
    fun tappingTheToggleAgainEndsTheBroadcast() {
        arm()
        val step = machine.onToggleTapped(destination)

        assertEquals(State.OFF, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "stopped by the user"),
                Effect.Toast(R.string.stream_stopped),
                Effect.DisarmEgress,
                Effect.StopKeepAlive,
            ),
            step.effects,
        )
    }

    @Test
    fun leavingTheSessionEndsTheBroadcastBecauseBackingOutMeansTheFlightIsOver() {
        arm()
        machine.onEgressLive(true)
        val step = machine.onSessionLeft()

        assertEquals(State.OFF, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "stopped by leaving the session"),
                Effect.Toast(R.string.stream_stopped),
                Effect.DisarmEgress,
                Effect.StopKeepAlive,
            ),
            step.effects,
        )
    }

    @Test
    fun leavingASessionThatWasNotBroadcastingSaysNothing() {
        val step = machine.onSessionLeft()
        assertEquals(State.OFF, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun shuttingDownStopsTheKeepAliveEvenWithNothingArmed() {
        val step = machine.onShutdown()
        assertEquals(State.OFF, step.state)
        assertEquals(listOf(Effect.StopKeepAlive), step.effects)
    }

    // carrying to the destination

    @Test
    fun theEgressCarryingIsWhatSeparatesLiveFromReconnecting() {
        arm()
        assertEquals(State.ARMED, machine.state)

        assertEquals(State.CARRYING, machine.onEgressLive(true).state)
        assertEquals(State.ARMED, machine.onEgressLive(false).state)
        assertEquals(State.CARRYING, machine.onEgressLive(true).state)
    }

    @Test
    fun aLateLiveReportAfterDisarmingDoesNotReviveTheBroadcast() {
        arm()
        machine.onEgressLive(true)
        machine.onToggleTapped(destination)

        val step = machine.onEgressLive(true)
        assertEquals(State.OFF, step.state)
    }

    @Test
    fun anEgressFailureReportsAndLeavesTheBroadcastArmed() {
        arm()
        machine.onEgressLive(true)
        val step = machine.onEgressFailed("Could not connect to server")

        assertEquals(State.CARRYING, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "restream failed: Could not connect to server"),
                Effect.ToastDetail("Could not connect to server"),
            ),
            step.effects,
        )
    }

    // the player underneath

    @Test
    fun losingThePlayerStopsTheBroadcastCarryingWithoutDisarmingIt() {
        arm()
        machine.onEgressLive(true)

        val step = machine.onPlayerGone()
        assertEquals(State.ARMED, step.state)
        assertTrue(machine.isArmed)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aNewPlayerIsArmedAgainSoLosingTheGoggleDoesNotEndTheBroadcast() {
        arm()
        machine.onEgressLive(true)
        machine.onPlayerGone()

        val step = machine.onPlayerCreated(destination)
        assertEquals(State.ARMED, step.state)
        assertEquals(
            listOf(
                Effect.ArmEgress(url),
                Effect.Log("stream", "re-armed Twitch live"),
            ),
            step.effects,
        )
    }

    @Test
    fun aDestinationEditedInSettingsIsPickedUpByTheNextArming() {
        arm()
        machine.onPlayerGone()

        val moved = "rtmp://a.rtmp.youtube.com/live2/OTHER"
        val step = machine.onPlayerCreated(destination.copy(url = moved))
        assertEquals(Effect.ArmEgress(moved), step.effects.first())
    }

    @Test
    fun theBroadcastNamesTheRecordItWasArmedTo() {
        // the caller looks the destination up by this id, so a change of active entry cannot
        // move a broadcast already in flight
        assertNull(machine.armedDestinationId)
        arm()
        assertEquals("d1", machine.armedDestinationId)
        machine.onSessionLeft()
        assertNull(machine.armedDestinationId)
    }

    @Test
    fun aDestinationClearedInSettingsEndsTheBroadcastAtTheNextPlayer() {
        arm()
        machine.onPlayerGone()

        val step = machine.onPlayerCreated(null)
        assertEquals(State.OFF, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "the destination is gone; broadcast ended"),
                Effect.DisarmEgress,
                Effect.StopKeepAlive,
            ),
            step.effects,
        )
    }

    @Test
    fun deletingTheArmedDestinationEndsTheBroadcastAtOnce() {
        // the alternative is carrying on to a destination the user has thrown away
        arm()
        machine.onEgressLive(true)
        val step = machine.onDestinationDeleted()

        assertEquals(State.OFF, step.state)
        assertEquals(
            listOf(
                Effect.Log("stream", "the destination was deleted; broadcast ended"),
                Effect.Toast(R.string.stream_stopped),
                Effect.DisarmEgress,
                Effect.StopKeepAlive,
            ),
            step.effects,
        )
    }

    @Test
    fun aDeletionWithNothingArmedSaysNothing() {
        val step = machine.onDestinationDeleted()
        assertEquals(State.OFF, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aNewPlayerLeavesAnUnarmedAppAlone() {
        val step = machine.onPlayerCreated(destination)
        assertEquals(State.OFF, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun theCodecBelongsToThePlayerThatNegotiatedItAndGoesWithIt() {
        // a goggle sending H.265 refuses Twitch; the next one may not be sending H.265 at all
        machine.onCodecNegotiated("H265")
        assertEquals(State.OFF, machine.onToggleTapped(destination).state)

        machine.onPlayerGone()
        assertEquals(State.ARMED, machine.onToggleTapped(destination).state)
    }
}
