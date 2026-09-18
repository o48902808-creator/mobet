package ai.arena.mobet.policy

import ai.arena.mobet.automation.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    private fun workflow(json: String) = Workflow.parse(json)

    @Test
    fun benignPlanPasses() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.android.settings",
              "steps": [
                { "action": "wait", "text": "Settings" },
                { "action": "tap", "text": "Network & internet" },
                { "action": "back" }
              ]
            }
            """
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(flow))
    }

    @Test
    fun consequentialTapWithoutConfirmIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "tap", "text": "Submit payment" } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("confirm") })
    }

    @Test
    fun consequentialTapWithAdjacentConfirmPasses() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "policy": { "allowedActions": ["wait","tap","confirm"] },
              "steps": [
                { "action": "confirm", "message": "Submit the form?" },
                { "action": "tap", "text": "Submit" }
              ]
            }
            """
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(flow))
    }

    @Test
    fun visualFallbackRequiresPolicyOptIn() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "policy": { "allowedActions": ["confirm","visualtap"] },
              "steps": [
                { "action": "confirm", "message": "Use OCR?" },
                { "action": "visualtap", "text": "Continue" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("Visual fallback") })
    }

    @Test
    fun packageOutsideAllowlistIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "policy": { "allowedPackages": ["com.other.app"] },
              "steps": [ { "action": "wait", "text": "Home" } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("allowedPackages") })
    }

    @Test
    fun actionBudgetIsEnforced() {
        val steps = (1..5).joinToString(",") { """{ "action": "wait", "text": "x$it" }""" }
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "policy": { "maxActions": 3 },
              "steps": [ $steps ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("limit is 3") })
    }

    @Test
    fun selfHealingFlagParses() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "policy": { "allowSelfHealing": true },
              "steps": [ { "action": "wait", "text": "Home" } ]
            }
            """
        )
        assertTrue(flow.policy.allowSelfHealing)
    }
}
