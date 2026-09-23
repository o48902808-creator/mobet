package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTimelineTest {
    @Test fun roundTripPreservesExplainabilityFields() {
        val event = ExecutionTimelineEvent(
            state = ExecutionTimelineEvent.State.RUNNING,
            mode = "Autonomous",
            goal = "Enable dark mode",
            subgoal = "Open display settings",
            step = 3,
            totalSteps = 7,
            screenFingerprint = "abc123",
            action = "tap · dark-theme",
            confidence = 96,
            evidence = "highest bounded utility",
            risk = "reversible · score 12",
            policy = "Allowed by AgentPlanValidator",
            recovery = null
        )
        assertEquals(event, ExecutionTimelineEvent.parse(event.toJson()))
    }

    @Test fun optionalFieldsRemainAbsent() {
        val parsed = ExecutionTimelineEvent.parse(
            ExecutionTimelineEvent(
                ExecutionTimelineEvent.State.PLANNING,
                "Workflow",
                "Demo"
            ).toJson()
        )
        assertNull(parsed.action)
        assertNull(parsed.confidence)
        assertNull(parsed.stopReason)
    }

    @Test fun codecBoundsTextAndConfidence() {
        val parsed = ExecutionTimelineEvent.parse(
            ExecutionTimelineEvent(
                state = ExecutionTimelineEvent.State.RUNNING,
                mode = "x".repeat(500),
                goal = "g".repeat(500),
                confidence = 400
            ).toJson()
        )
        assertEquals(300, parsed.goal.length)
        assertEquals(100, parsed.confidence)
    }

    @Test fun oversizedBroadcastPayloadIsRejected() {
        val result = runCatching { ExecutionTimelineEvent.parse(" ".repeat(8_193)) }
        assertTrue(result.isFailure)
    }

    @Test fun evidenceSummaryNeverIncludesFillValue() {
        val step = Step(
            action = "fill",
            value = "super-secret-value",
            expect = Expectation(textPresent = "Signed in")
        )
        val summary = step.timelineEvidence()
        assertEquals("declared text present", summary)
        assertTrue("fill value must not enter timeline", !summary.contains("super-secret"))
    }
}
