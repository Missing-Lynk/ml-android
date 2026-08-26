package at.websium.ml

import java.net.URI

/**
 * One streaming target: a name to pick it by, and the ingest URL with the stream key on the end.
 *
 * The label is what anything user-facing shows, which is what keeps a key out of a notification
 * and out of the diagnostics log without having to mask it every time.
 */
data class Destination(
    val id: String,
    val label: String,
    val url: String,
) {
    companion object {
        /**
         * A destination with [label] and [url] reduced to one line each, since the stored form is
         * line-based. A blank label falls back to the URL's host, and to [unnamed] when it
         * carries none, so an entry always has something to show.
         */
        fun of(id: String, label: String, url: String, unnamed: String): Destination {
            val target = oneLine(url)
            val name = oneLine(label).ifBlank { hostOf(target) ?: unnamed }

            return Destination(id = oneLine(id), label = name, url = target)
        }
    }
}

/**
 * The URL's host, or null when it carries none. Used to name a destination the user has not
 * named themselves.
 */
internal fun hostOf(url: String): String? {
    val host = runCatching { URI(url.trim()).host }.getOrNull()
    if (host.isNullOrBlank()) {
        return null
    }

    return host
}

private fun oneLine(value: String): String {
    return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}

/**
 * Every destination the user has saved, and which one is active.
 *
 * Immutable: each operation returns a new value, so the store's job is only to read a string,
 * hand it here, and write the result back. Every rule about the set lives in this file and is
 * exercisable as a JVM test.
 *
 * One invariant holds across every operation: [activeId] names an entry, or is null when there
 * are none. A selection pointing at a deleted entry is therefore unrepresentable.
 *
 * The stored form is line-based rather than JSON, because `org.json` is stubbed in JVM unit
 * tests and this needs no dependency: the first line is the active id, and each line after it is
 * `id`, `label`, `url` separated by tabs. Both separators are stripped from field values by
 * [Destination.of], so no escaping is needed.
 */
data class Destinations(
    val entries: List<Destination> = emptyList(),
    val activeId: String? = null,
) {
    /** the destination the toggle would arm, or null when nothing is saved */
    val active: Destination?
        get() = byId(activeId)

    fun byId(id: String?): Destination? {
        if (id == null) {
            return null
        }

        return entries.firstOrNull { entry -> entry.id == id }
    }

    /**
     * Add [entry], or replace the one with its id. A first entry becomes the active one, since a
     * set with something in it and nothing selected has no use.
     */
    fun with(entry: Destination): Destinations {
        val existing = entries.indexOfFirst { candidate -> candidate.id == entry.id }
        val next = if (existing >= 0) {
            entries.toMutableList().apply { set(existing, entry) }
        } else {
            entries + entry
        }

        return Destinations(next, activeId ?: entry.id)
    }

    /**
     * Remove the entry with [id]. Removing the active one moves the selection to the first
     * remaining entry.
     */
    fun without(id: String): Destinations {
        val next = entries.filterNot { entry -> entry.id == id }
        if (activeId != id) {
            return Destinations(next, activeId)
        }

        return Destinations(next, next.firstOrNull()?.id)
    }

    /**
     * Make [id] active. An id naming no entry leaves the selection alone.
     */
    fun withActive(id: String): Destinations {
        if (byId(id) == null) {
            return this
        }

        return Destinations(entries, id)
    }

    fun encode(): String {
        val lines = mutableListOf(activeId ?: "")
        entries.forEach { entry ->
            lines += "${entry.id}\t${entry.label}\t${entry.url}"
        }

        return lines.joinToString("\n")
    }

    companion object {
        /**
         * Read back [text]. Anything unreadable yields an empty set rather than an error: the
         * value is the app's own and a user with no destinations is a state the app already
         * handles.
         */
        fun decode(text: String?): Destinations {
            if (text.isNullOrEmpty()) {
                return Destinations()
            }

            val lines = text.split("\n")
            val entries = lines.drop(1).mapNotNull { line ->
                val fields = line.split("\t")
                if (fields.size != 3 || fields[0].isBlank()) {
                    return@mapNotNull null
                }

                Destination(id = fields[0], label = fields[1], url = fields[2])
            }

            val stored = lines[0].ifBlank { null }
            val active = entries.firstOrNull { entry -> entry.id == stored }?.id
            return Destinations(entries, active ?: entries.firstOrNull()?.id)
        }

        /**
         * The single destination the app used to store, as a set holding it. [id] is minted by
         * the caller and [unnamed] supplied by it, since neither belongs to this file.
         */
        fun fromLegacy(id: String, url: String?, unnamed: String): Destinations {
            if (url.isNullOrBlank()) {
                return Destinations()
            }

            return Destinations().with(
                Destination.of(id = id, label = "", url = url, unnamed = unnamed)
            )
        }
    }
}
