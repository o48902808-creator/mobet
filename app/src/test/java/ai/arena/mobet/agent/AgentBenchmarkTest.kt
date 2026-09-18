package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBenchmarkTest {
    @Test
    fun `solves thirty navigation tasks within ninety total cycles`() {
        var successes = 0
        var totalCycles = 0
        repeat(30) { task ->
            val target = "destination-$task"
            val device = GraphDevice(
                start = "home-$task",
                screens = mapOf(
                    "home-$task" to Screen(
                        listOf(
                            AgentAction("noise-$task", "unrelated option"),
                            AgentAction("open-$task", "open destination $task")
                        )
                    ),
                    target to Screen(emptyList(), setOf("arrived-$task")),
                    "noise" to Screen(emptyList())
                ),
                edges = mapOf(
                    ("home-$task" to "open-$task") to target,
                    ("home-$task" to "noise-$task") to "noise"
                )
            )
            val memory = InMemoryExperienceStore()
            val result = AutonomousAgent(device, memory).run(
                AgentGoal("open destination $task", "arrived-$task", "test.app", maxCycles = 6)
            )
            if (result.status == AgentStatus.SUCCEEDED) successes++
            totalCycles += result.cycles
        }
        assertEquals("benchmark success rate", 30, successes)
        assertTrue("expected <= 90 cycles, was $totalCycles", totalCycles <= 90)
    }

    @Test
    fun `backtracks and remembers a dead branch`() {
        val memory = InMemoryExperienceStore()
        val device = GraphDevice(
            "home",
            mapOf(
                "home" to Screen(listOf(AgentAction("a", "goal decoy"), AgentAction("b", "goal route"))),
                "dead" to Screen(emptyList()),
                "done" to Screen(emptyList(), setOf("goal"))
            ),
            mapOf(("home" to "a") to "dead", ("home" to "b") to "done")
        )
        val first = AutonomousAgent(device, memory).run(AgentGoal("goal", "goal", "test.app", 6))
        assertEquals(AgentStatus.SUCCEEDED, first.status)
        assertTrue(first.actions.contains("back"))
        assertTrue(memory.isDeadEnd("home", "a"))
    }

    private data class Screen(val actions: List<AgentAction>, val facts: Set<String> = emptySet())

    private class GraphDevice(
        start: String,
        private val screens: Map<String, Screen>,
        private val edges: Map<Pair<String, String>, String>
    ) : AgentDevice {
        private var current = start
        private val history = ArrayDeque<String>()

        override fun observe(): AgentObservation {
            val screen = requireNotNull(screens[current])
            return AgentObservation(current, "test.app", screen.actions, screen.facts)
        }

        override fun act(action: AgentAction): ActionReceipt {
            val next = edges[current to action.id] ?: return ActionReceipt(false, "no edge")
            history.addLast(current)
            current = next
            return ActionReceipt(true)
        }

        override fun back(): ActionReceipt {
            current = history.removeLastOrNull() ?: return ActionReceipt(false, "empty history")
            return ActionReceipt(true)
        }
    }
}
