package ai.arena.mobet.policy

import ai.arena.mobet.automation.Workflow

data class AutomationPolicy(
    val allowedPackages: Set<String>,
    val allowedActions: Set<String>,
    val maxActions: Int,
    val maxRuntimeMs: Long,
    val allowVisualFallbacks: Boolean
) {
    companion object {
        val DEFAULT_ACTIONS = setOf(
            "wait", "tap", "fill", "scroll", "delay", "confirm", "back", "home"
        )
    }
}

data class PolicyViolation(val step: Int?, val message: String)

/** Mandatory static safety gate for authored, recorded, or AI-proposed plans. */
object PlanValidator {
    private val consequentialWords = Regex(
        "(?i)\\b(submit|send|pay|buy|purchase|order|delete|remove|confirm|book|transfer|post|publish|sign|accept)\\b"
    )
    private val visualActions = setOf("tappoint", "swipe", "capture", "ocrwait", "visualtap")

    fun validate(workflow: Workflow): List<PolicyViolation> = buildList {
        val policy = workflow.policy
        if (workflow.packageName == null) add(PolicyViolation(null, "A target package is required"))
        else if (workflow.packageName !in policy.allowedPackages)
            add(PolicyViolation(null, "Target package is not in policy.allowedPackages"))
        if (workflow.steps.size > policy.maxActions)
            add(PolicyViolation(null, "Plan has ${workflow.steps.size} actions; limit is ${policy.maxActions}"))

        workflow.steps.forEachIndexed { index, step ->
            if (step.action !in policy.allowedActions)
                add(PolicyViolation(index + 1, "Action “${step.action}” is not allowed"))
            if (step.action in visualActions && !policy.allowVisualFallbacks)
                add(PolicyViolation(index + 1, "Visual fallback is disabled by policy"))
            val isConsequentialTap = step.action in setOf("tap", "visualtap", "tappoint") &&
                listOfNotNull(step.selector.text, step.selector.description, step.message)
                    .any(consequentialWords::containsMatchIn)
            val alwaysSensitive = step.action in visualActions
            if ((isConsequentialTap || alwaysSensitive) && workflow.steps.getOrNull(index - 1)?.action != "confirm")
                add(PolicyViolation(index + 1, "Requires an immediately preceding confirm step"))
        }
    }
}
