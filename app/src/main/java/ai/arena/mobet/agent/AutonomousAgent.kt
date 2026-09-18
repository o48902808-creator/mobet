package ai.arena.mobet.agent

/** Node-free, privacy-minimized view of a device screen used by the reasoning layer. */
data class AgentObservation(
    val screenId: String,
    val packageName: String,
    val actions: List<AgentAction>,
    val facts: Set<String> = emptySet(),
    val evidence: List<ObservationEvidence> = emptyList(),
    val appVersion: String? = null,
    val observedAt: Long = System.currentTimeMillis()
)

enum class AgentActionKind { TAP, SCROLL, BACK }

data class AgentAction(
    val id: String,
    val label: String,
    val risk: Int = 0,
    val reversible: Boolean = true,
    val kind: AgentActionKind = AgentActionKind.TAP,
    /** Serialized accessibility selector; never arbitrary executable input. */
    val selector: String? = null,
    val confidence: Double = 1.0,
    val trust: ContentTrust = ContentTrust.STRUCTURAL
)

data class AgentGoal(
    val description: String,
    val successFact: String,
    val allowedPackage: String,
    val maxCycles: Int = 20,
    val maxRisk: Int = 29,
    val minConfidence: Double = 0.49,
    val lookaheadExpansions: Int = 32,
    val maxRuntimeMs: Long = 120_000,
    val allowOcrEvidence: Boolean = false,
    val allowModelAssistance: Boolean = false
)

data class ActionReceipt(val accepted: Boolean, val detail: String = "")

/** Android implementations retain final authority over whether an action runs. */
interface AgentDevice {
    fun observe(): AgentObservation
    fun act(action: AgentAction): ActionReceipt
    fun back(): ActionReceipt
}

data class TransitionExperience(
    val from: String,
    val actionId: String,
    val to: String,
    val progressed: Boolean,
    val packageName: String = "",
    val appVersion: String? = null,
    val observedAt: Long = System.currentTimeMillis(),
    val confidence: Double = 1.0,
    val selectorRepair: String? = null,
    val failure: FailureKind? = null
)

/** Bounded memory used for learning, cycle avoidance, and cross-run navigation priors. */
interface ExperienceStore {
    fun record(experience: TransitionExperience)
    fun transitionsFrom(screenId: String): List<TransitionExperience>
    fun markDeadEnd(screenId: String, actionId: String)
    fun isDeadEnd(screenId: String, actionId: String): Boolean
    fun recordRepair(packageName: String, oldSelector: String, newSelector: String, appVersion: String?) = Unit
    fun invalidate(packageName: String, appVersion: String?, screenId: String? = null) = Unit
    fun summary(): String = "Memory diagnostics unavailable"
}

class InMemoryExperienceStore(private val capacity: Int = 512) : ExperienceStore {
    private val transitions = ArrayDeque<TransitionExperience>()
    private val deadEnds = LinkedHashSet<Pair<String, String>>()

    override fun record(experience: TransitionExperience) {
        transitions.addLast(experience)
        while (transitions.size > capacity) transitions.removeFirst()
    }

    override fun transitionsFrom(screenId: String): List<TransitionExperience> =
        transitions.filter { it.from == screenId }

    override fun markDeadEnd(screenId: String, actionId: String) {
        deadEnds += screenId to actionId
        while (deadEnds.size > capacity) deadEnds.remove(deadEnds.first())
    }

    override fun isDeadEnd(screenId: String, actionId: String): Boolean = screenId to actionId in deadEnds
}

data class Deliberation(
    val action: AgentAction?,
    val reason: String,
    val shouldBacktrack: Boolean = false,
    val confidence: Double = 0.0
)

