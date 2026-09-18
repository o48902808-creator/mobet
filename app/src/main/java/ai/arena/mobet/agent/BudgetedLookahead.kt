package ai.arena.mobet.agent

/** A counterfactual route scored without touching the device. */
data class SimulatedPath(
    val actionIds: List<String>,
    val predictedScreens: List<String>,
    val utility: Double,
    val expansionsUsed: Int
)

/**
 * Strictly budgeted search over learned transitions. Utility rewards confidence and observed
 * progress while penalizing path cost, risk, irreversibility, and cycles. Results are proposals;
 * live observations and the guarded execution gateway remain authoritative.
 */
class BudgetedLookahead(private val experience: ExperienceStore) {
    fun simulate(observation: AgentObservation, goal: AgentGoal): List<SimulatedPath> {
        data class Node(val screen: String, val first: AgentAction, val actions: List<String>,
                        val screens: List<String>, val visited: Set<String>, val utility: Double)
        val budget = goal.lookaheadExpansions.coerceIn(1, 128)
        val queue = ArrayDeque<Node>()
        observation.actions.filter { it.risk <= goal.maxRisk }.take(24).forEach { action ->
            val base = action.confidence * 18 - action.risk - if (action.reversible) 0 else 20
            queue += Node(observation.screenId, action, listOf(action.id), emptyList(),
                setOf(observation.screenId), base)
        }
        val results = mutableListOf<SimulatedPath>()
        var expansions = 0
        while (queue.isNotEmpty() && expansions < budget) {
            val node = queue.removeFirst(); expansions++
            val edges = experience.transitionsFrom(node.screen).filter {
                it.actionId == node.actions.last() && it.progressed && it.to !in node.visited
            }.take(6)
            if (edges.isEmpty() || node.actions.size >= MAX_DEPTH) {
                results += SimulatedPath(node.actions, node.screens, node.utility - node.actions.size * 1.5, expansions)
                continue
            }
            edges.forEach { edge ->
                val nextUtility = node.utility + edge.confidence * 25 - node.actions.size * 2
                val nextActions = experience.transitionsFrom(edge.to).filter { it.progressed && it.to !in node.visited }
                    .sortedByDescending { it.confidence }.take(4)
                if (nextActions.isEmpty()) {
                    results += SimulatedPath(node.actions, node.screens + edge.to, nextUtility, expansions)
                } else nextActions.forEach { next ->
                    queue += Node(edge.to, node.first, node.actions + next.actionId,
                        node.screens + edge.to, node.visited + edge.to, nextUtility)
                }
            }
        }
        return results.sortedByDescending { it.utility }.take(24)
    }

    private companion object { const val MAX_DEPTH = 5 }
}
