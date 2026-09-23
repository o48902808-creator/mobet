package ai.arena.mobet.automation

import org.json.JSONObject

/** Structured, privacy-minimized snapshot of why a run is taking its current action. */
data class ExecutionTimelineEvent(
    val state: State,
    val mode: String,
    val goal: String,
    val subgoal: String? = null,
    val step: Int? = null,
    val totalSteps: Int? = null,
    val screenFingerprint: String? = null,
    val action: String? = null,
    val confidence: Int? = null,
    val evidence: String? = null,
    val risk: String? = null,
    val policy: String? = null,
    val recovery: String? = null,
    val stopReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class State { PLANNING, RUNNING, WAITING, RECOVERING, SUCCEEDED, HALTED }

    fun toJson(): String = JSONObject()
        .put("state", state.name)
        .put("mode", mode.take(MAX_TEXT))
        .put("goal", goal.take(MAX_TEXT))
        .putOpt("subgoal", subgoal?.take(MAX_TEXT))
        .putOpt("step", step)
        .putOpt("totalSteps", totalSteps)
        .putOpt("screenFingerprint", screenFingerprint?.take(MAX_FINGERPRINT))
        .putOpt("action", action?.take(MAX_TEXT))
        .putOpt("confidence", confidence?.coerceIn(0, 100))
        .putOpt("evidence", evidence?.take(MAX_TEXT))
        .putOpt("risk", risk?.take(MAX_TEXT))
        .putOpt("policy", policy?.take(MAX_TEXT))
        .putOpt("recovery", recovery?.take(MAX_TEXT))
        .putOpt("stopReason", stopReason?.take(MAX_TEXT))
        .put("timestamp", timestamp)
        .toString()

    companion object {
        private const val MAX_TEXT = 300
        private const val MAX_FINGERPRINT = 64

        fun parse(source: String): ExecutionTimelineEvent {
            require(source.length <= 8_192) { "Timeline event is too large" }
            val json = JSONObject(source)
            fun optional(key: String): String? = json.optString(key).takeIf(String::isNotBlank)
            fun optionalInt(key: String): Int? = if (json.has(key) && !json.isNull(key)) json.getInt(key) else null
            return ExecutionTimelineEvent(
                state = State.valueOf(json.getString("state")),
                mode = json.getString("mode").take(MAX_TEXT),
                goal = json.getString("goal").take(MAX_TEXT),
                subgoal = optional("subgoal")?.take(MAX_TEXT),
                step = optionalInt("step")?.takeIf { it >= 0 },
                totalSteps = optionalInt("totalSteps")?.takeIf { it >= 0 },
                screenFingerprint = optional("screenFingerprint")?.take(MAX_FINGERPRINT),
                action = optional("action")?.take(MAX_TEXT),
                confidence = optionalInt("confidence")?.coerceIn(0, 100),
                evidence = optional("evidence")?.take(MAX_TEXT),
                risk = optional("risk")?.take(MAX_TEXT),
                policy = optional("policy")?.take(MAX_TEXT),
                recovery = optional("recovery")?.take(MAX_TEXT),
                stopReason = optional("stopReason")?.take(MAX_TEXT),
                timestamp = json.optLong("timestamp", 0L).coerceAtLeast(0L)
            )
        }
    }
}

/** Stable human-readable evidence summary that never includes fill values or secret contents. */
fun Step.timelineEvidence(): String = expect?.let { expected ->
    buildList {
        if (expected.screenChange) add("screen changed")
        if (expected.textPresent != null) add("declared text present")
        if (expected.textAbsent != null) add("declared text absent")
        expected.packageIs?.let { add("allowed package active: ${it.take(100)}") }
    }.joinToString(" · ").ifBlank { "post-action observation" }
} ?: when (action) {
    "wait", "ocrwait" -> "matching evidence observed"
    "confirm" -> "explicit user approval"
    else -> "screen transition and action result"
}
