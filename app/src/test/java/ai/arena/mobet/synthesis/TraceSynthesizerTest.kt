package ai.arena.mobet.synthesis

import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSynthesizerTest {

    private val trace = """
        [
          {"action":"tap","text":"Settings"},
          {"action":"tap","text":"Settings"},
          {"action":"scroll"},
          {"action":"scroll"},
          {"action":"scroll"},
          {"action":"scroll"},
          {"action":"scroll"},
          {"action":"tap","viewId":"com.example.app:id/save"}
        ]
    """.trimIndent()

    @Test
    fun recordedTraceBecomesAValidatedWorkflow() {
        val result = TraceSynthesizer.synthesize(trace, "com.example.app").getOrThrow()
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
        assertEquals("com.example.app", result.workflow.packageName)
    }

    @Test
    fun duplicateTapsAreDroppedAndScrollRunsCoalesced() {
        val steps = TraceSynthesizer.synthesize(trace, "com.example.app").getOrThrow().workflow.steps
        assertEquals(2, steps.count { it.action == "tap" })
        assertEquals(3, steps.count { it.action == "scroll" })
    }

    @Test
    fun waitsArePlacedBeforeEveryTap() {
        val steps = TraceSynthesizer.synthesize(trace, "com.example.app").getOrThrow().workflow.steps
        steps.forEachIndexed { index, step ->
            if (step.action == "tap") {
                val previous = steps[index - 1]
                assertTrue(previous.action == "wait" || previous.action == "confirm")
            }
        }
    }

    @Test
    fun cleanupCanBeDisabled() {
        val raw = TraceSynthesizer.synthesize(
            trace,
            "com.example.app",
            TraceOptions(
                dropRepeats = false,
                coalesceScrolls = false,
                insertWaits = false,
                optimize = false
            )
        ).getOrThrow()
        assertEquals(8, raw.workflow.steps.size)
    }

    @Test
    fun theOptimizerStillTrimsBlindScrollRunsWhenTraceCleanupIsOff() {
        // Trace-level coalescing and the plan optimizer are independent passes; with trace
        // cleanup off the optimizer is the remaining backstop against a 5-deep scroll run.
        val lean = TraceSynthesizer.synthesize(
            trace,
            "com.example.app",
            TraceOptions(dropRepeats = false, coalesceScrolls = false, insertWaits = false)
        ).getOrThrow()
        assertEquals(3, lean.workflow.steps.count { it.action == "scroll" })
        assertTrue(lean.notes.any { it.stage == "optimizer" })
    }

    @Test
    fun unsupportedRecordedActionIsRejected() {
        val result = TraceSynthesizer.synthesize(
            """[{"action":"fill","text":"Password","value":"hunter2"}]""",
            "com.example.app"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unsupported action"))
    }

    @Test
    fun emptyRecordingIsRejected() {
        assertTrue(TraceSynthesizer.synthesize("[]", "com.example.app").isFailure)
    }

    @Test
    fun invalidPackageIsRejected() {
        assertTrue(TraceSynthesizer.synthesize(trace, "not-a-package").isFailure)
    }
}
