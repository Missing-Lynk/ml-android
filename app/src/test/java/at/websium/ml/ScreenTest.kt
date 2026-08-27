package at.websium.ml

import at.websium.ml.ConnectionMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                    hint = Hint.Copy(R.string.searching_hint),
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
                    hint = Hint.Copy(R.string.searching_hint),
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

    // the player's complaint, shown while the next attempt runs
    @Test
    fun aMidAttemptScreenShowsWhyTheLastAttemptFailed() {
        val reason = "error from rtspsrc0: Could not open resource for reading."
        assertEquals(
            Hint.Detail(reason),
            screenFor(State.CONNECTING, reason).status?.hint,
        )
        assertEquals(
            Hint.Detail(reason),
            screenFor(State.NO_AIR_UNIT, reason).status?.hint,
        )
        assertEquals(
            Hint.Detail(reason),
            screenFor(State.RECONNECTING, reason).status?.hint,
        )
    }

    @Test
    fun aScreenWithNoPlayerKeepsTheSetupPointer() {
        // SEARCHING and STREAM_DOWN have torn the player down, so a stale reason must not show
        val reason = "error from rtspsrc0: Could not open resource for reading."
        assertEquals(
            Hint.Copy(R.string.searching_hint),
            screenFor(State.SEARCHING, reason).status?.hint,
        )
        assertEquals(
            Hint.Copy(R.string.searching_hint),
            screenFor(State.STREAM_DOWN, reason).status?.hint,
        )
    }

    @Test
    fun statesWithNothingToSayShowNoHint() {
        val reason = "error from rtspsrc0: Could not open resource for reading."
        assertNull(screenFor(State.READY, reason).status?.hint)
        assertNull(screenFor(State.UNAVAILABLE, reason).status?.hint)
    }

    @Test
    fun noFailureLeavesTheMidAttemptScreensBare() {
        assertNull(screenFor(State.CONNECTING).status?.hint)
        assertNull(screenFor(State.RECONNECTING).status?.hint)
    }

    // rules that hold across the whole table
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
        assertEquals(setOf(State.PLAYING, State.RECONNECTING, State.FEED_LOST), immersive)
    }

    @Test
    fun theStatusPanelIsHiddenOnlyWhereThereIsAPictureBehindIt() {
        // FEED_LOST included: the panel would cover the frame the decoder is still holding, which
        // is the whole of what that state has to show
        val panelless = State.entries.filter { state -> screenFor(state).status == null }.toSet()
        assertEquals(setOf(State.PLAYING, State.FEED_LOST), panelless)
    }

    @Test
    fun onlyALostFeedCarriesANotice() {
        val noticed = State.entries.filter { state -> screenFor(state).controls.notice != null }
        assertEquals(listOf(State.FEED_LOST), noticed)
    }

    @Test
    fun theNoticeShowsWithTheControlsHidden() {
        // it is tied to the state rather than to a tap, like the badge and unlike the controls
        val controls = screenFor(State.FEED_LOST, areControlsRevealed = false).controls
        assertEquals(Notice(R.string.notice_feed_lost), controls.notice)
        assertFalse(controls.isBackVisible)
    }

    // the controls over the video
    @Test
    fun aScreenWithNothingArmedAndNothingRevealedHasNoControls() {
        assertEquals(Controls(), screenFor(State.PLAYING).controls)
        assertEquals(Controls(), screenFor(State.SEARCHING).controls)
    }

    @Test
    fun tappingTheVideoRevealsTheBackControlAndTheToggle() {
        assertEquals(
            Controls(isBackVisible = true, toggle = Toggle.START),
            screenFor(State.PLAYING, areControlsRevealed = true).controls,
        )
    }

    @Test
    fun theToggleOffersToStopWhateverIsArmed() {
        assertEquals(
            Toggle.STOP,
            screenFor(
                State.PLAYING,
                restream = RestreamMachine.State.ARMED,
                areControlsRevealed = true,
            ).controls.toggle,
        )
        assertEquals(
            Toggle.STOP,
            screenFor(
                State.PLAYING,
                restream = RestreamMachine.State.CARRYING,
                areControlsRevealed = true,
            ).controls.toggle,
        )
    }

    @Test
    fun theControlsBelongToTheImmersiveChromeAndAppearInNoOther() {
        val revealed = ConnectionMachine.State.entries.filter { state ->
            screenFor(state, areControlsRevealed = true).controls.isBackVisible
        }.toSet()
        assertEquals(setOf(State.PLAYING, State.RECONNECTING, State.FEED_LOST), revealed)

        val toggled = ConnectionMachine.State.entries.filter { state ->
            screenFor(state, areControlsRevealed = true).controls.toggle != null
        }.toSet()
        assertEquals(setOf(State.PLAYING, State.RECONNECTING, State.FEED_LOST), toggled)
    }

    @Test
    fun theBadgeSaysWhetherAnArmedBroadcastIsCarrying() {
        assertNull(screenFor(State.PLAYING, restream = RestreamMachine.State.OFF).controls.badge)
        assertEquals(
            Badge.reconnecting("Twitch live"),
            screenFor(
                State.PLAYING,
                restream = RestreamMachine.State.ARMED,
                restreamLabel = "Twitch live",
            ).controls.badge,
        )
        assertEquals(
            Badge.live("Twitch live"),
            screenFor(
                State.PLAYING,
                restream = RestreamMachine.State.CARRYING,
                restreamLabel = "Twitch live",
            ).controls.badge,
        )
    }

    @Test
    fun theBadgeNamesTheDestinationBecauseSeveralAreSaved() {
        // which destination a session is going to is not otherwise visible from the video screen
        val badge = screenFor(
            State.PLAYING,
            restream = RestreamMachine.State.CARRYING,
            restreamLabel = "Twitch Inspector",
        ).controls.badge
        assertEquals("Twitch Inspector", badge?.label)
    }

    @Test
    fun theBadgeStaysUpInEveryChromeBecauseTheFeedIsWhatGoesAway() {
        // the controls hide themselves; whether a session is being broadcast has to outlast them
        val lit = ConnectionMachine.State.entries.filter { state ->
            screenFor(
                state,
                restream = RestreamMachine.State.ARMED,
                restreamLabel = "Twitch live",
            ).controls.badge != null
        }.toSet()
        assertEquals(ConnectionMachine.State.entries.toSet(), lit)
    }
}
