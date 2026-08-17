package com.brushlesswhoop.missinglynk

import com.brushlesswhoop.missinglynk.ConnectionMachine.Effect
import com.brushlesswhoop.missinglynk.ConnectionMachine.State
import com.brushlesswhoop.missinglynk.ConnectionMachine.Tick
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
 * contract (7 s to "turn on your quad", 8 s of frozen video before reconnecting, and so on).
 */
class ConnectionMachineTest {

    private val m = ConnectionMachine()

    private fun tick(
        now: Long,
        hasNetwork: Boolean = true,
        frames: Int? = null,
        foreground: Boolean = true,
    ) = m.onTick(Tick(hasNetwork, frames, foreground, now))

    /** drive to CONNECTING the way an auto-connect does, with a player attached at t=0 */
    private fun autoConnect() {
        tick(0)                       // SEARCHING, asks for a probe
        m.onProbeResult(true, 0)      // -> CONNECTING, player created and started
    }

    // ---- searching for the goggle ----

    @Test
    fun startsSearching() {
        assertEquals(State.SEARCHING, m.state)
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
        val step = m.onProbeResult(false, 1000)
        assertEquals(State.STREAM_DOWN, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun streamDownKeepsProbing() {
        tick(1000)
        m.onProbeResult(false, 1000)
        assertEquals(listOf(Effect.Probe), tick(2000).effects)
    }

    @Test
    fun anOpenPortConnectsStraightAway() {
        tick(0)
        val step = m.onProbeResult(true, 0)
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
        m.onConnectTapped(0)
        val step = m.onProbeResult(true, 500)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    // ---- the user leaving and coming back ----

    @Test
    fun disconnectingWithTheGoggleStillAttachedParksInReady() {
        autoConnect()
        val step = m.onDisconnect(hasNetwork = true, nowMs = 1000)
        assertEquals(State.READY, step.state)
        assertEquals(listOf(Effect.TeardownPlayer), step.effects)
    }

    @Test
    fun readyWaitsForTheConnectTap() {
        autoConnect()
        m.onDisconnect(hasNetwork = true, nowMs = 1000)
        val step = tick(2000)
        assertEquals(State.READY, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aDeliberateDisconnectSurvivesTheNextProbe() {
        autoConnect()
        m.onDisconnect(hasNetwork = true, nowMs = 1000)
        // the port is still open, but the user asked to leave, so it must not reconnect itself
        assertEquals(State.READY, m.onProbeResult(true, 2000).state)
    }

    @Test
    fun theConnectTapStartsASession() {
        autoConnect()
        m.onDisconnect(hasNetwork = true, nowMs = 1000)
        val step = m.onConnectTapped(2000)
        assertEquals(State.CONNECTING, step.state)
        assertEquals(listOf(Effect.CreatePlayer, Effect.StartStream), step.effects)
    }

    @Test
    fun disconnectingWithTheGoggleGoneGoesBackToSearching() {
        autoConnect()
        val step = m.onDisconnect(hasNetwork = false, nowMs = 1000)
        assertEquals(State.SEARCHING, step.state)
        assertEquals(listOf(Effect.TeardownPlayer), step.effects)
    }

    @Test
    fun unpluggingTheGoggleRearmsAutoConnect() {
        autoConnect()
        m.onDisconnect(hasNetwork = true, nowMs = 1000)   // parked in READY
        tick(2000, hasNetwork = false)                    // unplugged: clears the park
        assertEquals(State.SEARCHING, m.state)

        tick(3000)
        assertEquals(State.CONNECTING, m.onProbeResult(true, 3000).state)
    }

    // ---- waiting for media ----

    @Test
    fun framesPromoteToPlaying() {
        autoConnect()
        assertEquals(State.PLAYING, tick(1000, frames = 1).state)
    }

    @Test
    fun noFramesForSevenSecondsBlamesTheQuad() {
        autoConnect()
        assertEquals(State.CONNECTING, tick(7000, frames = 0).state)
        assertEquals(State.NO_QUAD, tick(7001, frames = 0).state)
    }

    @Test
    fun framesStillPromoteFromNoQuad() {
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
        m.onPlayerState(PlayerState.ERROR)

        assertFalse(tick(3999, frames = 0).effects.contains(Effect.StartStream))
        assertTrue(tick(4000, frames = 0).effects.contains(Effect.StartStream))
    }

    @Test
    fun anEndedStreamCountsAsAFailedAttempt() {
        autoConnect()
        m.onPlayerState(PlayerState.ENDED)
        assertTrue(tick(4000, frames = 0).effects.contains(Effect.StartStream))
    }

    @Test
    fun aRebuiltAttemptIsGivenTheFullDebounceAgain() {
        autoConnect()
        m.onPlayerState(PlayerState.ERROR)
        tick(4000, frames = 0)                       // rebuilt, error consumed
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

    // ---- the session probe ----

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
        val step = m.onSessionProbeResult(up = false, frameCount = 0, nowMs = 5100)
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
        val step = m.onSessionProbeResult(up = true, frameCount = 0, nowMs = 5100)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun mediaArrivingDuringTheProbeCancelsTheTeardown() {
        // the probe blocks up to its timeout; frames may start flowing while it is outstanding
        autoConnect()
        tick(5000, frames = 0)
        val step = m.onSessionProbeResult(up = false, frameCount = 12, nowMs = 5100)
        assertEquals(State.CONNECTING, step.state)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun theSessionProbeIsIgnoredOutsideASession() {
        autoConnect()
        tick(5000, frames = 0)
        m.onDisconnect(hasNetwork = true, nowMs = 5050)
        val step = m.onSessionProbeResult(up = false, frameCount = 0, nowMs = 5100)
        assertEquals(State.READY, step.state)
        assertTrue(step.effects.isEmpty())
    }

    // ---- playing ----

    @Test
    fun advancingFramesKeepPlaying() {
        autoConnect()
        tick(1000, frames = 1)
        assertEquals(State.PLAYING, tick(20000, frames = 2).state)
        assertEquals(State.PLAYING, tick(40000, frames = 3).state)
    }

    @Test
    fun eightSecondsOfFrozenVideoReconnects() {
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.PLAYING, tick(9000, frames = 5).state)
        val step = tick(9001, frames = 5)
        assertEquals(State.RECONNECTING, step.state)
        assertTrue(step.effects.contains(Effect.StartStream))
    }

    @Test
    fun aBackgroundedAppDoesNotChaseStalls() {
        // the GL sink cannot render without a surface, so a stall while stopped means nothing
        autoConnect()
        tick(1000, frames = 5)
        assertEquals(State.PLAYING, tick(60000, frames = 5, foreground = false).state)
    }

    @Test
    fun reconnectingPromotesBackToPlayingOnNewFrames() {
        autoConnect()
        tick(1000, frames = 5)
        tick(9001, frames = 5)                       // -> RECONNECTING
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
        tick(8000, frames = 6)                       // 7 s later, still fine
        assertEquals(State.PLAYING, tick(15000, frames = 7).state)
    }

    // ---- invariants ----

    @Test
    fun everySessionStateCarriesAPlayer() {
        // SESSION_STATES gates the back handler and the session probe; it must stay in step
        // with the states reached after CreatePlayer
        assertEquals(
            setOf(State.CONNECTING, State.NO_QUAD, State.PLAYING, State.RECONNECTING),
            ConnectionMachine.SESSION_STATES,
        )
    }
}
