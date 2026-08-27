package at.websium.ml

import at.websium.ml.ConnectionMachine.Effect
import at.websium.ml.ConnectionMachine.State
import at.websium.ml.ConnectionMachine.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation of the connection state machine: these pin the behaviour the app shipped
 * with, so a later refactor or fix has to change them on purpose.
 *
 * Timing is in the machine's own units, driven through an explicit clock. The thresholds are
 * written as literals rather than referenced from the machine, because the numbers are the
 * contract (7 s to blame the air unit, 8 s of frozen video before reconnecting, and so on).
 */
class ConnectionMachineTest {

    private val machine = ConnectionMachine()

    private fun tick(
        now: Long,
        hasNetwork: Boolean = true,
        frames: Int? = null,
        foreground: Boolean = true,
        isRestreaming: Boolean = false,
    ) = machine.onTick(
        Tick(
            hasNetwork = hasNetwork,
            frameCount = frames,
            foreground = foreground,
            isRestreaming = isRestreaming,
            nowMs = now,
        )
    )

    /** drive to CONNECTING the way an auto-connect does, with a player attached at t=0 */
    private fun autoConnect() {
        // SEARCHING asks for a probe, whose success creates the player and starts it
        tick(0)
        machine.onProbeResult(true, 0)
    }

    // searching for the goggle
    @Test
    fun startsSearching() {
        assertEquals(State.SEARCHING, machine.state)
    }

