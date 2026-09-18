package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentIntelligenceTest {
    @Test
    fun `simulation rejects excess risk and premature irreversible actions`() {
        val goal = AgentGoal("save draft", "saved", "example.app", maxCycles = 2, maxRisk = 20)
        val violations = AgentPlanValidator.validate(
            goal,
            listOf(
                AgentAction("publish", "publish", risk = 80, reversible = false),
                AgentAction("next", "next")
            )
        )
        assertEquals(2, violations.size)
        assertTrue(violations.any { it.message.contains("risk") })
        assertTrue(violations.any { it.message.contains("terminal") })
    }

    @Test
    fun `navigator finds learned route while excluding dead ends`() {
        val memory = InMemoryExperienceStore()
        memory.record(TransitionExperience("a", "bad", "trap", true))
        memory.record(TransitionExperience("a", "open", "b", true))
        memory.record(TransitionExperience("b", "finish", "goal", true))
        memory.markDeadEnd("a", "bad")
        assertEquals(listOf("open", "finish"), ExperienceNavigator(memory).route("a", "goal"))
    }

    @Test
    fun `navigator terminates on cyclic graph`() {
        val memory = InMemoryExperienceStore()
        memory.record(TransitionExperience("a", "next", "b", true))
        memory.record(TransitionExperience("b", "again", "a", true))
        assertNull(ExperienceNavigator(memory).route("a", "missing", maxExpansions = 4))
    }

    @Test
    fun `deliberator blocks risky actions`() {
        val memory = InMemoryExperienceStore()
        val observation = AgentObservation(
            "screen", "example.app",
            listOf(AgentAction("safe", "continue", 2), AgentAction("pay", "pay now", 90))
        )
        val result = Deliberator(memory).choose(
            observation,
            AgentGoal("pay now", "paid", "example.app", maxRisk = 29),
            emptySet()
        )
        assertEquals("safe", result.action?.id)
    }
}
