package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.Step
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier

enum class QualitySeverity { INFO, ADVICE, WARNING }

data class QualityFinding(
    val severity: QualitySeverity,
    val message: String,
    /** 1-based step number when the finding is local, null when it is about the whole plan. */
    val step: Int? = null
)

/**
 * Advisory static quality measurement of a generated (or authored) plan.
 *
 * This is deliberately *not* a second safety gate: `PlanValidator` decides what may exist, and it
 * has already run. Quality answers the different question a user actually has — "will this plan
 * still work tomorrow, and will I be able to tell if it silently did the wrong thing?" — and it
 * can only ever advise, never block or rewrite.
 *
 * Score starts at 100 and loses points for fragility (text selectors that drift with wording or
 * locale), blindness (acting steps with no verification anywhere), and brittleness (loops close
 * to their cap, budgets with no slack).
 */
data class PlanQuality(
    val score: Int,
    val findings: List<QualityFinding>
) {
    val grade: String
        get() = when {
            score >= 90 -> "A"
            score >= 75 -> "B"
            score >= 60 -> "C"
            score >= 40 -> "D"
            else -> "E"
        }

    fun summary(): String = buildString {
        appendLine("Quality     $grade ($score/100)")
        findings.forEach { finding ->
            val where = finding.step?.let { "step $it" } ?: "plan"
            appendLine("  ${symbol(finding.severity)} $where: ${finding.message}")
        }
    }

    private fun symbol(severity: QualitySeverity): String = when (severity) {
        QualitySeverity.WARNING -> "!"
        QualitySeverity.ADVICE -> "~"
        QualitySeverity.INFO -> "·"
    }

    companion object {
        private val actingActions = setOf("tap", "fill", "tryalternates", "swipe", "tappoint", "visualtap")

        fun analyze(workflow: Workflow): PlanQuality {
            val findings = mutableListOf<QualityFinding>()
            var score = 100
            val steps = workflow.steps

            val acting = steps.withIndex().filter { it.value.action in actingActions }
            val verified = steps.count { it.expect != null }
            if (acting.isNotEmpty() && verified == 0) {
                score -= 25
                findings += QualityFinding(
                    QualitySeverity.WARNING,
                    "no step verifies its result — the run cannot tell success from a silent misfire. " +
                        "Add a verify clause (e.g. verify “Saved” appears)."
                )
            } else if (acting.isNotEmpty() && verified < (acting.size + 1) / 2) {
                score -= 10
                findings += QualityFinding(
                    QualitySeverity.ADVICE,
                    "$verified of ${acting.size} acting steps are verified; consider asserting the outcome of the rest"
                )
            }

            val fragile = acting.filter { (_, step) -> isTextOnly(step) }
            if (fragile.isNotEmpty()) {
                val penalty = (fragile.size * 4).coerceAtMost(20)
                score -= penalty
                fragile.take(3).forEach { (index, step) ->
                    findings += QualityFinding(
                        QualitySeverity.ADVICE,
                        "targets “${step.selector.text}” by visible text, which changes with app wording " +
                            "and device language; a viewId selector survives both",
                        index + 1
                    )
                }
                if (fragile.size > 3) {
                    findings += QualityFinding(
                        QualitySeverity.INFO,
                        "${fragile.size - 3} further text-only selector(s) not listed"
                    )
                }
            }

            steps.forEachIndexed { index, step ->
                if (step.action == "repeatuntil" && step.maxIterations >= 40) {
                    score -= 5
                    findings += QualityFinding(
                        QualitySeverity.ADVICE,
                        "loop cap of ${step.maxIterations} is close to the 50 maximum; a tighter cap fails faster",
                        index + 1
                    )
                }
                if (step.action == "tryalternates" && step.options.size >= SnapshotGrounder.MAX_ALTERNATES) {
                    findings += QualityFinding(
                        QualitySeverity.INFO,
                        "${step.options.size} alternates — the screen offered several equally plausible controls",
                        index + 1
                    )
                }
                if (step.action == "fill" && step.value != null &&
                    !step.value!!.contains("{{") && looksSensitive(step)
                ) {
                    score -= 8
                    findings += QualityFinding(
                        QualitySeverity.WARNING,
                        "a credential-looking value is written in plain text; use {{secret:name}} instead",
                        index + 1
                    )
                }
            }

            val elevated = steps.count { RiskEngine.assess(it).tier >= RiskTier.ELEVATED }
            if (elevated > 0) {
                findings += QualityFinding(
                    QualitySeverity.INFO,
                    "$elevated step(s) are gated by a blocking confirmation before they can act"
                )
            }

            if (steps.size >= workflow.policy.maxActions) {
                score -= 5
                findings += QualityFinding(
                    QualitySeverity.ADVICE,
                    "the plan exactly fills its action budget; a recovery retry would exhaust it"
                )
            }

            if (workflow.policy.allowedPackages.size > 1) {
                findings += QualityFinding(
                    QualitySeverity.INFO,
                    "cross-app plan: ${workflow.policy.allowedPackages.sorted().joinToString()}"
                )
            }

            return PlanQuality(score.coerceIn(0, 100), findings)
        }

        private fun isTextOnly(step: Step): Boolean =
            step.selector.text != null && step.selector.viewId == null &&
                step.selector.description == null

        private val sensitiveField = Regex("(?i)password|passcode|pin|otp|token|secret|cvv|card")

        private fun looksSensitive(step: Step): Boolean {
            val corpus = listOfNotNull(
                step.selector.text, step.selector.description, step.selector.viewId
            ).joinToString(" ")
            return sensitiveField.containsMatchIn(corpus)
        }
    }
}
