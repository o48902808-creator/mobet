package ai.arena.mobet.audit

import org.json.JSONArray
import org.json.JSONObject

/** Portable, redacted evidence bundle. It contains no screen tree, screenshot, or secret value. */
object LedgerExport {
    fun json(entries: List<LedgerEntry>, build: String, verified: Boolean): String = JSONObject()
        .put("schema", "mobet.ledger.v1")
        .put("build", build)
        .put("verified", verified)
        .put("head", entries.lastOrNull()?.hash ?: "")
        .put("entries", JSONArray().also { array -> entries.forEach { e ->
            array.put(JSONObject().put("seq", e.sequence).put("ts", e.timestamp)
                .put("event", redact(e.event)).put("hash", e.hash).put("prev", e.previousHash))
        } }).toString(2)

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(secret|token|password|api[_ -]?key)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
        .replace(Regex("\\{\\{secret:[^}]+}}"), "[REDACTED]")
}
