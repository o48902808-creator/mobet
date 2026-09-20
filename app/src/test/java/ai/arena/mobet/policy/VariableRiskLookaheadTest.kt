package ai.arena.mobet.policy

import ai.arena.mobet.automation.Selector
import ai.arena.mobet.automation.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmation look-ahead must score a step as it will actually execute.
 *
 * A `confirm` step asks the runner how risky the *next* step is, and upgrades to a hardened
 * typed confirmation when that step is CRITICAL. The look-ahead scored the raw step while
 * execution scored the variable-expanded one, so a selector of `{{var:label}}` read as a bare
 * tap (LOW) at gate time even when `label` was "Pay $500 now" (CRITICAL). The protection meant
 * for the riskiest actions was weakest precisely when the risky text arrived through a variable.
 *
 * `riskOf` is private to the runner, which needs an accessibility service, so the rule is
 * reproduced here against the real [RiskEngine].
 */
class VariableRiskLookaheadTest {

    private fun substitute(source: String?, variables: Map<String, String>): String? {
        source ?: return null
        var result: String = source
        Regex("\\{\\{var:([A-Za-z0-9_.-]+)}}").findAll(source).forEach { match ->
            variables[match.groupValues[1]]?.let { result = result.replace(match.value, it) }
        }
        return result
    }

    /** Mirrors WorkflowRunner.riskOf(). */
    private fun riskOf(step: Step, variables: Map<String, String>): RiskAssessment {
        val previewed = step.copy(
            selector = Selector(
                substitute(step.selector.text, variables),
                substitute(step.selector.viewId, variables),
                substitute(step.selector.description, variables)
            ),
            value = substitute(step.value, variables),
            message = substitute(step.message, variables)
        )
        val raw = RiskEngine.assess(step)
        val resolved = RiskEngine.assess(previewed)
        return if (resolved.score >= raw.score) resolved else raw
    }

    private fun tap(text: String) = Step(action = "tap", selector = Selector(text = text))

    @Test
    fun aFinancialVariableEscalatesTheGateToCritical() {
        val step = tap("{{var:label}}")
        assertEquals(
            "unresolved, this looks harmless",
            RiskTier.LOW,
            RiskEngine.assess(step).tier
        )
        assertEquals(
            "resolved, it must harden the confirmation",
            RiskTier.CRITICAL,
            riskOf(step, mapOf("label" to "Pay \$500 now")).tier
        )
    }

    @Test
    fun aDestructiveVariableIsAlsoCaught() {
        val tier = riskOf(tap("{{var:label}}"), mapOf("label" to "Delete account")).tier
        assertTrue("expected at least ELEVATED, got $tier", tier >= RiskTier.ELEVATED)
    }

    @Test
    fun aRiskyFillValueIsCaught() {
        val step = Step(action = "fill", selector = Selector(viewId = "field"), value = "{{var:v}}")
        assertTrue(riskOf(step, mapOf("v" to "card number")).tier >= RiskTier.ELEVATED)
    }

    @Test
    fun literalRiskyTextIsUnaffected() {
        // Positive control: the existing behaviour for plain text must not change.
        assertEquals(RiskTier.ELEVATED, riskOf(tap("Submit"), emptyMap()).tier)
    }

    @Test
    fun aHarmlessStepStaysLow() {
        // The suite must not pass by escalating everything; that would train users to click
        // through hardened confirmations.
        assertEquals(RiskTier.LOW, riskOf(tap("Next"), emptyMap()).tier)
    }

    @Test
    fun anUnknownVariableLeavesTheScoreNoLowerThanTheRawStep() {
        // Substitution failure must never *reduce* the assessment below the raw reading.
        val step = tap("{{var:missing}}")
        assertTrue(riskOf(step, emptyMap()).score >= RiskEngine.assess(step).score)
    }

    @Test
    fun secretPlaceholdersAreNotResolvedForScoring() {
        // Reading a secret to score a step the user has not yet approved is not worth the
        // exposure, and a secret's value is not the signal risk scoring looks for.
        val step = tap("{{secret:pin}}")
        assertEquals(RiskEngine.assess(step).tier, riskOf(step, emptyMap()).tier)
    }

    @Test
    fun aHarmlessVariableDoesNotEscalate() {
        assertEquals(RiskTier.LOW, riskOf(tap("{{var:label}}"), mapOf("label" to "Next")).tier)
    }
}
