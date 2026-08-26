package at.websium.ml

/**
 * What the restream's audio track carries.
 *
 * There is always a track. Ingests behave badly with video-only, and because audio is decoupled
 * from video it keeps flowing through an RF dropout, which holds the session open instead of
 * ending the broadcast; a battery swap is 10 to 40 seconds of exactly that. So the choice is what
 * the track carries, never whether there is one.
 */
enum class AudioSource {
    /** a generated silent track */
    SILENCE,

    /** the phone's microphone, which also picks up whatever a speaker in the room is playing */
    MICROPHONE,
}

/**
 * The audio source [stored] names. Anything unrecognised, including nothing stored yet, is
 * silence: it is the source that needs no permission and cannot fail.
 */
internal fun audioSourceFor(stored: String?): AudioSource {
    if (stored == MICROPHONE_VALUE) {
        return AudioSource.MICROPHONE
    }
    return AudioSource.SILENCE
}

/** the preference value that selects the microphone; matches res/values/arrays.xml */
internal const val MICROPHONE_VALUE = "microphone"
