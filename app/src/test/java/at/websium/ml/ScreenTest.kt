package at.websium.ml

import at.websium.ml.ConnectionMachine.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen every state renders to. Each case pins one whole [Screen], so a field that moves
 * without a matching expectation fails with a diff of the two values.
 */
class ScreenTest {

    @Test
    fun searchingSpinsAndOffersTheSetupHint() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(
                    textResource = R.string.state_searching,
                    isSpinnerVisible = true,
                    isHintVisible = true,
                ),
                isInSession = false,
            ),
            screenFor(State.SEARCHING),
        )
    }

    @Test
    fun streamDownKeepsTheHintUpBecauseTheUserStillHasNoVideo() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(
                    textResource = R.string.state_stream_down,
                    isSpinnerVisible = true,
                    isHintVisible = true,
                ),
                isInSession = false,
            ),
            screenFor(State.STREAM_DOWN),
        )
    }

    @Test
    fun readyShowsTheConnectButtonAndStopsSpinning() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(
                    textResource = R.string.state_ready,
                    isConnectVisible = true,
                ),
                isInSession = false,
            ),
            screenFor(State.READY),
        )
    }

    @Test
    fun connectingSpinsInASession() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(
                    textResource = R.string.state_connecting,
                    isSpinnerVisible = true,
                ),
                isInSession = true,
            ),
            screenFor(State.CONNECTING),
        )
    }

    @Test
    fun noAirUnitBlamesTheAirUnitAndKeepsSpinning() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(
                    textResource = R.string.state_no_air_unit,
                    isSpinnerVisible = true,
                ),
                isInSession = true,
            ),
            screenFor(State.NO_AIR_UNIT),
        )
    }

    @Test
    fun playingIsBareImmersiveVideo() {
        assertEquals(
            Screen(chrome = Chrome.IMMERSIVE, status = null, isInSession = true),
            screenFor(State.PLAYING),
        )
    }

    @Test
    fun reconnectingStaysImmersiveWithThePanelOverTheVideo() {
        assertEquals(
            Screen(
                chrome = Chrome.IMMERSIVE,
                status = StatusPanel(
                    textResource = R.string.state_reconnecting,
                    isSpinnerVisible = true,
                    isImageVisible = true,
                ),
                isInSession = true,
            ),
            screenFor(State.RECONNECTING),
        )
    }

    @Test
    fun unavailableShowsOnlyItsMessage() {
        assertEquals(
            Screen(
                chrome = Chrome.TOOLBAR,
                status = StatusPanel(textResource = R.string.state_unavailable),
                isInSession = false,
            ),
            screenFor(State.UNAVAILABLE),
        )
    }

    // ---- rules that hold across the whole table ----

    @Test
    fun sessionMembershipTracksTheMachinesOwnSet() {
        val inSession = State.entries.filter { state -> screenFor(state).isInSession }.toSet()
        assertEquals(ConnectionMachine.SESSION_STATES, inSession)
    }

    @Test
    fun immersiveChromeIsForVideoAndItsRecovery() {
        val immersive = State.entries
            .filter { state -> screenFor(state).chrome == Chrome.IMMERSIVE }
            .toSet()
        assertEquals(setOf(State.PLAYING, State.RECONNECTING), immersive)
    }

    @Test
    fun theStatusPanelIsHiddenOnlyWhilePlaying() {
        val panelless = State.entries.filter { state -> screenFor(state).status == null }.toSet()
        assertEquals(setOf(State.PLAYING), panelless)
    }
}
