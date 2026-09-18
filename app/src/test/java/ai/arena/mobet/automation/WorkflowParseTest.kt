package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowParseTest {
    @Test
    fun timingValuesAreClamped() {
        val flow = Workflow.parse(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "wait", "text": "x", "timeoutMs": 999999, "delayMs": 999999, "retries": 99 }
              ]
            }
            """
        )
        val step = flow.steps.first()
        assertEquals(60_000L, step.timeoutMs)
        assertEquals(10_000L, step.delayMs)
        assertEquals(10, step.retries)
    }

    @Test
    fun percentsAreClampedToSafeBounds() {
        val flow = Workflow.parse(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "tappoint", "xPercent": 1.0, "yPercent": 0.0 } ]
            }
            """
        )
        assertEquals(0.98, flow.steps.first().xPercent!!, 1e-9)
        assertEquals(0.02, flow.steps.first().yPercent!!, 1e-9)
    }

    @Test
    fun policyDefaultsAreConservative() {
        val flow = Workflow.parse(
            """
            { "name": "demo", "package": "com.example.app", "steps": [ { "action": "wait", "text": "x" } ] }
            """
        )
        assertFalse(flow.policy.allowVisualFallbacks)
        assertFalse(flow.policy.allowSelfHealing)
        assertTrue(flow.policy.allowedPackages.contains("com.example.app"))
        assertEquals(AutomationPolicyDefaults, flow.policy.allowedActions)
    }

    @Test(expected = Exception::class)
    fun emptyStepListIsRejected() {
        Workflow.parse("""{ "name": "demo", "package": "com.example.app", "steps": [] }""")
    }

    companion object {
        private val AutomationPolicyDefaults =
            ai.arena.mobet.policy.AutomationPolicy.DEFAULT_ACTIONS
    }
}
