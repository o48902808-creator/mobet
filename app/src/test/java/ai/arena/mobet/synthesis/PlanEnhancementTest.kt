package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optimizer, quality analysis, parameterization and repair. */
class PlanEnhancementTest {

    private fun element(label: String, selector: String, role: String = "Button") =
        InspectedElement(label, role, selector, 1, 92, "0,0–10,10")

    private val screen = ScreenSnapshot(
        "com.example.app", 0,
        listOf(
            element("Continue", "text: Continue"),
            element("Settings", "text: Settings"),
            element("Password", "viewId: com.example.app:id/password", role = "EditText"),
            element("Email", "viewId: com.example.app:id/email", role = "EditText"),
            element("Done", "text: Done")
        )
    )

    // ── Optimizer ────────────────────────────────────────────────────────────

    @Test
    fun duplicateWaitsAreCollapsed() {
        val goal = "wait for \"Continue\" then wait for \"Continue\" then tap \"Continue\""
        val optimized = WorkflowSynthesizer.synthesize(goal, screen).getOrThrow()
        val unoptimized = WorkflowSynthesizer.synthesize(
            goal, screen, SynthesisOptions(optimize = false)
        ).getOrThrow()
        assertTrue(optimized.stepCount < unoptimized.stepCount)
        assertEquals(1, optimized.workflow.steps.count { it.action == "wait" })
        assertTrue(optimized.notes.any { it.stage == "optimizer" })
    }

    @Test
    fun optimizationNeverBreaksControlFlowTargets() {
        val result = WorkflowSynthesizer.synthesize(
            "if \"Continue\" appears then tap \"Continue\"\ntap \"Done\"", screen
        ).getOrThrow()
        val steps = result.workflow.steps
        val labels = steps.mapNotNull { it.label }.toSet()
        steps.forEach { step ->
            step.goto?.let { assertTrue("dangling goto $it", it in labels) }
            step.elseGoto?.let { assertTrue("dangling elseGoto $it", it in labels) }
        }
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun optimizerKeepsVerifiedSteps() {
        val result = WorkflowSynthesizer.synthesize(
            "wait for \"Continue\"; verify \"Continue\" appears; wait for \"Continue\" then tap \"Continue\"",
            screen
        ).getOrThrow()
        assertTrue(result.workflow.steps.any { it.expect?.textPresent == "Continue" })
    }

    // ── Parameterization ─────────────────────────────────────────────────────

    @Test
    fun credentialValuesAreNotHoistedIntoPlaintextVariables() {
        val result = WorkflowSynthesizer.synthesize(
            "fill \"Password\" with \"hunter2\"", screen
        ).getOrThrow()
        val fill = result.workflow.steps.first { it.action == "fill" }
        assertEquals("hunter2", fill.value)
        assertTrue(result.workflow.variables.isEmpty())
    }

    @Test
    fun existingReferencesAreLeftAlone() {
        val result = WorkflowSynthesizer.synthesize(
            "fill \"Email\" with \"{{secret:account.email}}\"", screen
        ).getOrThrow()
        val fill = result.workflow.steps.first { it.action == "fill" }
        assertEquals("{{secret:account.email}}", fill.value)
    }

    // ── Quality ──────────────────────────────────────────────────────────────

    @Test
    fun unverifiedPlanScoresBelowAVerifiedOne() {
        val blind = WorkflowSynthesizer.synthesize("tap \"Done\"", screen).getOrThrow()
        val verified = WorkflowSynthesizer.synthesize(
            "tap \"Done\"; verify \"Done\" appears", screen
        ).getOrThrow()
        assertTrue(verified.quality.score > blind.quality.score)
        assertTrue(blind.quality.findings.any { it.severity == QualitySeverity.WARNING })
    }

    @Test
    fun plaintextCredentialIsFlagged() {
        val result = WorkflowSynthesizer.synthesize(
            "fill \"Password\" with \"hunter2\"", screen
        ).getOrThrow()
        assertTrue(
            result.quality.findings.any {
                it.severity == QualitySeverity.WARNING && it.message.contains("{{secret:")
            }
        )
    }

    @Test
    fun qualityIsAdvisoryOnly() {
        val result = WorkflowSynthesizer.synthesize("tap \"Done\"", screen).getOrThrow()
        // A low score must never prevent a plan that policy approved from being produced.
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
        assertTrue(result.quality.grade.isNotEmpty())
        assertTrue(result.report().contains("Quality"))
    }

    // ── Repair ───────────────────────────────────────────────────────────────

    private val stalePlan = """
        {
          "name": "Stale plan",
          "package": "com.example.app",
          "policy": {
            "allowedPackages": ["com.example.app"],
            "allowedActions": ["wait", "tap", "confirm"],
            "maxActions": 20,
            "maxRuntimeMs": 60000
          },
          "steps": [
            {"action": "wait", "text": "Kontinue"},
            {"action": "tap", "text": "Kontinue"}
          ]
        }
    """.trimIndent()

    @Test
    fun driftedSelectorsAreRegrounded() {
        val result = WorkflowRepair.repair(stalePlan, screen).getOrThrow()
        assertTrue(result.workflow.steps.all { it.selector.text != "Kontinue" })
        assertTrue(result.workflow.steps.any { it.selector.text == "Continue" })
        assertTrue(result.notes.any { it.stage == "repair" })
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun repairCannotRetargetADifferentApp() {
        val otherApp = screen.copy(packageName = "com.other.app")
        val result = WorkflowRepair.repair(stalePlan, otherApp)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("com.other.app"))
    }

    @Test
    fun vanishedSelectorIsReportedNotGuessed() {
        val plan = stalePlan.replace("Kontinue", "Teleporter")
        val result = WorkflowRepair.repair(plan, screen)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("gone from this screen"))
    }

    @Test
    fun repairPreservesValuesAndDoesNotEnableSelfHealing() {
        val plan = """
            {
              "name": "Sign in",
              "package": "com.example.app",
              "variables": {"user": "someone@example.com"},
              "policy": {"allowedPackages": ["com.example.app"], "allowedActions": ["fill", "confirm"]},
              "steps": [
                {"action": "fill", "viewId": "com.example.app:id/email", "value": "{{var:user}}"}
              ]
            }
        """.trimIndent()
        val result = WorkflowRepair.repair(plan, screen).getOrThrow()
        val fill = result.workflow.steps.first { it.action == "fill" }
        assertEquals("{{var:user}}", fill.value)
        assertEquals("someone@example.com", result.workflow.variables["user"])
        assertFalse(result.workflow.policy.allowSelfHealing)
        assertFalse(result.workflow.policy.allowVisualFallbacks)
    }

    @Test
    fun intactPlanReportsNoChanges() {
        val plan = stalePlan.replace("Kontinue", "Continue")
        val result = WorkflowRepair.repair(plan, screen).getOrThrow()
        assertTrue(result.notes.any { it.detail.contains("still resolve") })
    }
}
