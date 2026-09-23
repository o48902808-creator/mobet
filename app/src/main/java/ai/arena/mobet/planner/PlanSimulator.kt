package ai.arena.mobet.planner

import ai.arena.mobet.agent.FuzzyText
import ai.arena.mobet.automation.ControlFlow
import ai.arena.mobet.automation.Expectation
import ai.arena.mobet.automation.FillValueMask
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector
import ai.arena.mobet.automation.Step
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier

/**
 * Counterfactual dry run: statically walks a plan against the latest accessibility snapshot
 * without touching the device. For each step it reports grounding against live elements, the
 * risk tier the runner will enforce, and where user confirmations will block. The output is a
 * human-readable preflight report so consequences are visible before a single action fires.
 *
 * Control flow (docs/FRONTIER.md pillar 3) is rendered as *paths*, not rows: a `branch`
 * lists both targets with resolved step numbers, a `repeatUntil` names its loop span and cap,
 * and a `tryAlternates` enumerates every option with its own grounding grade plus the
 * dead-end-memory note, so an author sees exactly what the runner will route around. Because
 * loops multiply how often steps execute, the report closes with a conservative worst-case
 * path estimate — acting steps against `policy.maxActions`, control visits against the
 * 200-hop rail — and warns when a looped run can outrun either rail. The grounded-selector
 * tally doubles as the static plan-quality measurement that pillar 4's model assistance
 * (0.9) will consume as beam-search input.
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

        // Static jump table, mirroring ControlFlow's but never throwing: a report must still
        // render when validation would reject the plan (the violations above say why).
        // Duplicates resolve last-wins here and are flagged by PlanValidator.
        val labelToIndex: Map<String, Int> = buildMap {
            workflow.steps.forEachIndexed { index, step ->
                step.label?.let { put(it, index) }
            }
        }

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
                step.action == "branch" -> "⇄"
                step.action == "repeatuntil" -> "↻"
                step.action == "tryalternates" -> "◇"
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
            if (snapshotUsable && snapshot != null && step.action !in ControlFlow.CONTROL_ACTIONS) {
                grade(step.selector, snapshot)?.let { append("  $it") }
            }
            appendLine()
            renderControlDetail(step, index, labelToIndex, snapshotUsable, snapshot)
        }

        appendLine()
        appendLine("Confirmation gates: $confirmations · Worst risk tier: ${worstTier.name}")
        val hardened = workflow.steps.any { RiskEngine.assess(it).tier == RiskTier.CRITICAL }
        if (hardened) appendLine("At least one CRITICAL step will demand a typed APPROVE confirmation.")
        appendLine("Estimated minimum duration: ~${(estimatedMs / 1000).coerceAtLeast(1)}s (excluding confirmations)")

        if (snapshotUsable && snapshot != null) {
            var gradeable = 0
            var grounded = 0
            fun tally(selector: Selector) {
                val mark = grade(selector, snapshot) ?: return
                gradeable++
                if (mark == "✔ grounded") grounded++
            }
            workflow.steps.forEach { step ->
                tally(step.selector)
                step.options.forEach { option -> tally(option) }
            }
            if (gradeable > 0) appendLine("Grounded selectors: $grounded/$gradeable")
        }

        val branches = workflow.steps.count { it.action == "branch" }
        val repeats = workflow.steps.count { it.action == "repeatuntil" }
        val alternates = workflow.steps.count { it.action == "tryalternates" }
        if (branches + repeats + alternates > 0) {
            appendLine("Control flow: $branches branch · $repeats repeat · $alternates alternates")

            // Conservative static worst case: each backward loop multiplies visits of its
            // span. Multipliers compose across nesting (outer loops are applied first by
            // ascending index). The runner's iteration counters are per step and global to
            // the run — never reset on re-entry — so true visits can only be lower; any plan
            // that fits the rails under this bound fits them at runtime.
            val visits = LongArray(workflow.steps.size) { 1L }
            workflow.steps.forEachIndexed { index, step ->
                if (step.action != "repeatuntil") return@forEachIndexed
                val target = step.goto?.let(labelToIndex::get) ?: return@forEachIndexed
                if (target > index) return@forEachIndexed
                for (i in target..index) {
                    visits[i] = (visits[i] * step.maxIterations).coerceAtMost(WORST_CASE_CAP)
                }
            }
            var worstActing = 0L
            var worstHops = 0L
            workflow.steps.forEachIndexed { index, step ->
                if (step.action in ControlFlow.CONTROL_ACTIONS) {
                    worstHops = (worstHops + visits[index]).coerceAtMost(WORST_CASE_CAP)
                } else {
                    worstActing = (worstActing + visits[index]).coerceAtMost(WORST_CASE_CAP)
                }
            }
            appendLine(
                "Worst-case path: ~$worstActing acting steps (budget ${workflow.policy.maxActions}) · " +
                    "~$worstHops control hops (rail ${ControlFlow.MAX_CONTROL_HOPS})"
            )
            if (worstActing > workflow.policy.maxActions) {
                appendLine(
                    "⚠ worst-case path outruns the action budget — a looped run halts at the cap; " +
                        "lower maxIterations or raise policy.maxActions"
                )
            }
            if (worstHops > ControlFlow.MAX_CONTROL_HOPS) {
                appendLine(
                    "⚠ worst-case path outruns the ${ControlFlow.MAX_CONTROL_HOPS}-hop control rail — " +
                        "tighten loop caps"
                )
            }
        }
    }.trimEnd()

    /** Indented path rendering for control steps, appended after the step's own row. */
    private fun StringBuilder.renderControlDetail(
        step: Step,
        index: Int,
        labels: Map<String, Int>,
        snapshotUsable: Boolean,
        snapshot: ScreenSnapshot?
    ) {
        when (step.action) {
            "branch" -> {
                appendLine("   on: ${describeCondition(step.expect)}")
                appendLine("   ├ satisfied → ${describeTarget(step.goto, labels)}")
                appendLine("   └ fallback  → ${describeTarget(step.elseGoto, labels)}")
            }
            "repeatuntil" -> {
                appendLine("   until: ${describeCondition(step.expect)} · cap ${step.maxIterations}×")
                val target = step.goto?.let(labels::get)
                if (target != null && target <= index) {
                    appendLine(
                        "   loops → ${describeTarget(step.goto, labels)}; " +
                            "steps ${target + 1}–${index + 1} may run up to ${step.maxIterations}×"
                    )
                } else {
                    appendLine("   loops → ${describeTarget(step.goto, labels)}")
                }
            }
            "tryalternates" -> {
                step.options.forEachIndexed { optionIndex, option ->
                    append("   ${optionIndex + 1}) ${describeSelector(option) ?: "(empty selector)"}")
                    if (snapshotUsable && snapshot != null) {
                        grade(option, snapshot)?.let { append("  $it") }
                    }
                    appendLine()
                }
                appendLine("   dead-end memory may skip recorded-dead options at runtime")
            }
        }
    }

    /** Human reading of a control condition; a null/empty block is always satisfied (runner semantics). */
    private fun describeCondition(expect: Expectation?): String {
        if (expect == null || expect.isEmpty) return "no evidence declared (always satisfied)"
        val parts = mutableListOf<String>()
        if (expect.screenChange) parts += "screen changes"
        expect.textPresent?.let { parts += "“$it” present" }
        expect.textAbsent?.let { parts += "“$it” absent" }
        expect.packageIs?.let { parts += "package $it" }
        return parts.joinToString(" · ")
    }

    private fun describeTarget(label: String?, labels: Map<String, Int>): String = when {
        label == null -> "next step"
        labels.containsKey(label) -> "“$label” (step ${labels.getValue(label) + 1})"
        else -> "“$label” (unresolved)"
    }

    private fun describeSelector(selector: Selector): String? = when {
        selector.viewId != null -> "id:${selector.viewId.substringAfterLast('/')}"
        selector.text != null -> "“${selector.text}”"
        selector.description != null -> "desc:“${selector.description}”"
        else -> null
    }

    /** Secrets/variables must never appear in a report; placeholders are shown verbatim. */
    private fun maskValue(value: String): String = FillValueMask.mask(value)

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

    /** Saturating bound so deeply nested loop caps cannot overflow the estimate. */
    private const val WORST_CASE_CAP = 1_000_000L
}
