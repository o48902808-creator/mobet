package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelectorOutcomesTest {

    private val pkg = "com.example.app"
    private val reliable = SelectorSpec("viewId", "com.example.app:id/next")
    private val flaky = SelectorSpec("text", "Next")

    @Before
    fun reset() = SelectorOutcomes.clear()

    @After
    fun cleanUp() = SelectorOutcomes.clear()

    @Test
    fun historyNeedsAMinimumOfObservations() {
        SelectorOutcomes.recordSuccess(pkg, reliable)
        assertEquals(0.0, SelectorOutcomes.adjustment(pkg, reliable), 1e-9)
        SelectorOutcomes.recordSuccess(pkg, reliable)
        assertTrue(SelectorOutcomes.adjustment(pkg, reliable) > 0.0)
    }

    @Test
    fun adjustmentStaysWithinTheDocumentedCeiling() {
        repeat(50) { SelectorOutcomes.recordSuccess(pkg, reliable) }
        repeat(50) { SelectorOutcomes.recordFailure(pkg, flaky) }
        assertEquals(GroundingPriors.MAX_ADJUSTMENT, SelectorOutcomes.adjustment(pkg, reliable), 1e-9)
        assertEquals(-GroundingPriors.MAX_ADJUSTMENT, SelectorOutcomes.adjustment(pkg, flaky), 1e-9)
    }

    @Test
    fun historyIsScopedToItsOwnPackage() {
        repeat(4) { SelectorOutcomes.recordSuccess(pkg, reliable) }
        assertEquals(0.0, SelectorOutcomes.adjustment("com.other.app", reliable), 1e-9)
    }

    @Test
    fun typedRunnerSelectorsMapToTheStrongestField() {
        val spec = SelectorOutcomes.specOf(Selector(text = "Next", viewId = "id/next"))
        assertEquals(SelectorSpec("viewId", "id/next"), spec)
        assertEquals(null, SelectorOutcomes.specOf(Selector()))
    }

    @Test
    fun historyBreaksATieButNeverAdmitsAnAbsentTarget() {
        val elements = listOf(
            InspectedElement("Next", "Button", "text: Next", 1, 90, "0,0–10,10"),
            InspectedElement("Next", "Button", "viewId: com.example.app:id/next", 1, 90, "0,0–10,10")
        )
        // Without history the two candidates are indistinguishable.
        val ambiguous = SnapshotGrounder.ground("Next", elements)
        assertTrue(ambiguous is Grounding.Ambiguous)

        // With a track record on one of them, ranking prefers it deterministically…
        repeat(6) { SelectorOutcomes.recordSuccess(pkg, reliable) }
        repeat(6) { SelectorOutcomes.recordFailure(pkg, flaky) }
        val informed = SnapshotGrounder.ground("Next", elements, packageName = pkg, priors = SelectorOutcomes)
        assertTrue(informed is Grounding.Resolved)
        assertEquals(reliable, (informed as Grounding.Resolved).best.selector)

        // …but history can never conjure a target that is not on the screen.
        val absent = SnapshotGrounder.ground("Teleporter", elements, packageName = pkg, priors = SelectorOutcomes)
        assertTrue(absent is Grounding.NotFound)
    }

    @Test
    fun generationRecordsTheLearnedPriorInItsReport() {
        repeat(6) { SelectorOutcomes.recordSuccess(pkg, reliable) }
        val snapshot = ScreenSnapshot(
            pkg, 0,
            listOf(InspectedElement("Next", "Button", "viewId: com.example.app:id/next", 1, 90, "0,0–10,10"))
        )
        val result = WorkflowSynthesizer.synthesize("tap \"Next\"", snapshot).getOrThrow()
        assertTrue(result.notes.any { it.detail.contains("learned prior") })
    }

    @Test
    fun priorsCanBeTurnedOffEntirely() {
        repeat(6) { SelectorOutcomes.recordSuccess(pkg, reliable) }
        val snapshot = ScreenSnapshot(
            pkg, 0,
            listOf(InspectedElement("Next", "Button", "viewId: com.example.app:id/next", 1, 90, "0,0–10,10"))
        )
        val result = WorkflowSynthesizer.synthesize(
            "tap \"Next\"", snapshot, SynthesisOptions(priors = NoGroundingPriors)
        ).getOrThrow()
        assertTrue(result.notes.none { it.detail.contains("learned prior") })
    }
}
