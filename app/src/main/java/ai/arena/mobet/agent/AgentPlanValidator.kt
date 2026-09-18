package ai.arena.mobet.agent

/** Static counterfactual checks applied before an autonomous proposal reaches a device. */
object AgentPlanValidator {
    data class Violation(val index: Int, val message: String)

    fun validate(goal: AgentGoal, proposed: List<AgentAction>): List<Violation> = buildList {
        if (proposed.size > goal.maxCycles) add(Violation(-1, "proposal exceeds cycle budget"))
        if (goal.lookaheadExpansions !in 1..128) add(Violation(-1, "lookahead expansion budget must be 1–128"))
        proposed.forEachIndexed { index, action ->
            if (action.risk !in 0..100 || action.risk > goal.maxRisk) add(Violation(index, "risk ${action.risk} exceeds ${goal.maxRisk}"))
            if (action.confidence !in 0.0..1.0) add(Violation(index, "invalid confidence"))
            if (!action.reversible && index != proposed.lastIndex) {
                add(Violation(index, "irreversible action must be terminal"))
            }
        }
    }
}

/**
 * Best-first search over a learned transition graph. The returned route is only a proposal and is
 * still verified one action at a time by [AutonomousAgent]. Cycles and remembered dead ends are
 * excluded and search expansion is explicitly bounded.
 */
class ExperienceNavigator(private val experience: ExperienceStore) {
    fun route(from: String, target: String, maxExpansions: Int = 64): List<String>? {
        data class Node(val screen: String, val route: List<String>, val visited: Set<String>)
        val queue = ArrayDeque<Node>()
        queue += Node(from, emptyList(), setOf(from))
        var expansions = 0
        while (queue.isNotEmpty() && expansions++ < maxExpansions) {
            val node = queue.removeFirst()
            if (node.screen == target) return node.route
            experience.transitionsFrom(node.screen)
                .filter { it.progressed && it.to !in node.visited && !experience.isDeadEnd(it.from, it.actionId) }
                .sortedWith(compareBy<TransitionExperience> { it.actionId }.thenBy { it.to })
                .forEach { edge ->
                    queue += Node(edge.to, node.route + edge.actionId, node.visited + edge.to)
                }
        }
        return null
    }
}
