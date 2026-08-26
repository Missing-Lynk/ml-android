package at.websium.ml

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.UUID

/**
 * Where the destination set is kept. The rules about the set live in [Destinations]; this reads a
 * string, hands it there, and writes the result back.
 *
 * It also carries the app forward from the single destination it used to store: the first read
 * after an update seeds the set from that value and clears it, so a pasted stream key survives
 * the change without the user doing anything.
 */
class DestinationStore(context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = context.getString(R.string.pref_destinations_key)
    private val legacyKey = context.getString(R.string.pref_rtmp_key)

    /** what a destination is called when neither the user nor its URL names it */
    val unnamed: String = context.getString(R.string.destination_unnamed)

    fun read(): Destinations {
        val stored = preferences.getString(key, null)
        if (stored != null) {
            return Destinations.decode(stored)
        }

        return migrate()
    }

    fun write(destinations: Destinations) {
        preferences.edit { putString(key, destinations.encode()) }
    }

    /** a fresh id for a destination the user is adding */
    fun newId(): String {
        return UUID.randomUUID().toString()
    }

    private fun migrate(): Destinations {
        val legacy = preferences.getString(legacyKey, null)
        val carried = Destinations.fromLegacy(newId(), legacy, unnamed)

        preferences.edit {
            putString(key, carried.encode())
            remove(legacyKey)
        }

        if (carried.entries.isNotEmpty()) {
            Diagnostics.log("stream", "carried the saved destination into the list")
        }

        return carried
    }
}
