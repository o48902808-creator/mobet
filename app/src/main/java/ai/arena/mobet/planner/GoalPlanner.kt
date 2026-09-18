package ai.arena.mobet.planner

import ai.arena.mobet.agent.FuzzyText
import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline, deterministic goal compiler. It deliberately generates only grounded actions whose
 * targets exist in the latest accessibility snapshot. Risk-driven confirmations are inserted by
 * consulting the same [RiskEngine] that gates execution, so the planner can never emit a step the
 * validator would reject for a missing confirmation. Future model planners must produce the same
 * JSON and pass the same PlanValidator boundary.
 */
object GoalPlanner {
    private const val GROUNDING_THRESHOLD = 0.55

    fun generate(goal: String, snapshot: ScreenSnapshot): Result<String> = runCatching {
        require(goal.isNotBlank()) { "Enter a goal" }
        val clauses = goal.split(Regex("(?i)\\s+(?:then|and then)\\s+|[;\\n]+"))
            .map(String::trim).filter(String::isNotBlank)
        require(clauses.isNotEmpty()) { "No actions found in goal" }
        require(clauses.size <= 20) { "Goal exceeds the 20-clause planning limit" }

        val proposed = JSONArray()
        clauses.forEachIndexed { index, clause ->
            val step = parse(clause, snapshot.elements)
                ?: error("Clause ${index + 1} is not grounded in a unique screen element: “$clause”")
            proposed.put(step)
        }

        // Risk pass: insert a blocking confirm before any step the RiskEngine scores ELEVATED+.
        val steps = insertRiskConfirmations(proposed, snapshot, clauses)

        val actions = buildSet {
            add("confirm")
            for (i in 0 until steps.length()) add(steps.getJSONObject(i).getString("action"))
        }
        val root = JSONObject()
            .put("name", "Goal: ${goal.take(60)}")
            .put("package", snapshot.packageName)
            .put("variables", JSONObject())
            .put("policy", JSONObject()
                .put("allowedPackages", JSONArray().put(snapshot.packageName))
                .put("allowedActions", JSONArray(actions.toList()))
                .put("maxActions", (steps.length() + 4).coerceAtMost(50))
                .put("maxRuntimeMs", 120_000)
                .put("allowVisualFallbacks", false)
                .put("allowSelfHealing", false))
            .put("steps", steps)
        val formatted = root.toString(2)
        val workflow = Workflow.parse(formatted)
        val violations = PlanValidator.validate(workflow)
        require(violations.isEmpty()) {
            violations.joinToString("; ") { (it.step?.let { n -> "step $n: " } ?: "") + it.message }
        }
        formatted
    }

    private fun insertRiskConfirmations(
        proposed: JSONArray,
        snapshot: ScreenSnapshot,
        clauses: List<String>
    ): JSONArray {
        // Parse a probe workflow so risk is assessed on exactly the typed steps the runner sees.
        val probe = JSONObject()
            .put("name", "probe")
            .put("package", snapshot.packageName)
            .put("steps", proposed)
        val typedSteps = Workflow.parse(probe.toString()).steps
        val result = JSONArray()
        typedSteps.forEachIndexed { index, typed ->
            val risk = RiskEngine.assess(typed)
            if (risk.tier >= RiskTier.ELEVATED) {
                val clause = clauses.getOrElse(index) { typed.action }
                result.put(
                    JSONObject().put("action", "confirm")
                        .put("message", "Allow: ${clause.take(120)}? (${risk.reasons.joinToString(", ")})")
                )
            }
            result.put(proposed.getJSONObject(index))
        }
        return result
    }

    private fun parse(clause: String, elements: List<InspectedElement>): JSONObject? {
        val quoted = Regex("[\"“”']([^\"“”']+)[\"“”']").findAll(clause)
            .map { it.groupValues[1] }.toList()
        val lower = clause.lowercase().trim()
        if (lower == "go back" || lower == "back" || lower == "press back") {
            return JSONObject().put("action", "back")
        }
        val action = when {
            lower.startsWith("wait") || lower.startsWith("find") || lower.startsWith("locate") -> "wait"
            lower.startsWith("fill") || lower.startsWith("enter") || lower.startsWith("type") -> "fill"
            lower.startsWith("scroll") -> "scroll"
            lower.startsWith("tap") || lower.startsWith("click") || lower.startsWith("open") ||
                lower.startsWith("select") || lower.startsWith("choose") -> "tap"
            else -> return null
        }
        val value = if (action == "fill") {
            Regex("(?i)\\s+with\\s+[\"“”']([^\"“”']+)[\"“”']").find(clause)?.groupValues?.get(1)
                ?: return null
        } else null
        val target = quoted.firstOrNull() ?: clause
            .replace(Regex("(?i)^(tap|click|open|select|choose|scroll(?:\\s+to)?|wait for|wait|find|locate|fill|enter|type)\\s+"), "")
            .replace(Regex("(?i)\\s+with\\s+.*$"), "")
            .trim()
        if (target.isBlank()) return null

        // Fill clauses are grounded only against editable elements; tapping a label named
        // "Email" is not the same as focusing the "Email" input next to it.
        val candidates = if (action == "fill") {
            elements.filter { it.role.contains("Edit", ignoreCase = true) }.ifEmpty { elements }
        } else elements
        val ranked = candidates.map { it to FuzzyText.similarity(target, it.label) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second < GROUNDING_THRESHOLD) return null
        if (ranked.getOrNull(1)?.let { it.second == best.second && it.first.label != best.first.label } == true) return null

        val step = JSONObject().put("action", action)
        applySelector(step, best.first.selector)
        if (value != null) step.put("value", value)
        step.put("timeoutMs", 5_000).put("retries", 1)
        return step
    }

    private fun applySelector(step: JSONObject, selector: String) {
        val key = selector.substringBefore(':')
        val value = selector.substringAfter(':').trim()
        step.put(key, value)
    }
}
