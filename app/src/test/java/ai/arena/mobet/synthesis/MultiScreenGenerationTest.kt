package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.WorkflowDocument
import ai.arena.mobet.policy.PlanValidator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Screen-graph grounding, generation-time simulation, and the authored-document quality read-out. */
class MultiScreenGenerationTest {

    private fun element(label: String, selector: String, role: String = "Button") =
        InspectedElement(label, role, selector, 1, 90, "0,0–10,10")

    private val home = ScreenSnapshot(
        "com.android.settings", 1_000,
        listOf(
            element("Network & internet", "text: Network & internet"),
            element("Apps", "text: Apps")
        )
    )

    private val networkScreen = ScreenSnapshot(
        "com.android.settings", 2_000,
        listOf(
            element("Wi-Fi", "text: Wi-Fi"),
            element("Add network", "text: Add network"),
            element("Mobile network", "text: Mobile network")
        )
    )

    private val options get() = SynthesisOptions(priors = NoGroundingPriors)

    @Before
    fun reset() = SessionScreenMemory.clear()

    @After
    fun cleanUp() = SessionScreenMemory.clear()

    // ── Multi-screen grounding ───────────────────────────────────────────────

    @Test
    fun withoutMemoryAMultiScreenGoalIsHonestlyRejected() {
        val result = WorkflowSynthesizer.synthesize(
            "open \"Network & internet\" then tap \"Wi-Fi\"",
            home,
            options.copy(screenMemory = ScreenMemory.EMPTY)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not on the captured screen"))
    }

    @Test
    fun aRememberedScreenMakesTheSameGoalPlannable() {
        SessionScreenMemory.remember(networkScreen)
        val result = WorkflowSynthesizer.synthesize(
            "open \"Network & internet\" then tap \"Wi-Fi\"", home, options
        ).getOrThrow()
        val steps = result.workflow.steps
        assertTrue(steps.any { it.action == "tap" && it.selector.text == "Wi-Fi" })
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun memoryGroundedStepsAreAlwaysPrecededByAWait() {
        SessionScreenMemory.remember(networkScreen)
        val result = WorkflowSynthesizer.synthesize(
            "open \"Network & internet\" then tap \"Wi-Fi\"",
            home,
            // Even with wait insertion switched off, the unverified step keeps its check.
            options.copy(insertWaits = false)
        ).getOrThrow()
        val steps = result.workflow.steps
        val tapIndex = steps.indexOfFirst { it.action == "tap" && it.selector.text == "Wi-Fi" }
        assertTrue(tapIndex > 0)
        assertEquals("wait", steps[tapIndex - 1].action)
        assertEquals("Wi-Fi", steps[tapIndex - 1].selector.text)
    }

    @Test
    fun memoryGroundingIsDisclosedInTheReport() {
        SessionScreenMemory.remember(networkScreen)
        val result = WorkflowSynthesizer.synthesize("tap \"Add network\"", home, options).getOrThrow()
        assertTrue(result.notes.any { it.detail.contains("grounded from a screen seen") })
        assertTrue(result.report().contains("grounded from a screen seen"))
    }

    @Test
    fun memoryNeverCrossesAPackageBoundary() {
        SessionScreenMemory.remember(networkScreen.copy(packageName = "com.other.app"))
        val result = WorkflowSynthesizer.synthesize("tap \"Wi-Fi\"", home, options)
        assertTrue(result.isFailure)
    }

    @Test
    fun theLiveScreenAlwaysWinsOverMemory() {
        // A stale screen also contains "Apps", but with a different selector.
        SessionScreenMemory.remember(
            home.copy(capturedAt = 500, elements = listOf(element("Apps", "viewId: com.android.settings:id/apps_old")))
        )
        val result = WorkflowSynthesizer.synthesize("tap \"Apps\"", home, options).getOrThrow()
        val tap = result.workflow.steps.first { it.action == "tap" }
        assertEquals("Apps", tap.selector.text)
        assertFalse(result.notes.any { it.detail.contains("grounded from a screen seen") })
    }

    @Test
    fun rememberedScreensAreBoundedAndDeduplicated() {
        repeat(3) { SessionScreenMemory.remember(networkScreen) }
        assertEquals(1, SessionScreenMemory.screens("com.android.settings").size)
        repeat(SessionScreenMemory.MAX_SCREENS_PER_PACKAGE + 6) { index ->
            SessionScreenMemory.remember(
                home.copy(elements = listOf(element("Item $index", "text: Item $index")))
            )
        }
        assertTrue(
            SessionScreenMemory.screens("com.android.settings").size <=
                SessionScreenMemory.MAX_SCREENS_PER_PACKAGE
        )
    }

    // ── Generation-time simulation ───────────────────────────────────────────

    @Test
    fun generatedPlansCarryADryRunReport() {
        val result = WorkflowSynthesizer.synthesize("tap \"Apps\"", home, options).getOrThrow()
        assertNotNull(result.simulation)
        assertTrue(result.simulation!!.contains("DRY RUN"))
        assertTrue(result.report().contains("DRY RUN"))
    }

    @Test
    fun pathsWithoutASnapshotSimplyHaveNoSimulation() {
        val trace = """[{"action":"tap","text":"Save"}]"""
        val result = TraceSynthesizer.synthesize(trace, "com.example.app").getOrThrow()
        assertEquals(null, result.simulation)
    }

    // ── Quality in the authored document ─────────────────────────────────────

    @Test
    fun documentSummaryCarriesTheRobustnessGrade() {
        val plan = WorkflowSynthesizer.synthesize("tap \"Apps\"", home, options).getOrThrow()
        val summary = WorkflowDocument.summarize(plan.json)!!
        assertNotNull(summary.quality)
        assertTrue(summary.quality!!.score in 0..100)
        assertTrue(summary.quality!!.grade.isNotEmpty())
    }

    @Test
    fun anUnparsableDocumentHasNoGrade() {
        assertEquals(null, WorkflowDocument.summarize("{ broken")!!.quality)
    }
}