    @Test
    fun searchingWithoutANetworkDoesNothing() {
        val step = tick(1000, hasNetwork = false)
        assertEquals(State.SEARCHING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun searchingWithANetworkProbesTheRtspPort() {
        assertEquals(listOf(Effect.Probe), tick(1000).effects)
    }

    @Test
    fun aClosedPortParksInStreamDown() {
        tick(1000)
        val step = machine.onProbeResult(false, 1000)
        assertEquals(State.STREAM_DOWN, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun streamDownKeepsProbing() {
        tick(1000)
        machine.onProbeResult(false, 1000)
        assertEquals(listOf(Effect.Probe), tick(2000).effects)
    }

    @Test
    fun anOpenPortConnectsStraightAway() {
        tick(0)
        val step = machine.onProbeResult(true, 0)
        assertEquals(State.CONNECTING, step.state)
        assertEquals(
            listOf(Effect.Log("conn", "RTSP up, connecting"), Effect.CreatePlayer, Effect.StartStream),
            step.effects,
        )
    }

    @Test
    fun aStaleProbeAnswerIsIgnored() {
        // the probe takes up to its timeout, by which time a Connect tap may have moved on
        tick(0)
        machine.onConnectTapped(0)
        val step = machine.onProbeResult(true, 500)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    // the user leaving and coming back
    @Test
    fun disconnectingWithTheGoggleStillAttachedParksInReady() {
        autoConnect()
        val step = machine.onDisconnect(hasNetwork = true, nowMs = 1000)
        assertEquals(State.READY, step.state)
        assertEquals(listOf(Effect.TeardownPlayer), step.effects)
    }

    @Test
    fun readyWaitsForTheConnectTap() {
        autoConnect()
        machine.onDisconnect(hasNetwork = true, nowMs = 1000)
        val step = tick(2000)
        assertEquals(State.READY, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aDeliberateDisconnectSurvivesTheNextProbe() {
        autoConnect()
        machine.onDisconnect(hasNetwork = true, nowMs = 1000)
        // the port is still open, but the user asked to leave, so it must not reconnect itself
        assertEquals(State.READY, machine.onProbeResult(true, 2000).state)
    }

    @Test
    fun theConnectTapStartsASession() {
        autoConnect()
        machine.onDisconnect(hasNetwork = true, nowMs = 1000)
        val step = machine.onConnectTapped(2000)
        assertEquals(State.CONNECTING, step.state)
        assertEquals(listOf(Effect.CreatePlayer, Effect.StartStream), step.effects)
    }

    @Test
    fun disconnectingWithTheGoggleGoneGoesBackToSearching() {
        autoConnect()
        val step = machine.onDisconnect(hasNetwork = false, nowMs = 1000)
        assertEquals(State.SEARCHING, step.state)
        assertEquals(listOf(Effect.TeardownPlayer), step.effects)
    }

    @Test
    fun unpluggingTheGoggleRearmsAutoConnect() {
        autoConnect()
        // parked in READY, then unplugged, which clears the park
        machine.onDisconnect(hasNetwork = true, nowMs = 1000)
        tick(2000, hasNetwork = false)
        assertEquals(State.SEARCHING, machine.state)

        tick(3000)
        assertEquals(State.CONNECTING, machine.onProbeResult(true, 3000).state)
    }

    // waiting for media
    @Test
    fun framesPromoteToPlaying() {
        autoConnect()
        assertEquals(State.PLAYING, tick(1000, frames = 1).state)
    }

    @Test
    fun noFramesForSevenSecondsBlamesTheAirUnit() {
        autoConnect()
        assertEquals(State.CONNECTING, tick(7000, frames = 0).state)
        assertEquals(State.NO_AIR_UNIT, tick(7001, frames = 0).state)
    }

    @Test
    fun framesStillPromoteFromNoAirUnit() {
        autoConnect()
        tick(7001, frames = 0)
        assertEquals(State.PLAYING, tick(8000, frames = 3).state)
    }

    @Test
    fun aTickWithNoPlayerDoesNothing() {
        autoConnect()
        val step = tick(1000, frames = null)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aFailedAttemptIsRebuiltAfterTheDebounce() {
        autoConnect()
        machine.onPlayerEvent(PlayerEvent.Failed("refused"))

        assertFalse(tick(3999, frames = 0).effects.contains(Effect.StartStream))
        assertTrue(tick(4000, frames = 0).effects.contains(Effect.StartStream))
    }

    @Test
    fun anEndedStreamCountsAsAFailedAttempt() {
        autoConnect()
        machine.onPlayerEvent(PlayerEvent.Ended)
        assertTrue(tick(4000, frames = 0).effects.contains(Effect.StartStream))
    }

    @Test
    fun aRebuiltAttemptIsGivenTheFullDebounceAgain() {
        autoConnect()
        machine.onPlayerEvent(PlayerEvent.Failed("refused"))
        // rebuilt, so the error is consumed
        tick(4000, frames = 0)
        assertFalse(tick(5000, frames = 0).effects.contains(Effect.StartStream))
    }

    @Test
    fun aHungAttemptIsRebuiltWithoutAnError() {
        autoConnect()
        assertFalse(tick(19999, frames = 0).effects.contains(Effect.StartStream))
        val step = tick(20000, frames = 0)
        assertTrue(step.effects.contains(Effect.StartStream))
        assertTrue(step.effects.contains(Effect.Log("conn", "rebuild after 20000ms stuck (no frames)")))
    }

    // the session probe
    @Test
    fun aSessionWithNoMediaRechecksThePortEveryFiveSeconds() {
        autoConnect()
        assertFalse(tick(4999, frames = 0).effects.contains(Effect.SessionProbe))
        assertTrue(tick(5000, frames = 0).effects.contains(Effect.SessionProbe))
        assertFalse(tick(6000, frames = 0).effects.contains(Effect.SessionProbe))
        assertTrue(tick(10000, frames = 0).effects.contains(Effect.SessionProbe))
    }

    @Test
    fun aClosedPortDuringASessionHandsBackToTheAutoConnectPath() {
        autoConnect()
        tick(5000, frames = 0)
        val step = machine.onSessionProbeResult(portOpen = false, frameCount = 0, nowMs = 5100)
        assertEquals(State.STREAM_DOWN, step.state)
        assertEquals(
            listOf(
                Effect.Log("conn", "RTSP port closed, waiting for the stream"),
                Effect.TeardownPlayer,
            ),
            step.effects,
        )
    }

    @Test
    fun anOpenPortDuringASessionChangesNothing() {
        autoConnect()
        tick(5000, frames = 0)
        val step = machine.onSessionProbeResult(portOpen = true, frameCount = 0, nowMs = 5100)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun mediaArrivingDuringTheProbeCancelsTheTeardown() {
        // the probe blocks up to its timeout; frames may start flowing while it is outstanding
        autoConnect()
        tick(5000, frames = 0)
        val step = machine.onSessionProbeResult(portOpen = false, frameCount = 12, nowMs = 5100)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun theSessionProbeIsIgnoredOutsideASession() {
        autoConnect()
        tick(5000, frames = 0)
        machine.onDisconnect(hasNetwork = true, nowMs = 5050)
        val step = machine.onSessionProbeResult(portOpen = false, frameCount = 0, nowMs = 5100)
        assertEquals(State.READY, step.state)
        assertTrue(step.effects.isEmpty())
    }

    // playing
    @Test
    fun advancingFramesKeepPlaying() {
        autoConnect()
        tick(1000, frames = 1)
        assertEquals(State.PLAYING, tick(20000, frames = 2).state)
        assertEquals(State.PLAYING, tick(40000, frames = 3).state)
    }

    @Test
    fun twoSecondsOfFrozenVideoHoldsThePictureWhileTheSessionIsOpen() {
        // a battery swap: the goggle is still serving, so there is nothing to reconnect to and
        // rebuilding would throw away a working session and the frame on screen with it
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.PLAYING, tick(3000, frames = 5).state)
        val step = tick(3001, frames = 5)
        assertEquals(State.FEED_LOST, step.state)
        assertFalse(step.effects.contains(Effect.StartStream))
    }

    @Test
    fun aBlipShorterThanTheThresholdSaysNothing() {
        // the RF link resets Tx-side for a moment routinely, and the picture catches up on its own
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.PLAYING, tick(2500, frames = 5).state)
        assertEquals(State.PLAYING, tick(3000, frames = 6).state)
    }

    @Test
    fun eightSecondsOfFrozenVideoReconnectsOnceTheSessionHasGone() {
        autoConnect()
        tick(1000, frames = 5)
        machine.onSessionProbeResult(portOpen = false, frameCount = 5, nowMs = 2000)
        // the probe answer put it in STREAM_DOWN, so drive a fresh session that then loses its port
        autoConnect()
        tick(3000, frames = 5)
        machine.onSessionProbeResult(portOpen = false, frameCount = 0, nowMs = 3500)
        assertEquals(State.STREAM_DOWN, machine.state)
    }

    @Test
    fun aResumedFeedReturnsToPlayingWithoutRebuilding() {
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.FEED_LOST, tick(9001, frames = 5).state)

        val step = tick(12000, frames = 6)
        assertEquals(State.PLAYING, step.state)
        assertFalse(step.effects.contains(Effect.StartStream))
    }

    @Test
    fun aLostFeedRechecksThatTheSessionIsStillThere() {
        autoConnect()
        tick(1000, frames = 5)
        tick(9001, frames = 5)
        assertTrue(tick(20000, frames = 5).effects.contains(Effect.SessionProbe))
    }

    @Test
    fun aSessionThatAnswersOnThePortButNeverResumesIsRebuiltEventually() {
        autoConnect()
        tick(1000, frames = 5)
        tick(9001, frames = 5)
        assertEquals(State.FEED_LOST, tick(60000, frames = 5).state)

        val step = tick(69002, frames = 5)
        assertEquals(State.RECONNECTING, step.state)
        assertTrue(step.effects.contains(Effect.StartStream))
    }

    @Test
    fun aLostGoggleKeepsThePlayerWhileBroadcasting() {
        // releasing the player takes the egress pipeline with it, and the broadcast is the one
        // thing still worth holding open once the picture has gone
        autoConnect()
        tick(1000, frames = 5, isRestreaming = true)
        val step = tick(2000, hasNetwork = false, isRestreaming = true)
        assertEquals(State.SEARCHING, step.state)
        assertFalse(step.effects.contains(Effect.TeardownPlayer))
    }

    @Test
    fun aClosedPortKeepsThePlayerWhileBroadcasting() {
        autoConnect()
        tick(1000, frames = 5, isRestreaming = true)
        // a closed port is only believed once the feed has already stopped: the probe takes up
        // to its timeout, so from PLAYING it says nothing that the frame count does not
        assertEquals(State.FEED_LOST, tick(9001, frames = 5, isRestreaming = true).state)

        val step = machine.onSessionProbeResult(portOpen = false, frameCount = 5, nowMs = 10000)
        assertEquals(State.STREAM_DOWN, step.state)
        assertFalse(step.effects.contains(Effect.TeardownPlayer))
    }

    @Test
    fun aBackgroundedAppDoesNotChaseStalls() {
        // the GL sink cannot render without a surface, so a stall while stopped means nothing
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.PLAYING, tick(60000, frames = 5, foreground = false).state)
    }

    @Test
    fun aBackgroundedAppChasesStallsWhileBroadcasting() {
        // off screen the picture does not matter, but a broadcast does: it rides on the feed,
        // so a stalled feed has to be reconnected whether or not anything is rendering it
        autoConnect()
        tick(1000, frames = 5)
        val step = tick(60000, frames = 5, foreground = false, isRestreaming = true)
        assertEquals(State.FEED_LOST, step.state)
    }

    @Test
    fun reconnectingPromotesBackToPlayingOnNewFrames() {
        autoConnect()
        tick(1000, frames = 5)
        // stalls into FEED_LOST
        tick(9001, frames = 5)
        assertEquals(State.PLAYING, tick(10000, frames = 6).state)
    }

    @Test
    fun losingTheGoggleWhilePlayingTearsDown() {
        autoConnect()
        tick(1000, frames = 5)
        val step = tick(2000, hasNetwork = false)
        assertEquals(State.SEARCHING, step.state)
        assertEquals(listOf(Effect.TeardownPlayer), step.effects)
    }

    @Test
    fun theStallClockRestartsWithEachNewFrame() {
        autoConnect()
        tick(1000, frames = 5)
        // a new frame 7 s later, still under the threshold
        tick(8000, frames = 6)
        assertEquals(State.PLAYING, tick(15000, frames = 7).state)
    }

    // the player cannot be built
    @Test
    fun anUnbuildablePlayerStopsAtUnavailable() {
        autoConnect()
        val step = machine.onPlayerUnavailable("dlopen failed: libgstreamer_android.so not found")
        assertEquals(State.UNAVAILABLE, step.state)
        assertEquals(
            listOf(Effect.Log("player", "unavailable: dlopen failed: libgstreamer_android.so not found")),
            step.effects,
        )
    }

    @Test
    fun anUnknownFailureStillLogsSomething() {
        assertEquals(
            listOf(Effect.Log("player", "unavailable: unknown")),
            machine.onPlayerUnavailable(null).effects,
        )
    }

    @Test
    fun unavailableIsTerminalAcrossTicks() {
        autoConnect()
        machine.onPlayerUnavailable("no decoder")
        for (t in listOf(1000L, 5000L, 30000L)) {
            val step = tick(t, frames = 0)
            assertEquals(State.UNAVAILABLE, step.state)
            assertTrue("must not retry at t=$t", step.effects.isEmpty())
        }
    }

    @Test
    fun unavailableSurvivesTheGoggleComingAndGoing() {
        // replugging cannot fix a native library that failed to load, so it must not look like
        // it is searching again
        autoConnect()
        machine.onPlayerUnavailable("no decoder")
        assertEquals(State.UNAVAILABLE, tick(1000, hasNetwork = false).state)
        assertEquals(State.UNAVAILABLE, tick(2000, hasNetwork = true).state)
    }

    @Test
    fun unavailableIgnoresTheConnectTapAndDisconnect() {
        machine.onPlayerUnavailable("no decoder")
        assertEquals(State.UNAVAILABLE, machine.onConnectTapped(1000).state)
        assertTrue(machine.onConnectTapped(1000).effects.isEmpty())
        assertEquals(State.UNAVAILABLE, machine.onDisconnect(hasNetwork = true, nowMs = 1000).state)
        assertTrue(machine.onDisconnect(hasNetwork = true, nowMs = 1000).effects.isEmpty())
    }

    @Test
    fun unavailableIgnoresAnOutstandingProbe() {
        // a probe issued before the failure can answer after it
        tick(0)
        machine.onPlayerUnavailable("no decoder")
        assertEquals(State.UNAVAILABLE, machine.onProbeResult(true, 500).state)
        assertEquals(State.UNAVAILABLE, machine.onSessionProbeResult(false, 0, 500).state)
    }

    @Test
    fun unavailableIsNotASessionState() {
        // it must not enable the back handler or the session probe
        assertFalse(State.UNAVAILABLE in ConnectionMachine.SESSION_STATES)
    }

    // invariants
    @Test
    fun everySessionStateCarriesAPlayer() {
        // SESSION_STATES gates the back handler and the session probe; it must stay in step
        // with the states reached after CreatePlayer
        assertEquals(
            setOf(State.CONNECTING, State.NO_AIR_UNIT, State.PLAYING, State.RECONNECTING,
                  State.FEED_LOST),
            ConnectionMachine.SESSION_STATES,
        )
    }
}
