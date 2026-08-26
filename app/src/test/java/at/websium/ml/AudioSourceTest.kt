package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the stored preference value means.
 *
 * Silence is the answer to everything unrecognised, because it is the source that needs no
 * permission and cannot fail to open. There is no case that yields no track at all: an ingest
 * handed video alone behaves badly, and the audio is what holds the session open while the video
 * drops on a battery swap.
 */
class AudioSourceTest {

    @Test
    fun nothingStoredYetIsSilence() {
        assertEquals(AudioSource.SILENCE, audioSourceFor(null))
    }

    @Test
    fun theStoredSilenceValueIsSilence() {
        assertEquals(AudioSource.SILENCE, audioSourceFor("silence"))
    }

    @Test
    fun theStoredMicrophoneValueIsTheMicrophone() {
        assertEquals(AudioSource.MICROPHONE, audioSourceFor("microphone"))
    }

    @Test
    fun anUnrecognisedValueFallsBackToSilenceRatherThanNoTrack() {
        assertEquals(AudioSource.SILENCE, audioSourceFor("device"))
        assertEquals(AudioSource.SILENCE, audioSourceFor(""))
        assertEquals(AudioSource.SILENCE, audioSourceFor("Microphone"))
    }
}
