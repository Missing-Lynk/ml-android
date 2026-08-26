package at.websium.ml

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * How the window is dressed. IMMERSIVE hides the toolbar and the system bars, locks the window
 * to landscape, and is the chrome in which the transient controls can be revealed; TOOLBAR shows
 * the toolbar and the system bars in portrait.
 */
enum class Chrome { TOOLBAR, IMMERSIVE }

/**
 * The secondary line under the status text. One view carries both, and the two never apply in
 * the same state: the setup pointer belongs to a screen with no player, the detail to a screen
 * whose player has just failed.
 */
sealed interface Hint {
    /** the fixed setup pointer, for a user with no goggle or no stream yet */
    data class Copy(@param:StringRes val textResource: Int) : Hint

    /** what the player said went wrong, shown while the next attempt runs */
    data class Detail(val text: String) : Hint
}

/**
 * The status panel's contents, which exist only while the panel is showing. A spinner or a
 * status line with no panel behind it is therefore unrepresentable.
 */
data class StatusPanel(
    @param:StringRes val textResource: Int,
    val isSpinnerVisible: Boolean = false,
    val isImageVisible: Boolean = false,
    val hint: Hint? = null,
    val isConnectVisible: Boolean = false,
)

/**
 * The arm/disarm control's two faces.
 */
enum class Toggle(
    @param:DrawableRes val iconResource: Int,
    @param:StringRes val descriptionResource: Int,
) {
    START(R.drawable.ic_stream_start, R.string.stream_start),
    STOP(R.drawable.ic_stream_stop, R.string.stream_stop),
}

/**
 * The broadcast indicator. A reconnect is silent by design, so an armed restream that is not
 * carrying says so rather than showing the same thing as one that is.
 */
enum class Badge(
    @param:StringRes val textResource: Int,
    @param:DrawableRes val iconResource: Int,
) {
    LIVE(R.string.stream_badge_live, R.drawable.ic_dot_live),
    RECONNECTING(R.string.stream_badge_reconnecting, R.drawable.ic_dot_reconnecting),
}

/**
 * The controls drawn over the video.
 *
 * The back control and the toggle are revealed by a tap and hide themselves again; the badge is
 * tied to the armed state instead, and shows in every chrome, because whether a session is being
 * broadcast has to stay readable once the controls have gone and after the picture has.
 *
 * A hidden toggle carries no icon, which makes an invisible control with a face unrepresentable.
 */
data class Controls(
    val isBackVisible: Boolean = false,
    val toggle: Toggle? = null,
    val badge: Badge? = null,
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

    val controls: Controls = Controls(),
)

/**
 * The one place that says what each state looks like.
 *
 * [failureReason] is the player's last complaint, which the states that are mid-attempt show
 * under the status line. [restream] and [areControlsRevealed] decide the controls over the
 * video, so the overlay and the status panel are one value with one writer.
 */
fun screenFor(
    state: ConnectionMachine.State,
    failureReason: String? = null,
    restream: RestreamMachine.State = RestreamMachine.State.OFF,
    areControlsRevealed: Boolean = false,
): Screen {
    val base = statusScreenFor(state, failureReason)
    return base.copy(controls = controlsFor(base.chrome, restream, areControlsRevealed))
}

/**
 * The controls for one chrome and one broadcast state. The back control and the toggle belong to
 * the immersive chrome, where the video fills the window and there is nothing else to touch.
 */
private fun controlsFor(
    chrome: Chrome,
    restream: RestreamMachine.State,
    areControlsRevealed: Boolean,
): Controls {
    val isShowing = chrome == Chrome.IMMERSIVE && areControlsRevealed
    return Controls(
        isBackVisible = isShowing,
        toggle = when {
            !isShowing -> null
            restream == RestreamMachine.State.OFF -> Toggle.START
            else -> Toggle.STOP
        },
        badge = when (restream) {
            RestreamMachine.State.OFF -> null
            RestreamMachine.State.ARMED -> Badge.RECONNECTING
            RestreamMachine.State.CARRYING -> Badge.LIVE
        },
    )
}

private fun statusScreenFor(state: ConnectionMachine.State, failureReason: String?): Screen {
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
