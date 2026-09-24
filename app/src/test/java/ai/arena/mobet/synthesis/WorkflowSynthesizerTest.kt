package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowSynthesizerTest {

    private fun element(
        label: String,
        selector: String,
        role: String = "Button",
        confidence: Int = 90
    ) = InspectedElement(label, role, selector, 1, confidence, "0,0–10,10")

    private val settings = ScreenSnapshot(
        "com.android.settings", 0,
        listOf(
            element("Network & internet", "text: Network & internet"),
            element("Connected devices", "text: Connected devices"),
            element("Apps", "text: Apps"),
            element("Delete account", "text: Delete account"),
            element("Not now", "text: Not now"),
            element("Search settings", "viewId: com.android.settings:id/search", role = "EditText")
        )
    )

    private fun synthesize(goal: String, options: SynthesisOptions = SynthesisOptions()) =
        WorkflowSynthesizer.synthesize(goal, settings, options)

    @Test
    fun groundedGoalProducesValidatedWorkflow() {
        val result = synthesize("open \"Network & internet\"").getOrThrow()
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
        assertEquals("com.android.settings", result.workflow.packageName)
        val actions = result.workflow.steps.map { it.action }
        assertEquals(listOf("wait", "tap"), actions)
        assertTrue(result.workflow.steps.last().expect!!.screenChange)
    }

    @Test
    fun synthesisIsDeterministic() {
        val a = synthesize("tap \"Apps\" then go back").getOrThrow().json
        val b = synthesize("tap \"Apps\" then go back").getOrThrow().json
        assertEquals(a, b)
    }

    @Test
    fun ungroundedTargetIsRejectedWithTheClosestMatch() {
        val result = synthesize("tap \"Teleport button\"")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not on the captured screen"))
    }

    @Test
    fun riskyTargetGetsAnImmediatelyPrecedingConfirm() {
        val result = synthesize("tap \"Delete account\"").getOrThrow()
        val actions = result.workflow.steps.map { it.action }
        assertTrue(actions.contains("confirm"))
        val tapIndex = actions.indexOf("tap")
        assertEquals("confirm", actions[tapIndex - 1])
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun fillGroundsAgainstEditableControlsOnly() {
        val result = synthesize("fill \"Search settings\" with \"wifi\"").getOrThrow()
        val fill = result.workflow.steps.first { it.action == "fill" }
        assertEquals("com.android.settings:id/search", fill.selector.viewId)
        assertEquals("wifi", fill.value)
    }

    @Test
    fun conditionalLowersToABranchWithResolvableLabels() {
        val result = synthesize(
            "if \"Not now\" appears then tap \"Not now\"\ntap \"Apps\""
        ).getOrThrow()
        val steps = result.workflow.steps
        val branch = steps.first { it.action == "branch" }
        assertNotNull(branch.expect)
        assertEquals("Not now", branch.expect!!.textPresent)
        val labels = steps.mapNotNull { it.label }.toSet()
        assertTrue(branch.goto!! in labels)
        assertTrue(branch.elseGoto!! in labels)
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun repeatLowersToABackwardsJumpWithACap() {
        val result = synthesize("repeat scroll until \"Apps\" appears max 5").getOrThrow()
        val steps = result.workflow.steps
        val loop = steps.last()
        assertEquals("repeatuntil", loop.action)
        assertEquals(5, loop.maxIterations)
        val targetIndex = steps.indexOfFirst { it.label == loop.goto }
        assertTrue(targetIndex in 0 until steps.lastIndex)
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun ambiguousTargetBecomesBoundedAlternates() {
        val duplicated = settings.copy(
            elements = settings.elements + element("Apps and notifications", "text: Apps and notifications")
        )
        val result = WorkflowSynthesizer.synthesize("tap \"Apps\"", duplicated)
        // Either a single confident match or a bounded tryAlternates — never an unvalidated guess.
        val workflow = result.getOrThrow().workflow
        assertTrue(PlanValidator.validate(workflow).isEmpty())
        val alternates = workflow.steps.firstOrNull { it.action == "tryalternates" }
        if (alternates != null) assertTrue(alternates.options.size in 2..SnapshotGrounder.MAX_ALTERNATES)
    }

    @Test
    fun ambiguityCanBeMadeAHardError() {
        val duplicated = settings.copy(
            elements = listOf(
                element("Continue", "text: Continue"),
                element("Continue ", "viewId: com.android.settings:id/continue2")
            )
        )
        val strict = WorkflowSynthesizer.synthesize(
            "tap \"Continue\"", duplicated, SynthesisOptions(allowAlternates = false)
        )
        assertTrue(strict.isFailure)
        assertTrue(strict.exceptionOrNull()!!.message!!.contains("ambiguous"))
    }

    @Test
    fun verificationBecomesAnExpectBlockOnThePreviousStep() {
        val result = synthesize("tap \"Apps\"; verify \"Apps\" appears").getOrThrow()
        val tap = result.workflow.steps.last { it.action == "tap" }
        assertEquals("Apps", tap.expect!!.textPresent)
    }

    @Test
    fun policyIsLeastPrivilege() {
        val result = synthesize("tap \"Apps\" then go back").getOrThrow()
        val policy = result.workflow.policy
        assertEquals(setOf("com.android.settings"), policy.allowedPackages)
        assertTrue(policy.allowedActions.containsAll(setOf("tap", "wait", "back", "confirm")))
        assertFalse(policy.allowedActions.contains("launch"))
        assertFalse(policy.allowVisualFallbacks)
        assertFalse(policy.allowSelfHealing)
        assertTrue(result.workflow.steps.size <= policy.maxActions)
    }

    @Test
    fun emptySnapshotCannotBeGroundedAgainst() {
        val result = WorkflowSynthesizer.synthesize(
            "tap \"Apps\"", ScreenSnapshot("com.android.settings", 0, emptyList())
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun reportExplainsEveryDecision() {
        val report = synthesize("open \"Network & internet\"").getOrThrow().report()
        assertTrue(report.contains("grounding"))
        assertTrue(report.contains("policy"))
        assertTrue(report.contains("com.android.settings"))
    }

    @Test
    fun generatedJsonRoundTripsThroughTheParser() {
        val result = synthesize("tap \"Apps\" then wait for \"Apps\"").getOrThrow()
        val reparsed = Workflow.parse(result.json)
        assertEquals(result.workflow.steps.size, reparsed.steps.size)
        assertTrue(PlanValidator.validate(reparsed).isEmpty())
    }
}
