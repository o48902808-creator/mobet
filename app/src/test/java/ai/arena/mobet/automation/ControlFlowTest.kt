package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the rails that make control flow stay bounded: the jump table, the hop budget that
 * catches actionless infinite loops, the action budget that keeps loops inside the policy
 * that was approved, and per-step repeat counters that nested loops cannot poison.
 */
class ControlFlowTest {

    private fun steps(vararg specs: Pair<String?, String>): List<Step> =
        specs.map { (label, action) -> Step(action = action, label = label) }

    @Test
    fun labelsMapResolvesJumpTargets() {
        val flow = ControlFlow(steps("start" to "delay", null to "wait", "gate" to "delay"))
        assertEquals(0, flow.jumpTarget("start"))
        assertEquals(2, flow.jumpTarget("gate"))
    }

    @Test
    fun unknownLabelResolvesToNull() {
        val flow = ControlFlow(steps("start" to "delay"))
        assertNull(flow.jumpTarget("missing"))
    }

    @Test
    fun duplicateLabelsAreRefusedEvenWithoutValidation() {
        val error = runCatching {
            ControlFlow(steps("a" to "delay", "a" to "delay"))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("Duplicate step label") == true)
    }

    @Test
    fun controlHopRailTripAtTheLimit() {
        val flow = ControlFlow(steps(null to "delay"))
        repeat(ControlFlow.MAX_CONTROL_HOPS) { hop ->
            assertTrue("hop $hop should be within budget", flow.consumeControlHop())
        }
        assertFalse(flow.consumeControlHop())
    }

    @Test
    fun actionBudgetIsEnforcedDynamicallyNotJustStatically() {
        val flow = ControlFlow(steps(null to "delay"))
        assertTrue(flow.consumeAction(maxActions = 2))
        assertTrue(flow.consumeAction(maxActions = 2))
        assertFalse(flow.consumeAction(maxActions = 2))
    }

    @Test
    fun repeatIterationsArePerStepAndCappedIndependently() {
        val flow = ControlFlow(steps(null to "delay"))
        assertTrue(flow.consumeIteration(stepIndex = 0, maxIterations = 2))
        assertTrue(flow.consumeIteration(stepIndex = 0, maxIterations = 2))
        assertFalse(flow.consumeIteration(stepIndex = 0, maxIterations = 2))
        // A different repeat position carries its own counter (nested loops don't share).
        assertTrue(flow.consumeIteration(stepIndex = 4, maxIterations = 2))
        assertFalse(flow.consumeControlHop(limit = 0))
    }

    @Test
    fun controlActionsAreTheLowercasedRunnerSpellings() {
        // Parse lowercases action strings before the runner bills them; the set must match.
        assertEquals(setOf("branch", "repeatuntil", "tryalternates"), ControlFlow.CONTROL_ACTIONS)
    }
}
