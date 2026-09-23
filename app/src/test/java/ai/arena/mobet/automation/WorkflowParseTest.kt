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

    @Test
    fun stepCountIsBoundedAtParseTime() {
        val steps = (1..(Workflow.MAX_STEPS + 1)).joinToString(", ") { "{ \"action\": \"delay\" }" }
        val source = """{ "name": "demo", "package": "com.example.app", "steps": [$steps] }"""
        val error = runCatching { Workflow.parse(source) }.exceptionOrNull()
        assertEquals(
            "Workflow has ${Workflow.MAX_STEPS + 1} steps; the limit is ${Workflow.MAX_STEPS}",
            error?.message
        )
    }

    @Test
    fun exactlyTheStepLimitParses() {
        val steps = (1..Workflow.MAX_STEPS).joinToString(", ") { "{ \"action\": \"delay\" }" }
        val flow = Workflow.parse(
            """{ "name": "demo", "package": "com.example.app", "policy": { "maxActions": ${Workflow.MAX_STEPS} }, "steps": [$steps] }"""
        )
        assertEquals(Workflow.MAX_STEPS, flow.steps.size)
    }

    @Test
    fun variableCountIsBoundedAtParseTime() {
        val variables = (1..(Workflow.MAX_VARIABLES + 1)).joinToString(", ") { "\"v$it\": \"x\"" }
        val source = """{ "name": "demo", "package": "com.example.app", "variables": { $variables }, "steps": [ { "action": "delay" } ] }"""
        val error = runCatching { Workflow.parse(source) }.exceptionOrNull()
        assertEquals(
            "Workflow has ${Workflow.MAX_VARIABLES + 1} variables; the limit is ${Workflow.MAX_VARIABLES}",
            error?.message
        )
    }

    @Test
    fun expectBlockParsesAllAssertionKinds() {
        val flow = Workflow.parse(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                {
                  "action": "tap", "text": "Wi-Fi",
                  "expect": {
                    "screenChange": true,
                    "textPresent": "Network & internet",
                    "textAbsent": "Settings",
                    "package": "com.example.app"
                  }
                }
              ]
            }
            """
        )
        val expect = flow.steps.first().expect!!
        assertTrue(expect.screenChange)
        assertEquals("Network & internet", expect.textPresent)
        assertEquals("Settings", expect.textAbsent)
        assertEquals("com.example.app", expect.packageIs)
        assertFalse(expect.isEmpty)
    }

    @Test
    fun stepsWithoutExpectParseAsNull() {
        val flow = Workflow.parse(
            """{ "name": "demo", "package": "com.example.app", "steps": [ { "action": "delay" } ] }"""
        )
        assertEquals(null, flow.steps.first().expect)
    }

    @Test
    fun blankExpectStringsNormaliseToNullSoEmptyBlocksAreDetectable() {
        val flow = Workflow.parse(
            """
            { "name": "demo", "package": "com.example.app",
              "steps": [ { "action": "delay", "expect": { "textPresent": "  ", "package": "" } } ] }
            """
        )
        val expect = flow.steps.first().expect!!
        assertTrue(expect.isEmpty)
    }

    @Test
    fun controlFlowFieldsRoundTrip() {
        val flow = Workflow.parse(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "delay", "label": "top" },
                {
                  "action": "repeatUntil", "goto": "top", "maxIterations": 99,
                  "expect": { "textPresent": "Done" }
                },
                {
                  "action": "tryAlternates",
                  "options": [ { "text": "Accept" }, { "viewId": "id/ok" }, { "description": "Close" } ]
                },
                { "action": "branch", "goto": "top", "elseGoto": "top", "expect": { "screenChange": true } }
              ]
            }
            """
        )
        assertEquals("top", flow.steps[0].label)
        assertEquals("top", flow.steps[1].goto)
        assertEquals(50, flow.steps[1].maxIterations)  // clamped to the 1..50 window
        assertEquals(3, flow.steps[2].options.size)
        assertEquals("id/ok", flow.steps[2].options[1].viewId)
        assertEquals("top", flow.steps[3].goto)
        assertEquals("top", flow.steps[3].elseGoto)
        assertEquals(
            "branch",
            flow.steps[3].action
        )
    }

    @Test
    fun optionsAreBoundedAtParseTime() {
        val options = (1..(Workflow.MAX_OPTIONS + 1)).joinToString(", ") { "{ \"text\": \"x\" }" }
        val source =
            """{ "name": "demo", "package": "com.example.app", "steps": [ { "action": "tryAlternates", "options": [$options] } ] }"""
        val error = runCatching { Workflow.parse(source) }.exceptionOrNull()
        assertEquals(
            "tryAlternates has ${Workflow.MAX_OPTIONS + 1} options; the limit is ${Workflow.MAX_OPTIONS}",
            error?.message
        )
    }

    @Test
    fun blankControlFlowStringsNormaliseToNull() {
        val flow = Workflow.parse(
            """{ "name": "demo", "package": "com.example.app",
              "steps": [ { "action": "branch", "label": " ", "goto": "", "expect": { "screenChange": true } } ] }"""
        )
        val step = flow.steps.first()
        assertEquals(null, step.label)
        assertEquals(null, step.goto)
    }

    companion object {
        private val AutomationPolicyDefaults =
            ai.arena.mobet.policy.AutomationPolicy.DEFAULT_ACTIONS
    }
}
