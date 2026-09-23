package ai.arena.mobet.policy

import ai.arena.mobet.automation.Workflow

data class AutomationPolicy(
    val allowedPackages: Set<String>,
    val allowedActions: Set<String>,
    val maxActions: Int,
    val maxRuntimeMs: Long,
    val allowVisualFallbacks: Boolean,
    val allowSelfHealing: Boolean = false
) {
    companion object {
        val DEFAULT_ACTIONS = setOf(
            "wait", "tap", "fill", "scroll", "delay", "confirm", "back", "home", "launch"
        )
    }
}

data class PolicyViolation(val step: Int?, val message: String)

/**
 * Mandatory static safety gate for authored, recorded, or AI-proposed plans.
 *
 * Risk is scored per step by [RiskEngine]; every step at or above [RiskTier.ELEVATED] must be
 * immediately preceded by a blocking confirm step, and visual fallbacks must be explicitly
 * enabled by policy. This boundary is shared by every plan source and cannot be bypassed.
 */
object PlanValidator {
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
            // `launch` is the only action that can move automation into another app, so the
            // destination must be declared in the same allowlist that bounds the whole run.
            // Without this a recorded or model-proposed plan could escape the target package.
            if (step.action == "launch") {
                val target = step.packageName
                if (target.isNullOrBlank())
                    add(PolicyViolation(index + 1, "launch requires a package"))
                else if (target !in policy.allowedPackages)
                    add(PolicyViolation(index + 1, "launch target “$target” is not in policy.allowedPackages"))
            }
            // Post-step evidence assertions are opt-in, but an incoherent block is an
            // authoring error the runner would otherwise only discover mid-run: an empty
            // expect verifies nothing, and expecting a package outside the allowlist could
            // never be observed true without first crossing the hard package boundary.
            step.expect?.let { expectation ->
                if (expectation.isEmpty) {
                    add(PolicyViolation(index + 1, "expect block declares no assertions"))
                }
                val expectedPackage = expectation.packageIs
                if (expectedPackage != null && expectedPackage !in policy.allowedPackages) {
                    add(
                        PolicyViolation(
                            index + 1,
                            "expect package “$expectedPackage” is not in policy.allowedPackages"
                        )
                    )
                }
            }
            val risk = RiskEngine.assess(step)
            if (risk.tier >= RiskTier.ELEVATED &&
                workflow.steps.getOrNull(index - 1)?.action != "confirm"
            ) {
                val why = risk.reasons.joinToString(", ").ifBlank { "elevated risk" }
                add(PolicyViolation(index + 1, "Requires an immediately preceding confirm step ($why)"))
            }
        }
    }
}
