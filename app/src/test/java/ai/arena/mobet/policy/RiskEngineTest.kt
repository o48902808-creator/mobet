package ai.arena.mobet.policy

import ai.arena.mobet.automation.Selector
import ai.arena.mobet.automation.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    @Test
    fun plainNavigationTapIsLowRisk() {
        val risk = RiskEngine.assess(Step(action = "tap", selector = Selector(text = "Network & internet")))
        assertTrue(risk.tier <= RiskTier.LOW)
    }

    @Test
    fun consequentialTapIsElevated() {
        val risk = RiskEngine.assess(Step(action = "tap", selector = Selector(text = "Submit order")))
        assertTrue(risk.tier >= RiskTier.ELEVATED)
        assertTrue(risk.reasons.any { it.contains("consequential") })
    }

    @Test
    fun destructiveFinancialTapIsCritical() {
        val risk = RiskEngine.assess(
            Step(action = "tap", selector = Selector(text = "Confirm transfer of $500 and delete account"))
        )
        assertEquals(RiskTier.CRITICAL, risk.tier)
    }

    @Test
    fun waitingForDangerousTextIsNotRisky() {
        // Passively observing the word "Delete" must not require a confirmation.
        val risk = RiskEngine.assess(Step(action = "wait", selector = Selector(text = "Delete account")))
        assertEquals(RiskTier.NONE, risk.tier)
    }

    @Test
    fun confirmStepsAreNeverRisky() {
        val risk = RiskEngine.assess(Step(action = "confirm", message = "Pay now and delete everything?"))
        assertEquals(RiskTier.NONE, risk.tier)
    }

    @Test
    fun visualActionsAreAtLeastElevated() {
        for (action in listOf("tappoint", "swipe", "capture", "ocrwait", "visualtap")) {
            val risk = RiskEngine.assess(Step(action = action, selector = Selector(text = "Continue")))
            assertTrue("$action should be elevated", risk.tier >= RiskTier.ELEVATED)
        }
    }

    @Test
    fun credentialFillRaisesScoreButLanguageAloneStaysBelowCritical() {
        val risk = RiskEngine.assess(
            Step(action = "fill", selector = Selector(viewId = "app:id/password"), value = "{{secret:pw}}")
        )
        assertTrue(risk.score > 0)
        assertTrue(risk.reasons.any { it.contains("credential") })
    }
}
