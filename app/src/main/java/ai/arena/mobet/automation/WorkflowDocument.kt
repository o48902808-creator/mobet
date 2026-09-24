package ai.arena.mobet.automation

import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.PolicyViolation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, view-free transformations of the workflow document the editor holds.
 *
 * These operations used to live inside `MainActivity`, where they were untestable: retargeting a
 * plan and merging recorded steps both edit `policy.allowedPackages`, which is a *security*
 * boundary, and neither had a unit test because the logic was tangled with `EditText` state and
 * status toasts. Here they are ordinary functions over JSON text, with the activity reduced to
 * rendering their results.
 */
object WorkflowDocument {

    /** Parsed read-out behind the editor's summary chips; [parseError] set means nothing else is. */
    data class Summary(
        val packageName: String?,
        val stepCount: Int,
        val maxActions: Int,
        val runtimeSeconds: Long,
        val visualFallbacks: Boolean,
        val selfHealing: Boolean,
        val violations: List<PolicyViolation>,
        val parseError: String? = null
    ) {
        val isValid: Boolean get() = parseError == null && violations.isEmpty()
    }

    /** Returns null for an empty document — there is nothing to say about it yet. */
    fun summarize(source: String): Summary? {
        if (source.isBlank()) return null
        val parsed = runCatching { Workflow.parse(source) }
        val workflow = parsed.getOrNull()
            ?: return Summary(
                packageName = null,
                stepCount = 0,
                maxActions = 0,
                runtimeSeconds = 0,
                visualFallbacks = false,
                selfHealing = false,
                violations = emptyList(),
                parseError = parsed.exceptionOrNull()?.message ?: "parse error"
            )
        return Summary(
            packageName = workflow.packageName,
            stepCount = workflow.steps.size,
            maxActions = workflow.policy.maxActions,
            runtimeSeconds = workflow.policy.maxRuntimeMs / 1_000,
            visualFallbacks = workflow.policy.allowVisualFallbacks,
            selfHealing = workflow.policy.allowSelfHealing,
            violations = runCatching { PlanValidator.validate(workflow) }.getOrDefault(emptyList())
        )
    }

    /**
     * Points the document at [target].
     *
     * The previous target is *removed* from `policy.allowedPackages` while any additional launch
     * targets are preserved: retargeting should not silently leave the old app authorised, and it
     * should not drop a deliberate cross-app allowance either.
     */
    fun retarget(source: String, target: String): String {
        require(target.isNotBlank()) { "A target package is required" }
        val root = JSONObject(source)
        val previous = root.optString("package").takeIf(String::isNotBlank)
        root.put("package", target)
        val policy = root.optJSONObject("policy") ?: JSONObject().also { root.put("policy", it) }
        val packages = allowedPackages(policy)
        if (previous != null) packages.remove(previous)
        packages.add(target)
        policy.put("allowedPackages", JSONArray(packages.toList()))
        return root.toString(2)
    }

    /**
     * Appends [steps] to the document, widening the allowlist to include [target].
     *
     * Widening is additive and explicit: an import can add the app it was recorded in, but it
     * never removes an existing allowance and never changes an already-declared target package.
     */
    fun appendSteps(source: String, steps: JSONArray, target: String? = null): String {
        val root = JSONObject(source)
        val existing = root.optJSONArray("steps") ?: JSONArray().also { root.put("steps", it) }
        for (i in 0 until steps.length()) existing.put(steps.getJSONObject(i))
        if (target != null && target.isNotBlank()) {
            if (root.optString("package").isBlank()) root.put("package", target)
            val policy = root.optJSONObject("policy") ?: JSONObject().also { root.put("policy", it) }
            val packages = allowedPackages(policy)
            packages.add(target)
            policy.put("allowedPackages", JSONArray(packages.toList()))
        }
        return root.toString(2)
    }

    private fun allowedPackages(policy: JSONObject): LinkedHashSet<String> {
        val allowed = policy.optJSONArray("allowedPackages")
        val packages = linkedSetOf<String>()
        if (allowed != null) for (i in 0 until allowed.length()) packages.add(allowed.getString(i))
        return packages
    }
}
