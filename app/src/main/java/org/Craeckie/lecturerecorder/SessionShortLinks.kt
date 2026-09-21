package org.Craeckie.lecturerecorder

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// One remembered lecture session: the name from its short link
// (https://lt2srv.iar.kit.edu/webapi/shorten/<name>) and when it was last opened, so the
// list can show the most recently used session first.
data class SessionShortLink(val name: String, val lastOpened: Long)

// Pure name<->URL logic for the "list of sessions" feature -- see CaptureModes for the
// sibling pattern (a pure object plus a thin persistence wrapper). Kept separate from
// MainActivity so it is unit-testable off-device like CaptureModes and MicRoutingGate are.
object SessionShortLinks {
    private const val HOST = "lt2srv.iar.kit.edu"
    private const val PATH_PREFIX = "/webapi/shorten/"

    // Accepts either a full https://lt2srv.iar.kit.edu/webapi/shorten/<name> URL or a bare
    // <name> typed/pasted by hand. Rejects everything else -- other hosts, other paths on
    // this host (in particular /present/<id>, which is deliberately not collected: that id
    // is what redactSessionIds strips, and a presenter page can't be reopened after End
    // Lecture anyway), and an empty name.
    //
    // Plain string parsing rather than android.net.Uri: this object is exercised by a bare
    // JVM unit test (SessionShortLinksTest), where Android framework classes throw
    // "not mocked" -- the same reason CaptureModes never touches one.
    fun nameFrom(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains("://")) {
            return trimmed.takeIf { it.isNotEmpty() && !it.contains('/') }
        }
        val withoutScheme = when {
            trimmed.startsWith("https://") -> trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> trimmed.removePrefix("http://")
            else -> return null
        }
        val slashIndex = withoutScheme.indexOf('/')
        val host = if (slashIndex < 0) withoutScheme else withoutScheme.substring(0, slashIndex)
        if (host != HOST) return null
        val pathAndQuery = if (slashIndex < 0) "" else withoutScheme.substring(slashIndex)
        val path = pathAndQuery.substringBefore('?')
        if (!path.startsWith(PATH_PREFIX)) return null
        val name = path.removePrefix(PATH_PREFIX).trim('/')
        return name.takeIf { it.isNotEmpty() }
    }

    fun urlFor(name: String): String = "https://$HOST$PATH_PREFIX$name"

    // Pure: moves/inserts `name` to the front with lastOpened = now, deduplicated by name.
    // Takes and returns a plain list so the store (below) and tests can share this without
    // either depending on Android.
    fun remember(entries: List<SessionShortLink>, name: String, now: Long): List<SessionShortLink> {
        val rest = entries.filterNot { it.name == name }
        return listOf(SessionShortLink(name, now)) + rest
    }
}

// Persists the remembered session list across launches, the same shape as
// CaptureModePreference: one SharedPreferences file, JSON in a single string value. Exposes
// plain reads/writes rather than Compose state directly, so the composable holding the live
// list (shared by the start screen and the in-site chip) owns exactly one source of truth.
class SessionShortLinkStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<SessionShortLink> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SessionShortLink(obj.getString("name"), obj.getLong("lastOpened"))
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Discarding unreadable session-link list: ${e.message}")
            emptyList()
        }
    }

    private fun save(entries: List<SessionShortLink>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("name", entry.name).put("lastOpened", entry.lastOpened))
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    // Returns the updated list so the caller can push it straight into Compose state
    // without a second load().
    fun remember(name: String, now: Long = System.currentTimeMillis()): List<SessionShortLink> {
        val updated = SessionShortLinks.remember(load(), name, now)
        save(updated)
        Log.i(LOG_TAG, "Session short link remembered: $name")
        return updated
    }

    fun removeAll(names: Set<String>): List<SessionShortLink> {
        val updated = load().filterNot { it.name in names }
        save(updated)
        return updated
    }

    private companion object {
        const val PREFS_NAME = "session-links"
        const val KEY_ENTRIES = "entries"
    }
}
