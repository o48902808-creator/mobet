package ai.arena.mobet.synthesis

import ai.arena.mobet.agent.AgentAction
import ai.arena.mobet.agent.AgentActionKind
import ai.arena.mobet.agent.AgentGoal
import ai.arena.mobet.agent.AgentRunResult
import ai.arena.mobet.agent.AgentStatus
import ai.arena.mobet.agent.AgentTransition
import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCrystallizerTest {

    private val goal = AgentGoal(
        description = "turn on aeroplane mode",
        successFact = "Aeroplane mode is on",
        allowedPackage = "com.android.settings"
    )

    private fun transition(id: String, label: String, selector: String?, kind: AgentActionKind = AgentActionKind.TAP) =
        AgentTransition(
            fromScreenId = "screen-$id",
            toScreenId = "screen-$id-next",
            action = AgentAction(id = id, label = label, kind = kind, selector = selector)
        )

    private fun success(vararg path: AgentTransition) = AgentRunResult(
        status = AgentStatus.SUCCEEDED,
        cycles = 6,
        actions = path.map { it.action.id } + listOf("explored", "back"),
        explanation = "goal verified",
        successPath = path.toList()
    )

    @Test
    fun successfulRunBecomesAReplayableValidatedWorkflow() {
        val result = AgentCrystallizer.crystallize(
            success(
                transition("a", "Network & internet", "text: Network & internet"),
                transition("b", "Aeroplane mode", "viewId: com.android.settings:id/airplane")
            ),
            goal
        ).getOrThrow()

        assertEquals("com.android.settings", result.workflow.packageName)
        assertEquals(listOf("wait", "tap", "wait", "tap"), result.workflow.steps.map { it.action })
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
    }

    @Test
    fun replayAssertsTheSameCompletionEvidenceTheAgentVerified() {
        val result = AgentCrystallizer.crystallize(
            success(transition("a", "Aeroplane mode", "text: Aeroplane mode")),
            goal
        ).getOrThrow()
        assertEquals("Aeroplane mode is on", result.workflow.steps.last().expect!!.textPresent)
    }

    @Test
    fun onlyVerifiedRunsCrystallize() {
        val abstained = AgentRunResult(
            status = AgentStatus.ABSTAINED,
            cycles = 3,
            actions = listOf("a"),
            explanation = "ambiguous candidates",
            successPath = listOf(transition("a", "Network", "text: Network"))
        )
        val result = AgentCrystallizer.crystallize(abstained, goal)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("successful run"))
    }

    @Test
    fun aRunThatTookNoActionHasNothingToReplay() {
        val result = AgentCrystallizer.crystallize(
            AgentRunResult(AgentStatus.SUCCEEDED, 1, emptyList(), "goal verified"),
            goal
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun anUnreplayableActionFailsLoudlyRatherThanShorteningTheRoute() {
        val result = AgentCrystallizer.crystallize(
            success(
                transition("a", "Network", "text: Network"),
                transition("b", "Mystery control", selector = null)
            ),
            goal
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("replayed faithfully"))
    }

    @Test
    fun crystallizedPlansAreRiskGatedLikeAnyOtherPlan() {
        val destructive = AgentCrystallizer.crystallize(
            success(transition("a", "Delete account", "text: Delete account")),
            goal.copy(successFact = "Account deleted")
        ).getOrThrow()
        val actions = destructive.workflow.steps.map { it.action }
        assertEquals("confirm", actions[actions.indexOf("tap") - 1])
        assertTrue(PlanValidator.validate(destructive.workflow).isEmpty())
    }

    @Test
    fun backStepsSurviveWithoutASelector() {
        val result = AgentCrystallizer.crystallize(
            success(
                transition("a", "Network", "text: Network"),
                transition("b", "back", selector = null, kind = AgentActionKind.BACK)
            ),
            goal
        ).getOrThrow()
        assertTrue(result.workflow.steps.any { it.action == "back" })
    }

    @Test
    fun explorationIsReportedAsDiscarded() {
        val result = AgentCrystallizer.crystallize(
            success(transition("a", "Network", "text: Network")),
            goal
        ).getOrThrow()
        assertTrue(result.notes.any { it.stage == "crystallize" && it.detail.contains("discarded") })
    }
}
