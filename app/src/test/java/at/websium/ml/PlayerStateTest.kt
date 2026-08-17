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
        // 0 to 3 are ST_CONNECTING, ST_PLAYING, ST_ERROR and ST_ENDED in gstplayer.c
        assertEquals(PlayerState.CONNECTING, PlayerState.fromNative(0))
        assertEquals(PlayerState.PLAYING, PlayerState.fromNative(1))
        assertEquals(PlayerState.ERROR, PlayerState.fromNative(2))
        assertEquals(PlayerState.ENDED, PlayerState.fromNative(3))
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
