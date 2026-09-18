package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontierRuntimeTest {
    @Test fun `observation stabilizer requires quorum and has a deadline`() {
        val stabilizer = ObservationStabilizer(requiredConsecutive = 2, maxSamples = 3)
        assertNull(stabilizer.offer(AgentObservation("a", "app", emptyList())).observation)
        assertTrue(stabilizer.offer(AgentObservation("a", "app", emptyList())).settled)
        stabilizer.reset()
        stabilizer.offer(AgentObservation("a", "app", emptyList()))
        stabilizer.offer(AgentObservation("b", "app", emptyList()))
        val deadline = stabilizer.offer(AgentObservation("c", "app", emptyList()))
        assertFalse(deadline.settled)
        assertEquals("c", deadline.observation?.screenId)
    }

    @Test fun `hierarchy advances only with preconditions and observed evidence`() {
        val goal = AgentGoal("open account then open privacy", "privacy", "app")
        val executor = HierarchicalExecutor(HierarchicalPlanner.decompose(goal))
        assertFalse(executor.progress(emptySet(), false, "open account")!!.completed)
        assertTrue(executor.progress(emptySet(), true, "open account")!!.completed)
        assertEquals("open privacy", executor.current?.description)
        assertTrue(executor.progress(setOf("privacy"), false, null)!!.completed)
        assertTrue(executor.isComplete)
    }

    @Test fun `local model ranks only caller supplied safe ids`() {
        val assistant = LocalStructuredModelAssistant()
        val observation = AgentObservation("s", "app", listOf(
            AgentAction("safe", "open privacy"), AgentAction("unsafe", "open privacy and pay")
        ))
        val output = assistant.rankSafeCandidates(AgentGoal("open privacy", "privacy", "app"), observation, setOf("safe"))
        assertEquals(listOf("safe"), output.actionIds)
        assertTrue(ModelOutputValidator.validateRanking(output, setOf("safe")) != null)
    }
}
