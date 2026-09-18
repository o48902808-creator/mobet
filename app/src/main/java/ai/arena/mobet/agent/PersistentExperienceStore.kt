package ai.arena.mobet.agent

import android.content.Context
import ai.arena.mobet.security.EncryptedStateStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.exp

/**
 * Private, bounded episodic/semantic memory. It stores hashes and action identifiers, not screen
 * text, OCR output, entered values, or screenshots. Confidence decays with age and unsuccessful
 * outcomes. App major-version changes and structural drift invalidate incompatible knowledge.
 */
class PersistentExperienceStore(context: Context, private val capacity: Int = 600) : ExperienceStore {
    private val secureStore = EncryptedStateStore(context, "agent_memory_v3")
    private val legacyPreferences = context.getSharedPreferences("agent_memory_v2", Context.MODE_PRIVATE)

    init {
        // One-time authenticated migration; delete plaintext only after the encrypted commit lands.
        if (secureStore.read() == null) {
            legacyPreferences.getString("memory", null)?.let { legacy ->
                if (secureStore.write(legacy)) legacyPreferences.edit().clear().commit()
            }
        }
    }

    @Synchronized override fun record(experience: TransitionExperience) {
        val root = load()
        val episodes = root.optJSONArray("episodes") ?: JSONArray().also { root.put("episodes", it) }
        episodes.put(JSONObject().apply {
            put("from", experience.from); put("action", safeId(experience.actionId)); put("to", experience.to)
            put("progressed", experience.progressed); put("package", experience.packageName)
            put("version", experience.appVersion); put("at", experience.observedAt)
            put("confidence", experience.confidence.coerceIn(0.0, 1.0)); put("failure", experience.failure?.name)
        })
        while (episodes.length() > capacity) episodes.remove(0)
        invalidateContradictedRoutes(root, experience)
        save(root)
    }

