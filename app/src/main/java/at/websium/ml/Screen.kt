package at.websium.ml

import androidx.annotation.StringRes

/**
 * How the window is dressed. IMMERSIVE hides the toolbar and the system bars, locks the window
 * to landscape, and is the chrome in which the fullscreen back control can be revealed; TOOLBAR
 * shows the toolbar and the system bars in portrait.
 */
enum class Chrome { TOOLBAR, IMMERSIVE }

/**
 * The secondary line under the status text. One view carries both, and the two never apply in
 * the same state: the setup pointer belongs to a screen with no player, the detail to a screen
 * whose player has just failed.
 */
sealed interface Hint {
    /** the fixed setup pointer, for a user with no goggle or no stream yet */
    data class Copy(@StringRes val textResource: Int) : Hint

    /** what the player said went wrong, shown while the next attempt runs */
    data class Detail(val text: String) : Hint
}

/**
 * The status panel's contents, which exist only while the panel is showing. A spinner or a
 * status line with no panel behind it is therefore unrepresentable.
 */
data class StatusPanel(
    @StringRes val textResource: Int,
    val isSpinnerVisible: Boolean = false,
    val isImageVisible: Boolean = false,
    val hint: Hint? = null,
    val isConnectVisible: Boolean = false,
)

/**
 * Everything the main screen shows for one connection state, as a value. The view layer applies
 * it verbatim, so every decision about visibility, copy and orientation is made in [screenFor].
 */
data class Screen(
    val chrome: Chrome,

    /** carried while the status panel shows; null while playing, which is when video is visible */
    val status: StatusPanel?,

    /** the back gesture leaves the session */
    val isInSession: Boolean,
)

/**
 * The one place that says what each state looks like. [failureReason] is the player's last
 * complaint, which the states that are mid-attempt show under the status line.
 */
fun screenFor(state: ConnectionMachine.State, failureReason: String? = null): Screen {
    val detail = failureReason?.let { reason -> Hint.Detail(reason) }

    return when (state) {
        ConnectionMachine.State.SEARCHING -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(
                textResource = R.string.state_searching,
                isSpinnerVisible = true,
                hint = Hint.Copy(R.string.searching_hint),
            ),
            isInSession = false,
        )

        ConnectionMachine.State.STREAM_DOWN -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(
                textResource = R.string.state_stream_down,
                isSpinnerVisible = true,
                hint = Hint.Copy(R.string.searching_hint),
            ),
            isInSession = false,
        )

        ConnectionMachine.State.READY -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(
                textResource = R.string.state_ready,
                isConnectVisible = true,
            ),
            isInSession = false,
        )

        ConnectionMachine.State.CONNECTING -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(
                textResource = R.string.state_connecting,
                isSpinnerVisible = true,
                hint = detail,
            ),
            isInSession = true,
        )

        ConnectionMachine.State.NO_AIR_UNIT -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(
                textResource = R.string.state_no_air_unit,
                isSpinnerVisible = true,
                hint = detail,
            ),
            isInSession = true,
        )

        ConnectionMachine.State.PLAYING -> Screen(
            chrome = Chrome.IMMERSIVE,
            status = null,
            isInSession = true,
        )

        ConnectionMachine.State.RECONNECTING -> Screen(
            chrome = Chrome.IMMERSIVE,
            status = StatusPanel(
                textResource = R.string.state_reconnecting,
                isSpinnerVisible = true,
                isImageVisible = true,
                hint = detail,
            ),
            isInSession = true,
        )

        ConnectionMachine.State.UNAVAILABLE -> Screen(
            chrome = Chrome.TOOLBAR,
            status = StatusPanel(textResource = R.string.state_unavailable),
            isInSession = false,
        )
    }
}
