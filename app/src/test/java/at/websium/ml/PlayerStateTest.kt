package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The integer codes are a hand-maintained contract with the `ST_*` defines in
 * jni/gstplayer.c. Renumbering there without changing this mapping would silently report
 * the wrong state, so the codes are pinned here literally rather than by reference.
 */
class PlayerStateTest {

    @Test
    fun mapsTheNativeCodes() {
        assertEquals(PlayerState.CONNECTING, PlayerState.fromNative(0))  // ST_CONNECTING
        assertEquals(PlayerState.PLAYING, PlayerState.fromNative(1))     // ST_PLAYING
        assertEquals(PlayerState.ERROR, PlayerState.fromNative(2))       // ST_ERROR
        assertEquals(PlayerState.ENDED, PlayerState.fromNative(3))       // ST_ENDED
    }

    @Test
    fun treatsAnUnknownCodeAsEnded() {
        assertEquals(PlayerState.ENDED, PlayerState.fromNative(99))
        assertEquals(PlayerState.ENDED, PlayerState.fromNative(-1))
    }

    @Test
    fun errorAndEndedBothCountAsAFailedAttempt() {
        // MainActivity.onPlayerState treats these two as "rebuild the pipeline"; if a state
        // is ever added, this is where the decision has to be revisited.
        val failure = setOf(PlayerState.ERROR, PlayerState.ENDED)
        assertEquals(setOf(PlayerState.CONNECTING, PlayerState.PLAYING),
            PlayerState.entries.toSet() - failure)
    }
}