    @Synchronized override fun transitionsFrom(screenId: String): List<TransitionExperience> {
        val now = System.currentTimeMillis()
        val episodes = load().optJSONArray("episodes") ?: return emptyList()
        return buildList {
            for (i in 0 until episodes.length()) {
                val item = episodes.optJSONObject(i) ?: continue
                if (item.optString("from") != screenId) continue
                val ageDays = (now - item.optLong("at", now)).coerceAtLeast(0) / DAY_MS.toDouble()
                val decayed = item.optDouble("confidence", 1.0) * exp(-ageDays / HALF_LIFE_DAYS)
                if (decayed < MIN_CONFIDENCE) continue
                add(TransitionExperience(item.optString("from"), item.optString("action"), item.optString("to"),
                    item.optBoolean("progressed"), item.optString("package"), item.optString("version").ifBlank { null },
                    item.optLong("at"), decayed,
                    failure = item.optString("failure").takeIf(String::isNotBlank)?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }))
            }
        }
    }

    @Synchronized override fun markDeadEnd(screenId: String, actionId: String) {
        val root = load(); val dead = root.optJSONArray("dead") ?: JSONArray().also { root.put("dead", it) }
        dead.put(JSONObject().put("screen", screenId).put("action", safeId(actionId)).put("at", System.currentTimeMillis()))
        while (dead.length() > capacity) dead.remove(0)
        save(root)
    }

    @Synchronized override fun isDeadEnd(screenId: String, actionId: String): Boolean {
        val dead = load().optJSONArray("dead") ?: return false
        val id = safeId(actionId); val now = System.currentTimeMillis()
        for (i in 0 until dead.length()) {
            val item = dead.optJSONObject(i) ?: continue
            if (item.optString("screen") == screenId && item.optString("action") == id &&
                now - item.optLong("at") < DEAD_END_TTL_MS) return true
        }
        return false
    }

    @Synchronized override fun recordRepair(packageName: String, oldSelector: String, newSelector: String, appVersion: String?) {
        val root = load(); val repairs = root.optJSONArray("repairs") ?: JSONArray().also { root.put("repairs", it) }
        val oldHash = hash(oldSelector); val newHash = hash(newSelector)
        var found: JSONObject? = null
        for (i in 0 until repairs.length()) {
            val candidate = repairs.optJSONObject(i)
            if (candidate?.optString("package") == packageName && candidate.optString("old") == oldHash && candidate.optString("new") == newHash) found = candidate
        }
        val item = found ?: JSONObject().put("package", packageName).put("old", oldHash).put("new", newHash)
            .put("successes", 0).also { repairs.put(it) }
        item.put("version", appVersion).put("successes", item.optInt("successes") + 1).put("at", System.currentTimeMillis())
        while (repairs.length() > 120) repairs.remove(0)
        save(root)
    }

    @Synchronized override fun invalidate(packageName: String, appVersion: String?, screenId: String?) {
        val root = load(); val previous = root.optJSONObject("versions")?.optString(packageName)
        val majorChanged = previous?.substringBefore('.')?.let { old ->
            appVersion != null && appVersion.substringBefore('.') != old
        } ?: false
        if (majorChanged || screenId != null) {
            root.put("episodes", filter(root.optJSONArray("episodes")) {
                it.optString("package") != packageName || (!majorChanged && it.optString("from") != screenId && it.optString("to") != screenId)
            })
            if (majorChanged) root.put("repairs", filter(root.optJSONArray("repairs")) { it.optString("package") != packageName })
        }
        val versions = root.optJSONObject("versions") ?: JSONObject().also { root.put("versions", it) }
        if (appVersion != null) versions.put(packageName, appVersion)
        save(root)
    }

    @Synchronized override fun summary(): String {
        val root = load(); val episodes = root.optJSONArray("episodes") ?: JSONArray()
        var success = 0
        for (i in 0 until episodes.length()) if (episodes.optJSONObject(i)?.optBoolean("progressed") == true) success++
        val rate = if (episodes.length() == 0) 0 else success * 100 / episodes.length()
        return "Encrypted agent memory: ${episodes.length()} episodes · $rate% progress · ${root.optJSONArray("dead")?.length() ?: 0} dead ends · ${root.optJSONArray("repairs")?.length() ?: 0} repairs · ${root.optInt("driftInvalidations")} drift invalidations"
    }

    @Synchronized fun clear() { secureStore.clear(); legacyPreferences.edit().clear().commit() }

    /** Three recent contradictions on a known screen constitute structural UI drift. */
    private fun invalidateContradictedRoutes(root: JSONObject, latest: TransitionExperience) {
        if (latest.progressed) return
        val episodes = root.optJSONArray("episodes") ?: return
        var consecutiveFailures = 0
        for (i in episodes.length() - 1 downTo 0) {
            val item = episodes.optJSONObject(i) ?: continue
            if (item.optString("package") != latest.packageName || item.optString("from") != latest.from) continue
            if (item.optBoolean("progressed")) break
            consecutiveFailures++
            if (consecutiveFailures >= UI_DRIFT_FAILURES) break
        }
        if (consecutiveFailures < UI_DRIFT_FAILURES) return
        root.put("episodes", filter(episodes) {
            it.optString("package") != latest.packageName || it.optString("from") != latest.from
        })
        root.put("driftInvalidations", root.optInt("driftInvalidations") + 1)
    }

    private fun filter(source: JSONArray?, keep: (JSONObject) -> Boolean): JSONArray = JSONArray().also { output ->
        source ?: return@also
        for (i in 0 until source.length()) source.optJSONObject(i)?.takeIf(keep)?.let { output.put(it) }
    }
    private fun safeId(value: String) = if (value.length <= 96 && !value.contains("{{secret:")) value else "sha256:${hash(value)}"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun load() = runCatching { JSONObject(secureStore.read() ?: "{}") }.getOrDefault(JSONObject())
    private fun save(root: JSONObject) { secureStore.write(root.toString()) }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HALF_LIFE_DAYS = 45.0
        const val MIN_CONFIDENCE = 0.18
        const val DEAD_END_TTL_MS = 14L * DAY_MS
        const val UI_DRIFT_FAILURES = 3
    }
}
