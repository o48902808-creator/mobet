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

    // ── Route-ordered grounding ──────────────────────────────────────────────

    /** Two screens of the same app both offer "Continue"; only one is reachable from here. */
    private val detour = ScreenSnapshot(
        "com.android.settings", 3_000,
        listOf(element("Continue", "viewId: com.android.settings:id/detour_continue"))
    )
    private val reachable = ScreenSnapshot(
        "com.android.settings", 1_500,
        listOf(element("Continue", "viewId: com.android.settings:id/network_continue"))
    )

    @Test
    fun theScreenActuallyReachableFromHereWinsOverTheMoreRecentOne() {
        // Observed route: home → reachable. The detour screen is newer but unconnected.
        SessionScreenMemory.remember(home)
        SessionScreenMemory.remember(reachable)
        SessionScreenMemory.remember(detour)

        val result = WorkflowSynthesizer.synthesize("tap \"Continue\"", home, options).getOrThrow()
        val tap = result.workflow.steps.first { it.action == "tap" }
        assertEquals("com.android.settings:id/network_continue", tap.selector.viewId)
    }

    @Test
    fun revisitingAScreenIsNotRecordedAsATransition() {
        SessionScreenMemory.remember(home)
        SessionScreenMemory.remember(home)
        val id = SessionScreenMemory.identify(home.elements)
        assertTrue(SessionScreenMemory.successors("com.android.settings", id).isEmpty())
    }

    @Test
    fun successorsAreRankedByHowOftenTheyWereObserved() {
        val homeId = SessionScreenMemory.identify(home.elements)
        repeat(3) {
            SessionScreenMemory.remember(home)
            SessionScreenMemory.remember(networkScreen)
        }
        SessionScreenMemory.remember(home)
        SessionScreenMemory.remember(detour)
        val successors = SessionScreenMemory.successors("com.android.settings", homeId)
        assertEquals(SessionScreenMemory.identify(networkScreen.elements), successors.first().screenId)
    }

    // ── Consented OCR as a diagnostic ────────────────────────────────────────

    @Test
    fun ocrDistinguishesAMissingControlFromAnInaccessibleOne() {
        val withOcr = WorkflowSynthesizer.synthesize(
            "tap \"Print\"", home,
            options.copy(screenMemory = ScreenMemory.EMPTY, ocrText = listOf("Print", "Share"))
        )
        assertTrue(withOcr.exceptionOrNull()!!.message!!.contains("exposes no accessibility node"))

        val withoutOcr = WorkflowSynthesizer.synthesize(
            "tap \"Print\"", home, options.copy(screenMemory = ScreenMemory.EMPTY)
        )
        assertFalse(withoutOcr.exceptionOrNull()!!.message!!.contains("accessibility node"))
    }

    @Test
    fun ocrNeverProducesAPlanStep() {
        val result = WorkflowSynthesizer.synthesize(
            "tap \"Print\"", home,
            options.copy(screenMemory = ScreenMemory.EMPTY, ocrText = listOf("Print"))
        )
        assertTrue(result.isFailure)
    }
}
