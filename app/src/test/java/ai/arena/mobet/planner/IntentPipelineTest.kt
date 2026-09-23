package ai.arena.mobet.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentPipelineTest {
    @Test fun reviewedVoiceBecomesStructuredBoundedGoal() {
        val preview = IntentToPlanPipeline.prepare(
            "  open   display settings  ",
            " Dark theme ",
            "com.android.settings",
            IntentSource.VOICE_TRANSCRIPT
        ).getOrThrow()
        assertEquals("open display settings", preview.goal.description)
        assertEquals("Dark theme", preview.goal.successFact)
        assertEquals(IntentSource.VOICE_TRANSCRIPT, preview.goal.source)
        assertEquals(29, preview.maxRisk)
        assertTrue("tap" in preview.allowedActions)
    }

    @Test fun blankOrOversizedInputIsRejected() {
        assertTrue(IntentToPlanPipeline.prepare("", "done", "com.example.app", IntentSource.TYPED).isFailure)
        assertTrue(IntentToPlanPipeline.prepare("x".repeat(501), "done", "com.example.app", IntentSource.TYPED).isFailure)
        assertTrue(IntentToPlanPipeline.prepare("goal", "x".repeat(201), "com.example.app", IntentSource.TYPED).isFailure)
    }

    @Test fun invalidPackageIsRejected() {
        assertTrue(IntentToPlanPipeline.prepare("goal", "done", "not a package", IntentSource.TYPED).isFailure)
    }

    @Test fun explainAndDryRunPromiseNoEffects() {
        val preview = IntentToPlanPipeline.prepare(
            "open display", "Display", "com.android.settings", IntentSource.TYPED
        ).getOrThrow()
        assertTrue(preview.explanation(RunMode.EXPLAIN).contains("Device effects: none"))
        assertTrue(preview.explanation(RunMode.DRY_RUN).contains("Device effects: none"))
        assertFalse(preview.explanation(RunMode.EXECUTE).contains("Device effects: none"))
    }

    @Test fun agentGoalPreservesHardSafetyBudget() {
        val preview = IntentToPlanPipeline.prepare(
            "open display", "Display", "com.android.settings", IntentSource.TYPED
        ).getOrThrow()
        val goal = preview.asAgentGoal(allowOcr = true, allowModel = true)
        assertEquals("com.android.settings", goal.allowedPackage)
        assertEquals(20, goal.maxCycles)
        assertEquals(29, goal.maxRisk)
        assertTrue(goal.allowOcrEvidence)
        assertTrue(goal.allowModelAssistance)
    }
}
