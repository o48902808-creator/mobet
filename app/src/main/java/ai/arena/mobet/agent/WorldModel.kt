package ai.arena.mobet.agent

import android.content.Context
import org.json.JSONObject

/**
 * Persistent on-device world model: a screen-transition graph learned passively while workflows
 * run. Nodes are [ScreenFingerprint] hashes; edges record which action moved the UI from one
 * screen to another and how often that transition has been observed.
 *
 * The graph never leaves the device, stores no screen text (only fingerprint hashes and action
 * names), and is capped so it cannot grow without bound. It powers diagnostics today and gives
 * future planners a grounded navigation prior ("tap:Network & internet reliably leads to screen
 * 3f2a…") without any network dependency.
 */
class WorldModel(context: Context) {
    private val preferences = context.getSharedPreferences("world_model", Context.MODE_PRIVATE)

    @Synchronized
    fun record(packageName: String, fromScreen: String, action: String, toScreen: String) {
        if (fromScreen == toScreen) return
        val root = load()
        val edges = root.optJSONObject("edges") ?: JSONObject().also { root.put("edges", it) }
        val key = "$packageName|$fromScreen|$action|$toScreen"
        val edge = edges.optJSONObject(key) ?: JSONObject().put("count", 0).also { edges.put(key, it) }
        edge.put("count", edge.optInt("count") + 1)
        edge.put("lastSeen", System.currentTimeMillis())
        prune(edges)
        preferences.edit().putString("graph", root.toString()).apply()
    }

    @Synchronized
    fun summary(): String {
        val edges = load().optJSONObject("edges") ?: return "World model: empty"
        val screens = mutableSetOf<String>()
        var transitions = 0L
        edges.keys().forEach { key ->
            val parts = key.split('|')
            if (parts.size == 4) {
                screens += "${parts[0]}|${parts[1]}"
                screens += "${parts[0]}|${parts[3]}"
            }
            transitions += edges.optJSONObject(key)?.optInt("count")?.toLong() ?: 0L
        }
        return "World model: ${screens.size} screens · ${edges.length()} edges · $transitions observed transitions"
    }

    /** Actions previously observed to leave [fromScreen], most reliable first. */
    @Synchronized
    fun knownActions(packageName: String, fromScreen: String): List<Pair<String, Int>> {
        val edges = load().optJSONObject("edges") ?: return emptyList()
        val results = mutableListOf<Pair<String, Int>>()
        edges.keys().forEach { key ->
            val parts = key.split('|')
            if (parts.size == 4 && parts[0] == packageName && parts[1] == fromScreen) {
                results += parts[2] to (edges.optJSONObject(key)?.optInt("count") ?: 0)
            }
        }
        return results.sortedByDescending { it.second }
    }

    @Synchronized
    fun clear() = preferences.edit().remove("graph").apply()

    private fun load(): JSONObject = try {
        JSONObject(preferences.getString("graph", null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun prune(edges: JSONObject) {
        if (edges.length() <= MAX_EDGES) return
        val keys = edges.keys().asSequence().toList()
        keys.sortedBy { edges.optJSONObject(it)?.optLong("lastSeen") ?: 0L }
            .take(keys.size - MAX_EDGES)
            .forEach(edges::remove)
    }

    private companion object {
        const val MAX_EDGES = 400
    }
}
