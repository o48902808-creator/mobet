package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.AutomationPolicy
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier
import org.json.JSONArray
import org.json.JSONObject

/** One auditable decision taken by the generation engine, surfaced in the synthesis report. */
data class SynthesisNote(val stage: String, val detail: String) {
    override fun toString(): String = "[$stage] $detail"
}

/** A generated, parsed and policy-validated workflow plus the reasoning that produced it. */
data class SynthesizedWorkflow(
    val json: String,
    val workflow: Workflow,
    val notes: List<SynthesisNote>,
    /** Advisory robustness measurement; never a gate — [PlanValidator] already decided legality. */
    val quality: PlanQuality,
    /**
     * Counterfactual dry-run report, when a snapshot was available to simulate against.
     *
     * Simulating at generation time answers "will step 4 time out?" *before* the plan is
     * inserted, instead of after a failed run.
     */
    val simulation: String? = null
) {
    val stepCount: Int get() = workflow.steps.size

    /** Human-readable rationale shown before the user ever presses Run. */
    fun report(): String = buildString {
        appendLine("Workflow    ${workflow.name}")
        appendLine("Target      ${workflow.packageName}")
        appendLine("Steps       $stepCount / ${workflow.policy.maxActions}")
        appendLine("Actions     ${workflow.policy.allowedActions.sorted().joinToString()}")
        appendLine("Packages    ${workflow.policy.allowedPackages.sorted().joinToString()}")
        appendLine("Runtime     ${workflow.policy.maxRuntimeMs} ms")
        appendLine("Visual      ${if (workflow.policy.allowVisualFallbacks) "allowed" else "blocked"}")
        if (workflow.variables.isNotEmpty()) {
            appendLine("Variables   ${workflow.variables.keys.sorted().joinToString()}")
        }
        appendLine()
        append(quality.summary())
        appendLine()
        appendLine("Synthesis decisions")
        notes.forEach { appendLine("  • ${it.stage}: ${it.detail}") }
        simulation?.let {
            appendLine()
            append(it)
        }
    }
}

/**
 * Shared back half of every generation path (goal text, recorded trace, recipe).
 *
 * Whatever produced the raw steps, they converge here and are subjected to exactly the same
 * pipeline the hand-authored editor path is subjected to: mandatory risk confirmations,
 * least-privilege policy synthesis, a real [Workflow.parse], and [PlanValidator]. Generation
 * therefore cannot mint authority that authoring does not already have — a generated plan that
 * would fail validation is returned as an error, never as a document the user might run.
 */
internal object WorkflowAssembler {

    /** Extra head-room over the emitted step count, for the runner's own bookkeeping. */
    private const val ACTION_HEADROOM = 4

    fun assemble(
        name: String,
        targetPackage: String,
        steps: List<JSONObject>,
        variables: Map<String, String> = emptyMap(),
        extraPackages: Set<String> = emptySet(),
        notes: List<SynthesisNote> = emptyList(),
        maxSteps: Int = 80,
        optimize: Boolean = true,
        parameterizeValues: Boolean = false,
        /** Simulated against this snapshot when present; simulation has no device effects. */
        snapshot: ScreenSnapshot? = null
    ): Result<SynthesizedWorkflow> = runCatching {
        require(steps.isNotEmpty()) { "Synthesis produced no steps" }
        require(steps.size <= maxSteps) { "Synthesis produced ${steps.size} steps; the limit is $maxSteps" }
        require(targetPackage.matches(PACKAGE_PATTERN)) { "Target package is invalid: $targetPackage" }
        extraPackages.forEach {
            require(it.matches(PACKAGE_PATTERN)) { "Referenced package is invalid: $it" }
        }

        val trail = notes.toMutableList()
        // Optimize first (fewer steps to score), parameterize second (risk is assessed on the
        // *substituted* value by the runner anyway), risk-gate last so gates are never optimized
        // away and always sit immediately before the step they guard.
        val lean = if (optimize) PlanOptimizer.optimize(steps, trail) else steps
        val hoisted = if (parameterizeValues) PlanParameterizer.apply(lean, trail) else
            PlanParameterizer.Parameterized(lean, emptyMap())
        val allVariables = variables + hoisted.variables
        val guarded = insertRiskConfirmations(hoisted.steps, targetPackage, trail)
        require(guarded.size <= maxSteps) {
            "Risk confirmations push the plan to ${guarded.size} steps; the limit is $maxSteps"
        }

        val packages = (setOf(targetPackage) + extraPackages).sorted()
        val usedActions = guarded.map { it.getString("action").lowercase() }.toSortedSet()
        val allowedActions = (usedActions + "confirm").toSortedSet()
        val unsupported = allowedActions.filterNot { it in AutomationPolicy.DEFAULT_ACTIONS }
        require(unsupported.isEmpty()) {
            "Synthesis emitted actions outside the default-allowed set: ${unsupported.joinToString()}"
        }

        val runtime = estimateRuntimeMs(guarded)
        trail += SynthesisNote(
            "policy",
            "least-privilege: ${allowedActions.joinToString()} over ${packages.joinToString()}, " +
                "≤ ${guarded.size + ACTION_HEADROOM} actions, ≤ ${runtime / 1000}s"
        )

        val root = JSONObject()
            .put("name", name.trim().take(80).ifBlank { "Generated workflow" })
            .put("package", targetPackage)
            .put("variables", JSONObject(allVariables as Map<*, *>))
            .put(
                "policy",
                JSONObject()
                    .put("allowedPackages", JSONArray(packages))
                    .put("allowedActions", JSONArray(allowedActions.toList()))
                    .put("maxActions", (guarded.size + ACTION_HEADROOM).coerceIn(1, 200))
                    .put("maxRuntimeMs", runtime)
                    .put("allowVisualFallbacks", false)
                    .put("allowSelfHealing", false)
            )
            .put("steps", JSONArray(guarded))

        val formatted = root.toString(2)
        val workflow = Workflow.parse(formatted)
        val violations = PlanValidator.validate(workflow)
        require(violations.isEmpty()) {
            "Generated plan failed policy validation: " + violations.joinToString("; ") { violation ->
                (violation.step?.let { "step $it: " } ?: "") + violation.message
            }
        }
        val simulation = snapshot?.let { runCatching { PlanSimulator.simulate(workflow, it) }.getOrNull() }
        SynthesizedWorkflow(formatted, workflow, trail, PlanQuality.analyze(workflow), simulation)
    }

