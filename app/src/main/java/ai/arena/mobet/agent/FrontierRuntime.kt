package ai.arena.mobet.agent

/** Result of temporal UI-settling analysis. */
data class SettlingResult(val settled: Boolean, val observation: AgentObservation?, val samples: Int, val reason: String)

/**
 * Requires structurally identical observations before planning. A deadline prevents animated or
 * hostile screens from holding the agent forever; deadline observations are marked unsettled and
 * may only be used to abstain/recover, never as proof of success.
 */
class ObservationStabilizer(
    private val requiredConsecutive: Int = 2,
    private val maxSamples: Int = 6
) {
    private var lastId: String? = null
    private var consecutive = 0
    private var samples = 0

    fun offer(observation: AgentObservation): SettlingResult {
        samples++
        consecutive = if (observation.screenId == lastId) consecutive + 1 else 1
        lastId = observation.screenId
        return when {
            consecutive >= requiredConsecutive -> SettlingResult(true, observation, samples, "UI structurally settled")
            samples >= maxSamples -> SettlingResult(false, observation, samples, "UI did not settle within observation budget")
            else -> SettlingResult(false, null, samples, "awaiting stable observation")
        }
    }

    fun reset() { lastId = null; consecutive = 0; samples = 0 }
}

data class SubgoalProgress(
    val index: Int,
    val subgoal: Subgoal,
    val preconditionsMet: Boolean,
    val completed: Boolean,
    val evidence: Set<String>
)

/** Explicit hierarchical execution state; transitions require observed evidence, not planner claims. */
class HierarchicalExecutor(private val plan: HierarchicalPlan) {
    private var index = 0
    private val completed = mutableSetOf<String>()
    val current: Subgoal? get() = plan.subgoals.getOrNull(index)
    val isComplete: Boolean get() = index >= plan.subgoals.size

    fun progress(facts: Set<String>, observedTransition: Boolean, actionLabel: String? = null): SubgoalProgress? {
        val active = current ?: return null
        val preconditions = active.preconditions.all { it.startsWith("package:") || it in completed }
        val explicit = active.completionEvidence.filter { it in facts || "text:$it" in facts }.toSet()
        val transitional = observedTransition && actionLabel != null && semanticOverlap(actionLabel, active.description)
        val done = preconditions && (explicit.isNotEmpty() || transitional)
        val result = SubgoalProgress(index, active, preconditions, done,
            explicit + if (transitional) setOf("verified-screen-transition") else emptySet())
        if (done) {
            completed += "subgoal:$index:complete"
            index++
        }
        return result
    }

    private fun semanticOverlap(a: String, b: String): Boolean {
        fun terms(v: String) = v.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return terms(a).intersect(terms(b)).isNotEmpty()
    }
}

/** Deterministic on-device assistant. It only emits structured hints over supplied candidate IDs. */
class LocalStructuredModelAssistant : ModelAssistant {
    override fun proposeSubgoals(goal: AgentGoal): List<ModelSubgoal> = goal.description
        .split(Regex("(?i)\\bthen\\b|;|\\n")).map(String::trim).filter(String::isNotBlank).take(12)
        .map { ModelSubgoal(it, "clause-preserving local decomposition", .8) }

    override fun rankSafeCandidates(
        goal: AgentGoal,
        observation: AgentObservation,
        allowedActionIds: Set<String>
    ): ModelRanking {
        val terms = tokens(goal.description + " " + goal.successFact)
        val ids = observation.actions.filter { it.id in allowedActionIds }
            .sortedWith(compareByDescending<AgentAction> { tokens(it.label).intersect(terms).size }
                .thenByDescending { it.confidence }.thenBy { it.id }).map { it.id }
        return ModelRanking(ids, if (ids.isEmpty()) 0.0 else .72)
    }

    private fun tokens(value: String) = value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
}
