package at.websium.ml

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.UUID

/**
 * Where the destination set is kept. The rules about the set live in [Destinations]; this reads a
 * string, hands it there, and writes the result back.
 *
 * The stored string is encrypted, because a destination URL ends in a stream key. See
 * [SecretText] for what that protects against.
 *
 * It also carries the app forward from the two shapes it used to store: a single plain
 * destination, and the plain set that replaced it. The first read after an update re-writes
 * whichever is found and clears it, so a pasted stream key survives the change without the user
 * doing anything.
 */
class DestinationStore(context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = context.getString(R.string.pref_destinations_secret_key)
    private val plainKey = context.getString(R.string.pref_destinations_key)
    private val legacyKey = context.getString(R.string.pref_rtmp_key)
    private val secret = SecretText(KEY_ALIAS)

    /** what a destination is called when neither the user nor its URL names it */
    val unnamed: String = context.getString(R.string.destination_unnamed)

    fun read(): Destinations {
        val stored = preferences.getString(key, null)
        if (stored != null) {
            val decrypted = secret.decrypt(stored)
            if (decrypted != null) {
                return Destinations.decode(decrypted)
            }

            /*
             * The key that made this is gone, which is what restoring the app's data onto another
             * device leaves behind. Stream keys cannot be recovered from here, so the set starts
             * empty and the stale value goes rather than being reported on every read.
             */
            Diagnostics.log("stream", "saved destinations could not be read back and were cleared")
            preferences.edit { remove(key) }
            return Destinations()
        }

        return migrate()
    }

    fun write(destinations: Destinations) {
        preferences.edit { putString(key, secret.encrypt(destinations.encode())) }
    }

    /** a fresh id for a destination the user is adding */
    fun newId(): String {
        return UUID.randomUUID().toString()
    }

    private fun migrate(): Destinations {
        val plain = preferences.getString(plainKey, null)
        val carried = if (plain != null) {
            Destinations.decode(plain)
        } else {
            Destinations.fromLegacy(newId(), preferences.getString(legacyKey, null), unnamed)
        }

        preferences.edit {
            putString(key, secret.encrypt(carried.encode()))
            remove(plainKey)
            remove(legacyKey)
        }

        if (carried.entries.isNotEmpty()) {
            Diagnostics.log("stream", "carried the saved destinations into encrypted storage")
        }

        return carried
    }

    private companion object {
        /** names this app's key in the Android Keystore; changing it strands every saved key */
        const val KEY_ALIAS = "destinations"
    }
}
