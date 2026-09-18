package ai.arena.mobet.planner

import ai.arena.mobet.agent.FuzzyText
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier

/**
 * Counterfactual dry run: statically walks a plan against the latest accessibility snapshot
 * without touching the device. For each step it reports grounding against live elements, the
 * risk tier the runner will enforce, and where user confirmations will block. The output is a
 * human-readable preflight report so consequences are visible before a single action fires.
 */
object PlanSimulator {
    fun simulate(workflow: Workflow, snapshot: ScreenSnapshot?): String = buildString {
        appendLine("DRY RUN · ${workflow.name}")
        appendLine("Target: ${workflow.packageName ?: "(none)"}")
        appendLine(
            "Budget: ${workflow.steps.size}/${workflow.policy.maxActions} actions · " +
                "${workflow.policy.maxRuntimeMs / 1000}s runtime cap"
        )
        appendLine(
            "Self-healing: ${if (workflow.policy.allowSelfHealing) "enabled" else "disabled"} · " +
                "Visual fallbacks: ${if (workflow.policy.allowVisualFallbacks) "enabled" else "disabled"}"
        )

        val violations = PlanValidator.validate(workflow)
        if (violations.isEmpty()) appendLine("Policy: ✔ approved")
        else {
            appendLine("Policy: ✖ ${violations.size} violation(s)")
            violations.forEach {
                appendLine("   • ${(it.step?.let { s -> "step $s: " } ?: "")}${it.message}")
            }
        }

        val snapshotUsable = snapshot != null && snapshot.packageName == workflow.packageName
        appendLine(
            when {
                snapshot == null -> "Grounding: no screen snapshot yet — visit the target app to ground selectors"
                !snapshotUsable -> "Grounding: snapshot is from ${snapshot.packageName}, not the target — grounding skipped"
                else -> "Grounding: against ${snapshot.elements.size} live elements of ${snapshot.packageName}"
            }
        )
        appendLine()

        var worstTier = RiskTier.NONE
        var confirmations = 0
        var estimatedMs = 0L
        workflow.steps.forEachIndexed { index, step ->
            val risk = RiskEngine.assess(step)
            if (risk.tier > worstTier) worstTier = risk.tier
            if (step.action == "confirm") confirmations++
            estimatedMs += step.delayMs + if (step.action == "wait") step.timeoutMs / 2 else 400

            val marker = when {
                step.action == "confirm" -> "⏸"
                risk.tier == RiskTier.CRITICAL -> "‼"
                risk.tier == RiskTier.ELEVATED -> "⚠"
                else -> "•"
            }
            append("$marker ${index + 1}. ${step.action}")
            describeSelector(step.selector)?.let { append(" $it") }
            step.value?.let { append(" = “${maskValue(it)}”") }
            if (risk.tier >= RiskTier.ELEVATED) {
                append("  [${risk.tier.name.lowercase()}: ${risk.reasons.joinToString(", ")}]")
            }
            if (snapshotUsable && snapshot != null) grade(step.selector, snapshot)?.let { append("  $it") }
            appendLine()
        }

        appendLine()
        appendLine("Confirmation gates: $confirmations · Worst risk tier: ${worstTier.name}")
        val hardened = workflow.steps.any { RiskEngine.assess(it).tier == RiskTier.CRITICAL }
        if (hardened) appendLine("At least one CRITICAL step will demand a typed APPROVE confirmation.")
        appendLine("Estimated minimum duration: ~${(estimatedMs / 1000).coerceAtLeast(1)}s (excluding confirmations)")
    }.trimEnd()

    private fun describeSelector(selector: Selector): String? = when {
        selector.viewId != null -> "id:${selector.viewId.substringAfterLast('/')}"
        selector.text != null -> "“${selector.text}”"
        selector.description != null -> "desc:“${selector.description}”"
        else -> null
    }

    /** Secrets/variables must never appear in a report; placeholders are shown verbatim. */
    private fun maskValue(value: String): String =
        if (value.contains("{{")) value else "•".repeat(value.length.coerceAtMost(8))

    private fun grade(selector: Selector, snapshot: ScreenSnapshot): String? {
        val target = selector.text ?: selector.description
            ?: selector.viewId?.let { id -> return if (snapshot.elements.any { it.selector == "viewId: $id" }) "✔ grounded" else "✖ not on screen" }
            ?: return null
        if (target.contains("{{")) return "◌ resolved at runtime"
        val best = snapshot.elements.maxOfOrNull { FuzzyText.similarity(target, it.label) } ?: 0.0
        return when {
            best >= 0.95 -> "✔ grounded"
            best >= 0.72 -> "≈ close match (${(best * 100).toInt()}%)"
            else -> "✖ not on screen"
        }
    }
}
