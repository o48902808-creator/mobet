package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Adversarial and determinism hardening for the generation engine: screen-borne template
 * injection, grammar fuzzing, output determinism under input perturbation, and diff integrity.
 */
class SynthesisHardeningTest {

    private fun element(label: String, selector: String, role: String = "Button", confidence: Int = 90) =
        InspectedElement(label, role, selector, 1, confidence, "0,0–10,10")

    private val screen = ScreenSnapshot(
        "com.example.app", 0,
        listOf(
            element("Continue", "text: Continue"),
            element("Settings", "text: Settings"),
            element("Done", "text: Done"),
            element("Email", "viewId: com.example.app:id/email", role = "EditText")
        )
    )

    // ── Screen-borne template injection ──────────────────────────────────────

    @Test
    fun aHostileLabelCannotSmuggleASecretReferenceIntoAPlan() {
        val hostile = screen.copy(
            elements = screen.elements + element("{{secret:bank.pin}}", "text: {{secret:bank.pin}}")
        )
        // The control is simply not groundable, so no clause can target it…
        val direct = WorkflowSynthesizer.synthesize("tap \"{{secret:bank.pin}}\"", hostile)
        assertTrue(direct.isFailure)

        // …and it never appears in a plan generated for anything else on that screen.
        val benign = WorkflowSynthesizer.synthesize("tap \"Continue\"", hostile).getOrThrow()
        assertFalse(benign.json.contains("secret:bank.pin"))
    }

    @Test
    fun recordedTracesRejectTemplateSyntax() {
        val trace = """[{"action":"tap","text":"{{secret:bank.pin}}"}]"""
        val result = TraceSynthesizer.synthesize(trace, "com.example.app")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("template syntax"))
    }

    @Test
    fun selectorFactoryRefusesTemplateSyntaxOnEveryPath() {
        assertNull(SelectorSpec.of("text", "{{var:x}}"))
        assertNull(SelectorSpec.parse("text: {{secret:x}}"))
        assertNull(SelectorSpec.of("text", "   "))
        assertNull(SelectorSpec.of("bogus", "value"))
        assertEquals(SelectorSpec("text", "Continue"), SelectorSpec.parse("text: Continue"))
    }

    // ── Grammar fuzzing ──────────────────────────────────────────────────────

    @Test
    fun grammarFuzzingNeverThrowsOutsideTheResultBoundary() {
        val fragments = listOf(
            "tap", "open", "fill", "scroll", "wait for", "verify", "if", "then", "repeat",
            "until", "back", "home", "with", "max", "\"Continue\"", "\"Done\"", "\"\"", "''",
            ";", ",", "\n", "{{secret:x}}", "}}", "0", "99999", "…", "🙂", "-", "  "
        )
        val random = Random(20260924)
        repeat(600) {
            val goal = (1..random.nextInt(1, 12)).joinToString(" ") { fragments.random(random) }
            val result = IntentGrammar.parse(goal)
            result.onFailure { error ->
                // Every rejection must be an explained argument error, never a crash class.
                assertTrue(
                    "unexpected ${error::class.java.name} for “$goal”",
                    error is IllegalArgumentException || error is IllegalStateException
                )
                assertTrue("empty message for “$goal”", !error.message.isNullOrBlank())
            }
            result.onSuccess { intents ->
                assertTrue(intents.size <= IntentGrammar.MAX_INTENTS)
                // Anything the grammar accepts must survive the whole pipeline or fail cleanly.
                WorkflowSynthesizer.synthesize(intents, screen, SynthesisOptions(priors = NoGroundingPriors))
                    .onSuccess { plan -> assertTrue(PlanValidator.validate(plan.workflow).isEmpty()) }
            }
        }
    }

    @Test
    fun pathologicalInputIsBoundedNotHung() {
        val huge = (1..500).joinToString(" then ") { "tap \"Continue\"" }
        val result = IntentGrammar.parse(huge)
        assertTrue(result.isFailure)
        assertTrue(IntentGrammar.parse("x".repeat(5_000)).isFailure)
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    private val goldenGoals = listOf(
        "tap \"Continue\"",
        "open \"Settings\" then tap \"Done\"",
        "fill \"Email\" with \"someone@example.com\"; verify \"Done\" appears",
        "if \"Continue\" appears then tap \"Continue\"\ntap \"Done\"",
        "repeat scroll until \"Done\" appears max 4"
    )

    @Test
    fun repeatedGenerationIsByteIdentical() {
        goldenGoals.forEach { goal ->
            val first = WorkflowSynthesizer.synthesize(goal, screen, SynthesisOptions(priors = NoGroundingPriors))
            val second = WorkflowSynthesizer.synthesize(goal, screen, SynthesisOptions(priors = NoGroundingPriors))
            assertEquals("non-deterministic output for “$goal”", first.getOrThrow().json, second.getOrThrow().json)
        }
    }

    @Test
    fun snapshotElementOrderDoesNotChangeTheGeneratedPlan() {
        val shuffled = screen.copy(elements = screen.elements.reversed())
        goldenGoals.forEach { goal ->
            val ordered = WorkflowSynthesizer.synthesize(goal, screen, SynthesisOptions(priors = NoGroundingPriors))
            val permuted = WorkflowSynthesizer.synthesize(goal, shuffled, SynthesisOptions(priors = NoGroundingPriors))
            assertEquals("order-sensitive output for “$goal”", ordered.getOrThrow().json, permuted.getOrThrow().json)
        }
    }

    @Test
    fun everyGoldenPlanParsesAndValidates() {
        goldenGoals.forEach { goal ->
            val plan = WorkflowSynthesizer.synthesize(goal, screen, SynthesisOptions(priors = NoGroundingPriors)).getOrThrow()
            val reparsed = Workflow.parse(plan.json)
            assertEquals(plan.workflow.steps.size, reparsed.steps.size)
            assertTrue(PlanValidator.validate(reparsed).isEmpty())
        }
    }

    // ── Diff ─────────────────────────────────────────────────────────────────

    @Test
    fun diffAgainstAnIdenticalPlanReportsNoChanges() {
        val plan = WorkflowSynthesizer.synthesize("tap \"Continue\"", screen, SynthesisOptions(priors = NoGroundingPriors)).getOrThrow()
        val diff = PlanDiff.between(plan.json, plan.workflow)!!
        assertTrue(diff.isIdentical)
        assertTrue(diff.render().contains("identical"))
    }

    @Test
    fun diffCountsAddedAndRemovedSteps() {
        val options = SynthesisOptions(priors = NoGroundingPriors)
        val small = WorkflowSynthesizer.synthesize("tap \"Continue\"", screen, options).getOrThrow()
        val larger = WorkflowSynthesizer.synthesize("tap \"Continue\" then tap \"Done\"", screen, options).getOrThrow()
        val diff = PlanDiff.between(small.json, larger.workflow)!!
        assertFalse(diff.isIdentical)
        assertTrue(diff.added >= 2)
        assertEquals(0, diff.removed)
        assertTrue(diff.unchanged >= 2)
    }

    @Test
    fun diffIsSkippedWhenThereIsNothingComparable() {
        val plan = WorkflowSynthesizer.synthesize("tap \"Continue\"", screen, SynthesisOptions(priors = NoGroundingPriors)).getOrThrow()
        assertNull(PlanDiff.between("", plan.workflow))
        assertNull(PlanDiff.between("{ not json", plan.workflow))
    }
}
