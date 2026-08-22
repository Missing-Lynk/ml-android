package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The integer codes are a hand-maintained contract with the `ST_*` defines in
 * jni/gstplayer.c. Renumbering there without changing this mapping would silently report
 * the wrong event, so the codes are pinned here literally rather than by reference.
 */
class PlayerEventTest {

    @Test
    fun mapsTheNativeCodes() {
        // 0 to 3 are ST_CONNECTING, ST_PLAYING, ST_ERROR and ST_ENDED in gstplayer.c
        assertEquals(PlayerEvent.Connecting, PlayerEvent.fromNative(0, null))
        assertEquals(PlayerEvent.Playing, PlayerEvent.fromNative(1, null))
        assertEquals(PlayerEvent.Failed(null), PlayerEvent.fromNative(2, null))
        assertEquals(PlayerEvent.Ended, PlayerEvent.fromNative(3, null))
    }

    @Test
    fun treatsAnUnknownCodeAsEnded() {
        assertEquals(PlayerEvent.Ended, PlayerEvent.fromNative(99, null))
        assertEquals(PlayerEvent.Ended, PlayerEvent.fromNative(-1, null))
    }

    @Test
    fun aFailureCarriesTheReasonErrorCbBuilt() {
        val line = "error from rtspsrc0: Could not open resource for reading."
        assertEquals(PlayerEvent.Failed(line), PlayerEvent.fromNative(2, line))
    }

    @Test
    fun onlyAFailureCarriesAReason() {
        // a reason arriving with any other code is dropped, so a stale string cannot show
        assertEquals(PlayerEvent.Playing, PlayerEvent.fromNative(1, "stale"))
        assertEquals(PlayerEvent.Ended, PlayerEvent.fromNative(3, "stale"))
    }
}
