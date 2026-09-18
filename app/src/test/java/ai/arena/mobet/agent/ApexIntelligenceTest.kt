package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexIntelligenceTest {
    @Test fun `belief state preserves ambiguity and weights accessibility above OCR`() {
        val belief = BeliefReasoner.infer(listOf(
            ObservationEvidence("settings", EvidenceSource.ACCESSIBILITY, .9),
            ObservationEvidence("settlings", EvidenceSource.OCR, .9),
            ObservationEvidence("settings", EvidenceSource.WORLD_MODEL, .7)
        ))
        assertEquals("settings", belief.hypotheses.first().proposition)
        assertTrue(belief.hypotheses.size == 2)
        assertTrue(belief.ambiguity > 0.0)
    }

    @Test fun `ambiguous actions cause abstention at strict confidence`() {
        val result = Deliberator(InMemoryExperienceStore()).choose(
            AgentObservation("s", "app", listOf(
                AgentAction("a", "open profile"), AgentAction("b", "open profile")
            )),
            AgentGoal("open profile", "profile", "app", minConfidence = .8), emptySet()
        )
        assertNull(result.action)
        assertTrue(result.reason.contains("ambiguous"))
    }

    @Test fun `hierarchical plan tracks preconditions and completion evidence`() {
        val plan = HierarchicalPlanner.decompose(AgentGoal(
            "open account then choose privacy then open permissions", "permissions", "app"
        ))
        assertEquals(3, plan.subgoals.size)
        assertTrue(plan.subgoals.first().preconditions.contains("package:app"))
        assertTrue(plan.subgoals.last().completionEvidence.contains("permissions"))
    }

    @Test fun `lookahead obeys strict expansion budget and avoids cycles`() {
        val memory = InMemoryExperienceStore()
        memory.record(TransitionExperience("a", "go", "b", true))
        memory.record(TransitionExperience("b", "next", "a", true))
        val paths = BudgetedLookahead(memory).simulate(
            AgentObservation("a", "app", listOf(AgentAction("go", "go"))),
            AgentGoal("go", "done", "app", lookaheadExpansions = 2)
        )
        assertTrue(paths.all { it.expansionsUsed <= 2 })
    }

    @Test fun `model output cannot introduce unknown action authority`() {
        val output = ModelRanking(listOf("safe", "invented"), .9)
        assertNull(ModelOutputValidator.validateRanking(output, setOf("safe")))
    }

    @Test fun `failure recovery is specific and bounded`() {
        assertEquals(RecoveryAction.ASK_USER,
            RecoveryPolicies.forFailure(FailureKind.PERMISSION_GATE).action)
        assertEquals(1, RecoveryPolicies.forFailure(FailureKind.STALE_SELECTOR).maxAttempts)
        assertEquals(FailureKind.WRONG_APP, FailureClassifier.classify(
            AgentObservation("a", "one", emptyList()), AgentObservation("b", "two", emptyList()), "", 10
        ))
    }
}