    /**
     * Inserts a blocking confirm before every step the [RiskEngine] scores ELEVATED or above,
     * using the same rule [PlanValidator] enforces — including the per-option rule for
     * `tryAlternates`, whose options are scored as if each were a tap.
     *
     * When the risky step carries a control-flow label, the label moves to the confirm step.
     * Otherwise a `branch`/`repeatUntil` jump would land *after* the confirmation and silently
     * bypass the gate the risk pass just installed.
     */
    private fun insertRiskConfirmations(
        steps: List<JSONObject>,
        targetPackage: String,
        notes: MutableList<SynthesisNote>
    ): List<JSONObject> {
        val probe = JSONObject()
            .put("name", "risk-probe")
            .put("package", targetPackage)
            .put("steps", JSONArray(steps))
        val typed = Workflow.parse(probe.toString()).steps
        val result = mutableListOf<JSONObject>()
        typed.forEachIndexed { index, step ->
            val direct = RiskEngine.assess(step)
            val optionRisks = step.options.map { option ->
                RiskEngine.assess(step.copy(action = "tap", selector = option, options = emptyList()))
            }
            val worst = (optionRisks + direct).maxByOrNull { it.score } ?: direct
            val raw = JSONObject(steps[index].toString())
            if (worst.tier >= RiskTier.ELEVATED && result.lastOrNull()?.optString("action") != "confirm") {
                val why = worst.reasons.joinToString(", ").ifBlank { "elevated risk" }
                val subject = describe(raw)
                val confirm = JSONObject()
                    .put("action", "confirm")
                    .put("message", "Allow $subject? ($why)".take(200))
                // Preserve jump semantics: a labelled risky step keeps its label on the gate.
                raw.optString("label").takeIf(String::isNotBlank)?.let { label ->
                    confirm.put("label", label)
                    raw.remove("label")
                }
                result += confirm
                notes += SynthesisNote(
                    "risk",
                    "confirmation inserted before $subject (score ${worst.score}, $why)"
                )
            }
            result += raw
        }
        return result
    }

    private fun describe(step: JSONObject): String {
        val action = step.optString("action")
        val target = listOf("text", "description", "viewId")
            .firstNotNullOfOrNull { key -> step.optString(key).takeIf(String::isNotBlank) }
        return if (target == null) action else "$action “${target.take(60)}”"
    }

    /** Budget from the plan's own declared waits, with slack; always inside the parser's range. */
    private fun estimateRuntimeMs(steps: List<JSONObject>): Long {
        val declared = steps.sumOf { step ->
            step.optLong("timeoutMs", 5_000) + step.optLong("delayMs", 300) +
                step.optLong("durationMs", 0)
        }
        return (declared + 30_000).coerceIn(5_000, 900_000)
    }

    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
}
