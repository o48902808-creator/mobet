package ai.arena.mobet.automation

import ai.arena.mobet.policy.AutomationPolicy
import org.json.JSONObject

data class Selector(
    val text: String? = null,
    val viewId: String? = null,
    val description: String? = null
)

/**
 * Post-step evidence assertions (verified execution — docs/FRONTIER.md pillar 2).
 *
 * A step may declare what the screen must look like *after* it executes: a structural change,
 * text that must be present or gone, and/or the package that must be active. The next screen
 * observation after the step is checked by [ExpectationChecker]; a mismatch halts the run
 * with a named reason instead of letting later steps act on an unverified state.
 *
 * Screening is single-shot: the check runs once, at the first observation after the step,
 * because later observations belong to later steps. Timing-sensitive assertions belong in a
 * `wait`/`ocrwait` step before the asserting step.
 */
data class Expectation(
    /** The screen fingerprint must differ from the pre-step baseline (vacuous without one). */
    val screenChange: Boolean = false,
    /** Case-insensitive substring that must appear among visible accessibility labels. */
    val textPresent: String? = null,
    /** Case-insensitive substring that must NOT appear among visible accessibility labels. */
    val textAbsent: String? = null,
    /** The active package after the step; PlanValidator requires membership in allowedPackages. */
    val packageIs: String? = null
) {
    /** A block with no assertions is an authoring error flagged by PlanValidator. */
    val isEmpty: Boolean
        get() = !screenChange && textPresent == null && textAbsent == null && packageIs == null
}

data class Step(
    val action: String,
    val selector: Selector = Selector(),
    val value: String? = null,
    /**
     * Target package for the `launch` action.
     *
     * This is never trusted on its own: [PlanValidator] rejects a launch whose package is not in
     * `policy.allowedPackages`, and the runner re-checks membership immediately before switching
     * apps. Cross-app automation therefore stays inside the allowlist the user authored.
     */
    val packageName: String? = null,
    val timeoutMs: Long = 5_000,
    val delayMs: Long = 300,
    val retries: Int = 0,
    val ifText: String? = null,
    val unlessText: String? = null,
    val message: String? = null,
    val xPercent: Double? = null,
    val yPercent: Double? = null,
    val endXPercent: Double? = null,
    val endYPercent: Double? = null,
    val durationMs: Long = 400,
    /** Optional post-step evidence assertions; null means "no verification declared". */
    val expect: Expectation? = null
)

data class Workflow(
    val name: String,
    val packageName: String?,
    val variables: Map<String, String>,
    val steps: List<Step>,
    val policy: AutomationPolicy
) {
    companion object {
        fun parse(source: String): Workflow {
            val root = JSONObject(source)
            val variablesObject = root.optJSONObject("variables") ?: JSONObject()
            require(variablesObject.length() <= MAX_VARIABLES) {
                "Workflow has ${variablesObject.length()} variables; the limit is $MAX_VARIABLES"
            }
            val variables = buildMap {
                variablesObject.keys().forEach { key -> put(key, variablesObject.getString(key)) }
            }
            val items = root.getJSONArray("steps")
            // Bound before materializing: the editor re-parses the whole document on every
            // keystroke to refresh its summary chips, and policy.maxActions caps execution at
            // 200 anyway, so a larger document can never run — it could only make the main
            // thread grind. Fail the parse early with a clear message instead.
            require(items.length() <= MAX_STEPS) {
                "Workflow has ${items.length()} steps; the limit is $MAX_STEPS"
            }
            val steps = buildList {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    add(
                        Step(
                            action = item.getString("action").lowercase(),
                            selector = Selector(
                                text = optional(item, "text"),
                                viewId = optional(item, "viewId"),
                                description = optional(item, "description")
                            ),
                            value = optional(item, "value"),
                            packageName = optional(item, "package"),
                            timeoutMs = item.optLong("timeoutMs", 5_000).coerceIn(100, 60_000),
                            delayMs = item.optLong("delayMs", 300).coerceIn(0, 10_000),
                            retries = item.optInt("retries", 0).coerceIn(0, 10),
                            ifText = optional(item, "ifText"),
                            unlessText = optional(item, "unlessText"),
                            message = optional(item, "message"),
                            xPercent = percent(item, "xPercent"),
                            yPercent = percent(item, "yPercent"),
                            endXPercent = percent(item, "endXPercent"),
                            endYPercent = percent(item, "endYPercent"),
                            durationMs = item.optLong("durationMs", 400).coerceIn(50, 5_000),
                            expect = item.optJSONObject("expect")?.let { expectation ->
                                Expectation(
                                    screenChange = expectation.optBoolean("screenChange", false),
                                    textPresent = optional(expectation, "textPresent"),
                                    textAbsent = optional(expectation, "textAbsent"),
                                    packageIs = optional(expectation, "package")
                                )
                            }
                        )
                    )
                }
            }
            require(steps.isNotEmpty()) { "A workflow needs at least one step" }
            val targetPackage = optional(root, "package")
            val policyJson = root.optJSONObject("policy") ?: JSONObject()
            val defaultPackages = targetPackage?.let(::setOf) ?: emptySet()
            val allowedPackages = stringSet(policyJson, "allowedPackages", defaultPackages)
            val defaultActions = AutomationPolicy.DEFAULT_ACTIONS
            val policy = AutomationPolicy(
                allowedPackages = allowedPackages,
                allowedActions = stringSet(policyJson, "allowedActions", defaultActions).map(String::lowercase).toSet(),
                maxActions = policyJson.optInt("maxActions", 50).coerceIn(1, 200),
                maxRuntimeMs = policyJson.optLong("maxRuntimeMs", 120_000).coerceIn(5_000, 900_000),
                allowVisualFallbacks = policyJson.optBoolean("allowVisualFallbacks", false),
                allowSelfHealing = policyJson.optBoolean("allowSelfHealing", false)
            )
            return Workflow(
                name = root.optString("name", "Untitled workflow"),
                packageName = targetPackage,
                variables = variables,
                steps = steps,
                policy = policy
            )
        }

        private fun optional(objectValue: JSONObject, key: String): String? =
            objectValue.optString(key).takeIf(String::isNotBlank)

        private fun percent(objectValue: JSONObject, key: String): Double? =
            if (objectValue.has(key)) objectValue.getDouble(key).coerceIn(0.02, 0.98) else null

        private fun stringSet(objectValue: JSONObject, key: String, fallback: Set<String>): Set<String> {
            val array = objectValue.optJSONArray(key) ?: return fallback
            return buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }

        /** Hard parse-time bounds; see the comments at their enforcement points. */
        const val MAX_STEPS = 200
        const val MAX_VARIABLES = 100
    }
}
