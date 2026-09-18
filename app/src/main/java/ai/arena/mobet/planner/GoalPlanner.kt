package ai.arena.mobet.planner

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline, deterministic goal compiler. It deliberately generates only grounded actions whose
 * targets exist in the latest accessibility snapshot. Future model planners must produce the same
 * JSON and pass the same PlanValidator boundary.
 */
object GoalPlanner {
    private val consequence = Regex(
        "(?i)\\b(submit|send|pay|buy|purchase|order|delete|remove|confirm|book|transfer|post|publish|sign|accept)\\b"
    )

    fun generate(goal: String, snapshot: ScreenSnapshot): Result<String> = runCatching {
        require(goal.isNotBlank()) { "Enter a goal" }
        val clauses = goal.split(Regex("(?i)\\s+(?:then|and then)\\s+|[;\\n]+"))
            .map(String::trim).filter(String::isNotBlank)
        require(clauses.isNotEmpty()) { "No actions found in goal" }
        require(clauses.size <= 20) { "Goal exceeds the 20-clause planning limit" }

        val steps = JSONArray()
        clauses.forEachIndexed { index, clause ->
            val instruction = parse(clause, snapshot.elements)
                ?: error("Clause ${index + 1} is not grounded in a unique screen element: “$clause”")
            if (instruction.consequential) {
                steps.put(JSONObject().put("action", "confirm")
                    .put("message", "Allow: ${clause.take(120)}?"))
            }
            steps.put(instruction.step)
        }

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
                .put("allowVisualFallbacks", false))
            .put("steps", steps)
        val formatted = root.toString(2)
        val workflow = Workflow.parse(formatted)
        val violations = PlanValidator.validate(workflow)
        require(violations.isEmpty()) {
            violations.joinToString("; ") { (it.step?.let { n -> "step $n: " } ?: "") + it.message }
        }
        formatted
    }

    private fun parse(clause: String, elements: List<InspectedElement>): Instruction? {
        val quoted = Regex("[\"“”']([^\"“”']+)[\"“”']").findAll(clause)
            .map { it.groupValues[1] }.toList()
        val lower = clause.lowercase()
        val action = when {
            lower.startsWith("wait") || lower.startsWith("find") || lower.startsWith("locate") -> "wait"
            lower.startsWith("fill") || lower.startsWith("enter") || lower.startsWith("type") -> "fill"
            lower.startsWith("tap") || lower.startsWith("click") || lower.startsWith("open") ||
                lower.startsWith("select") || lower.startsWith("choose") -> "tap"
            else -> return null
        }
        val value = if (action == "fill") {
            Regex("(?i)\\s+with\\s+[\"“”']([^\"“”']+)[\"“”']").find(clause)?.groupValues?.get(1)
                ?: return null
        } else null
        val target = quoted.firstOrNull() ?: clause
            .replace(Regex("(?i)^(tap|click|open|select|choose|wait for|wait|find|locate|fill|enter|type)\\s+"), "")
            .replace(Regex("(?i)\\s+with\\s+.*$"), "")
            .trim()
        val ranked = elements.map { it to similarity(target, it.label) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second < 0.55) return null
        if (ranked.getOrNull(1)?.let { it.second == best.second && it.first.label != best.first.label } == true) return null

        val step = JSONObject().put("action", action)
        applySelector(step, best.first.selector)
        if (value != null) step.put("value", value)
        step.put("timeoutMs", 5_000).put("retries", 1)
        return Instruction(step, consequence.containsMatchIn(clause) || consequence.containsMatchIn(best.first.label))
    }

    private fun applySelector(step: JSONObject, selector: String) {
        val key = selector.substringBefore(':')
        val value = selector.substringAfter(':').trim()
        step.put(key, value)
    }

    private fun similarity(target: String, label: String): Double {
        val a = normalize(target)
        val b = normalize(label)
        if (a == b) return 1.0
        if (b.contains(a) || a.contains(b)) return 0.88
        val left = a.split(' ').filter(String::isNotBlank).toSet()
        val right = b.split(' ').filter(String::isNotBlank).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class Instruction(val step: JSONObject, val consequential: Boolean)
}