/** Deterministic utility ranking with strict candidate and lookahead budgets. */
class Deliberator(
    private val experience: ExperienceStore,
    private val modelAssistant: ModelAssistant? = null
) {
    fun choose(observation: AgentObservation, goal: AgentGoal, pathScreens: Set<String>): Deliberation {
        val candidates = observation.actions.asSequence()
            .filter { it.risk <= goal.maxRisk && it.trust != ContentTrust.UNTRUSTED_INSTRUCTION &&
                !experience.isDeadEnd(observation.screenId, it.id) }
            .take(MAX_CANDIDATES).toList()
        if (candidates.isEmpty()) return Deliberation(null, "no safe unexplored actions", true)

        val allHistory = experience.transitionsFrom(observation.screenId).groupBy { it.actionId }
        val known = allHistory.mapValues { (_, items) ->
            items.filter { it.progressed && it.to !in pathScreens }
        }
        val lookahead = BudgetedLookahead(experience).simulate(observation, goal)
            .groupBy { it.actionIds.first() }.mapValues { (_, paths) -> paths.maxOf { it.utility } }
        val goalTerms = tokenize(goal.description + " " + goal.successFact)
        val allowedIds = candidates.map { it.id }.toSet()
        val modelRanking = modelAssistant?.rankSafeCandidates(goal, observation, allowedIds)
            ?.let { ModelOutputValidator.validateRanking(it, allowedIds) }
        val modelOrder = modelRanking?.actionIds?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val ranked = candidates.map { action ->
            val semantic = tokenize(action.label).count { it in goalTerms } * 20.0
            val histories = known[action.id].orEmpty().take(goal.lookaheadExpansions.coerceIn(1, 64))
            val learned = histories.sumOf { it.confidence * 20.0 }.coerceAtMost(60.0)
            val reliability = ExperienceStatistics.reliability(allHistory[action.id].orEmpty())
            val reversible = if (action.reversible) 4.0 else -12.0
            val simulated = (lookahead[action.id] ?: 0.0) * 0.35
            // Model influence is deliberately capped at five utility points and can only reorder
            // candidates already accepted by deterministic safety filtering.
            val modelHint = modelOrder[action.id]?.let { (5.0 - it * .25).coerceAtLeast(0.0) } ?: 0.0
            val utility = semantic + learned + reliability.successProbability * reliability.confidence * 20 +
                simulated + modelHint + reversible - action.risk - (1.0 - action.confidence) * 20
            action to utility
        }.sortedWith(compareByDescending<Pair<AgentAction, Double>> { it.second }.thenBy { it.first.id })
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.second ?: (best.second - 20.0)
        val confidence = (0.5 + (best.second - second) / 100.0).coerceIn(0.0, 1.0) * best.first.confidence
        if (confidence < goal.minConfidence) {
            return Deliberation(null, "ambiguous candidates (${(confidence * 100).toInt()}% confidence); user input required", confidence = confidence)
        }
        return Deliberation(best.first, "highest bounded utility ${best.second.toInt()}", confidence = confidence)
    }

    private fun tokenize(value: String): Set<String> = value.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length > 1 }.toSet()

    private companion object { const val MAX_CANDIDATES = 24 }
}

enum class AgentStatus { SUCCEEDED, EXHAUSTED, BLOCKED, DEVICE_REJECTED, ABSTAINED }

data class AgentRunResult(
    val status: AgentStatus,
    val cycles: Int,
    val actions: List<String>,
    val explanation: String
)

/** Bounded observe–deliberate–act–verify loop with local replanning and DFS backtracking. */
class AutonomousAgent(
    private val device: AgentDevice,
    private val experience: ExperienceStore,
    private val deliberator: Deliberator = Deliberator(experience)
) {
    fun run(goal: AgentGoal): AgentRunResult {
        val actions = mutableListOf<String>()
        val frames = ArrayDeque<Frame>()
        var cycles = 0
        val startedAt = System.currentTimeMillis()
        var observation = device.observe()

        while (true) {
            // Verify the final observation before budgets prevent another action. Package
            // provenance remains first so a foreign app cannot spoof expected success text.
            if (observation.packageName != goal.allowedPackage) return AgentRunResult(AgentStatus.BLOCKED, cycles, actions, "package boundary crossed")
            if (goal.successFact in observation.facts) return AgentRunResult(AgentStatus.SUCCEEDED, cycles, actions, "goal verified")
            if (cycles >= goal.maxCycles) return AgentRunResult(AgentStatus.EXHAUSTED, cycles, actions, "cycle budget exhausted")
            if (System.currentTimeMillis() - startedAt > goal.maxRuntimeMs)
                return AgentRunResult(AgentStatus.EXHAUSTED, cycles, actions, "runtime budget exhausted")

            val path = frames.map { it.screenId }.toSet() + observation.screenId
            val decision = deliberator.choose(observation, goal, path)
            val action = decision.action
            if (action == null) {
                if (!decision.shouldBacktrack) return AgentRunResult(AgentStatus.ABSTAINED, cycles, actions, decision.reason)
                val failed = frames.removeLastOrNull()
                    ?: return AgentRunResult(AgentStatus.EXHAUSTED, cycles, actions, decision.reason)
                experience.markDeadEnd(failed.screenId, failed.actionId)
                val receipt = device.back(); cycles++; actions += "back"
                if (!receipt.accepted) return AgentRunResult(AgentStatus.DEVICE_REJECTED, cycles, actions, receipt.detail)
                observation = device.observe(); continue
            }

            val before = observation
            val receipt = device.act(action); cycles++; actions += action.id
            if (!receipt.accepted) {
                experience.markDeadEnd(before.screenId, action.id)
                observation = device.observe(); continue
            }
            val after = device.observe()
            val progressed = after.screenId != before.screenId || after.facts != before.facts
            experience.record(TransitionExperience(before.screenId, action.id, after.screenId, progressed,
                before.packageName, before.appVersion, confidence = decision.confidence))
            if (!progressed || after.screenId in path) experience.markDeadEnd(before.screenId, action.id)
            else frames.addLast(Frame(before.screenId, action.id))
            observation = after
        }
    }

    private data class Frame(val screenId: String, val actionId: String)
}
