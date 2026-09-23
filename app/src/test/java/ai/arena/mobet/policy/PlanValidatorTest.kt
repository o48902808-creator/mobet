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

    // ── launch: the only action that can move automation into another app ────

    @Test
    fun launchWithinAllowlistPasses() {
        val flow = workflow(
            """
            {
              "name": "cross-app",
              "package": "com.example.notes",
              "policy": {
                "allowedPackages": ["com.example.notes", "com.example.mail"],
                "allowedActions": ["wait", "tap", "launch"]
              },
              "steps": [
                { "action": "wait", "text": "Notes" },
                { "action": "launch", "package": "com.example.mail" },
                { "action": "wait", "text": "Inbox" }
              ]
            }
            """
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(flow))
    }

    @Test
    fun launchOutsideAllowlistIsRejected() {
        val flow = workflow(
            """
            {
              "name": "escape",
              "package": "com.example.notes",
              "policy": {
                "allowedPackages": ["com.example.notes"],
                "allowedActions": ["wait", "launch"]
              },
              "steps": [
                { "action": "wait", "text": "Notes" },
                { "action": "launch", "package": "com.attacker.bank" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 2 && it.message.contains("allowedPackages") })
    }

    @Test
    fun launchWithoutPackageIsRejected() {
        val flow = workflow(
            """
            {
              "name": "no target",
              "package": "com.example.notes",
              "policy": {
                "allowedPackages": ["com.example.notes"],
                "allowedActions": ["wait", "launch"]
              },
              "steps": [
                { "action": "wait", "text": "Notes" },
                { "action": "launch" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 2 && it.message.contains("requires a package") })
    }

    @Test
    fun launchNotInAllowedActionsIsRejected() {
        val flow = workflow(
            """
            {
              "name": "action not permitted",
              "package": "com.example.notes",
              "policy": {
                "allowedPackages": ["com.example.notes", "com.example.mail"],
                "allowedActions": ["wait", "tap"]
              },
              "steps": [
                { "action": "wait", "text": "Notes" },
                { "action": "launch", "package": "com.example.mail" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 2 && it.message.contains("not allowed") })
    }

    @Test
    fun expectBlockWithoutAssertionsIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "delay", "expect": {} } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 1 && it.message.contains("no assertions") })
    }

    @Test
    fun expectPackageOutsideAllowlistIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "tap", "text": "x", "expect": { "package": "com.example.other" } }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(
            violations.any {
                it.step == 1 && it.message.contains("com.example.other") &&
                    it.message.contains("allowedPackages")
            }
        )
    }

    @Test
    fun duplicateStepLabelsAreRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "delay", "label": "x" },
                { "action": "delay", "label": "x" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("Duplicate step label") })
    }

    @Test
    fun branchRequiresConditionAndTarget() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "branch", "goto": "nope" } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("branch requires an expect condition") })
        assertTrue(violations.any { it.message.contains("does not exist") })
    }

    @Test
    fun repeatUntilMustPointBackwards() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "repeatUntil", "goto": "later", "expect": { "textPresent": "x" } },
                { "action": "delay", "label": "later" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 1 && it.message.contains("backwards") })
    }

    @Test
    fun gotoOnOrdinaryActionIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "delay", "label": "t" },
                { "action": "wait", "text": "x", "goto": "t" }
              ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.step == 2 && it.message.contains("only meaningful") })
    }

    @Test
    fun tryAlternatesOptionWithoutSelectorFieldsIsRejected() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "tryAlternates", "options": [ {} ] } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("no selector fields") })
    }

    @Test
    fun elevatedAlternatesOptionNeedsPrecedingConfirm() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "tryAlternates", "options": [ { "text": "Submit payment" } ] } ]
            }
            """
        )
        val violations = PlanValidator.validate(flow)
        assertTrue(violations.any { it.message.contains("tryAlternates option 1") && it.message.contains("confirm") })
    }

    @Test
    fun coherentControlFlowPlanPasses() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "delay", "label": "top" },
                { "action": "wait", "text": "Load" },
                { "action": "repeatUntil", "goto": "top", "maxIterations": 5,
                  "expect": { "textPresent": "Done" } },
                { "action": "branch", "goto": "finish", "elseGoto": "cleanup",
                  "expect": { "textPresent": "Done" } },
                { "action": "delay", "label": "cleanup" },
                { "action": "tryAlternates", "options": [ { "text": "Close" }, { "viewId": "id/ok" } ] },
                { "action": "delay", "label": "finish" }
              ]
            }
            """
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(flow))
    }

    @Test
    fun coherentExpectBlockAddsNoViolations() {
        val flow = workflow(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [
                { "action": "delay" },
                {
                  "action": "tap", "text": "Network & internet",
                  "expect": {
                    "screenChange": true,
                    "textPresent": "Connected",
                    "package": "com.example.app"
                  }
                }
              ]
            }
            """
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(flow))
    }
}
