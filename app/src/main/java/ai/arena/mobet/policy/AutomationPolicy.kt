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
            "wait", "tap", "fill", "scroll", "delay", "confirm", "back", "home", "launch",
            // Bounded control flow: decide-only actions, billed to their own hop rail at run
            // time. They are default-allowed because they cannot touch the device; the device
            // gates (confirm, allowlist, budgets) all still apply to what they steer.
            "branch", "repeatuntil", "tryalternates"
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

        // Control-flow jump table, computed once for the whole plan. Duplicate labels would
        // make a goto ambiguous, so they are violations wherever the label sits.
        val labelIndices = buildMap<String, Int> {
            workflow.steps.forEachIndexed { index, step ->
                step.label?.let { label -> put(label, index) }
            }
        }
        val labelCounts = workflow.steps.mapNotNull { it.label }.groupingBy { it }.eachCount()

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
            // Bounded control flow (docs/FRONTIER.md pillar 3): every jump must resolve,
            // every control step must carry its condition, repeats must aim backwards
            // (forward "repeats" are definitionally infinite), and jump fields on ordinary
            // actions are dead authoring rather than inert decorations.
            if (step.label != null && (labelCounts[step.label] ?: 0) > 1) {
                add(PolicyViolation(index + 1, "Duplicate step label “${step.label}”"))
            }
            fun unresolved(name: String?): Boolean = name != null && name !in labelIndices
            if (unresolved(step.goto)) {
                add(PolicyViolation(index + 1, "goto label “${step.goto}” does not exist"))
            }
            if (unresolved(step.elseGoto)) {
                add(PolicyViolation(index + 1, "elseGoto label “${step.elseGoto}” does not exist"))
            }
            when (step.action) {
                "branch" -> {
                    if (step.expect == null) add(PolicyViolation(index + 1, "branch requires an expect condition"))
                    if (step.goto == null) add(PolicyViolation(index + 1, "branch requires a goto label"))
                    if (step.elseGoto != null && step.elseGoto == step.goto)
                        add(PolicyViolation(index + 1, "branch goto and elseGoto point at the same label"))
                }
                "repeatuntil" -> {
                    if (step.expect == null) add(PolicyViolation(index + 1, "repeatUntil requires an expect condition"))
                    if (step.goto == null) add(PolicyViolation(index + 1, "repeatUntil requires a goto label"))
                    else labelIndices[step.goto]?.let { target ->
                        if (target >= index) add(
                            PolicyViolation(index + 1, "repeatUntil must jump backwards (goto aims at step ${target + 1})")
                        )
                    }
                }
                "tryalternates" -> {
                    step.options.forEachIndexed { optionIndex, option ->
                        if (option.text == null && option.viewId == null && option.description == null) {
                            add(PolicyViolation(index + 1, "tryAlternates option ${optionIndex + 1} has no selector fields"))
                        }
                        val asTap = step.copy(action = "tap", selector = option, options = emptyList())
                        if (RiskEngine.assess(asTap).tier >= RiskTier.ELEVATED &&
                            workflow.steps.getOrNull(index - 1)?.action != "confirm"
                        ) {
                            add(
                                PolicyViolation(
                                    index + 1,
                                    "tryAlternates option ${optionIndex + 1} requires an immediately preceding confirm step"
                                )
                            )
                        }
                    }
                }
                else -> {
                    if (step.goto != null || step.elseGoto != null) add(
                        PolicyViolation(index + 1, "goto labels are only meaningful on branch/repeatUntil")
                    )
                    if (step.options.isNotEmpty()) add(
                        PolicyViolation(index + 1, "options are only meaningful on tryAlternates")
                    )
                }
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
