package ai.arena.mobet.agent

/** A small, platform-neutral view of a device screen for deliberation and tests. */
data class AgentObservation(
    val screenId: String,
    val packageName: String,
    val actions: List<AgentAction>,
    val facts: Set<String> = emptySet()
)

data class AgentAction(
    val id: String,
    val label: String,
    val risk: Int = 0,
    val reversible: Boolean = true
)

data class AgentGoal(
    val description: String,
    val successFact: String,
    val allowedPackage: String,
    val maxCycles: Int = 20,
    val maxRisk: Int = 29
)

data class ActionReceipt(val accepted: Boolean, val detail: String = "")

/** Android integration seam. Implementations retain final authority over whether an action runs. */
interface AgentDevice {
    fun observe(): AgentObservation
    fun act(action: AgentAction): ActionReceipt
    fun back(): ActionReceipt
}

data class TransitionExperience(
    val from: String,
    val actionId: String,
    val to: String,
    val progressed: Boolean
)

/** Bounded memory used for learning, cycle avoidance, and cross-run navigation priors. */
interface ExperienceStore {
    fun record(experience: TransitionExperience)
    fun transitionsFrom(screenId: String): List<TransitionExperience>
    fun markDeadEnd(screenId: String, actionId: String)
    fun isDeadEnd(screenId: String, actionId: String): Boolean
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

    override fun isDeadEnd(screenId: String, actionId: String): Boolean =
        screenId to actionId in deadEnds
}

data class Deliberation(
    val action: AgentAction?,
    val reason: String,
    val shouldBacktrack: Boolean = false
)

/**
 * Deterministic uncertainty-aware policy. It exploits transitions known to make progress, then
 * explores safe reversible controls. Failed branches are suppressed by dead-end memory.
 */
class Deliberator(private val experience: ExperienceStore) {
    fun choose(observation: AgentObservation, goal: AgentGoal, pathScreens: Set<String>): Deliberation {
        val candidates = observation.actions.filter {
            it.risk <= goal.maxRisk && !experience.isDeadEnd(observation.screenId, it.id)
        }
        if (candidates.isEmpty()) return Deliberation(null, "no safe unexplored actions", true)

        val known = experience.transitionsFrom(observation.screenId)
            .filter { it.progressed && it.to !in pathScreens }
            .groupingBy { it.actionId }.eachCount()
        val goalTerms = tokenize(goal.description + " " + goal.successFact)
        val ranked = candidates.map { action ->
            val semantic = tokenize(action.label).count { it in goalTerms } * 20
            val learned = (known[action.id] ?: 0) * 30
            val reversible = if (action.reversible) 4 else 0
            val uncertaintyPenalty = action.risk
            action to (semantic + learned + reversible - uncertaintyPenalty)
        }.sortedWith(compareByDescending<Pair<AgentAction, Int>> { it.second }.thenBy { it.first.id })
        return Deliberation(ranked.first().first, "highest bounded utility ${ranked.first().second}")
    }

    private fun tokenize(value: String): Set<String> =
        value.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }.toSet()
}

enum class AgentStatus { SUCCEEDED, EXHAUSTED, BLOCKED, DEVICE_REJECTED }

data class AgentRunResult(
    val status: AgentStatus,
    val cycles: Int,
    val actions: List<String>,
    val explanation: String
)

/**
 * Bounded observe-deliberate-act-verify loop with DFS-style backtracking. Every action is verified
 * by a fresh observation; unchanged and exhausted branches become durable dead ends.
 */
class AutonomousAgent(
    private val device: AgentDevice,
    private val experience: ExperienceStore,
    private val deliberator: Deliberator = Deliberator(experience)
) {
    fun run(goal: AgentGoal): AgentRunResult {
        val actions = mutableListOf<String>()
        val frames = ArrayDeque<Frame>()
        var cycles = 0
        var observation = device.observe()

        while (cycles < goal.maxCycles) {
            if (goal.successFact in observation.facts) {
                return AgentRunResult(AgentStatus.SUCCEEDED, cycles, actions, "goal verified")
            }
            if (observation.packageName != goal.allowedPackage) {
                return AgentRunResult(AgentStatus.BLOCKED, cycles, actions, "package boundary crossed")
            }

            val path = frames.map { it.screenId }.toSet() + observation.screenId
            val decision = deliberator.choose(observation, goal, path)
            val action = decision.action
            if (action == null) {
                val failed = frames.removeLastOrNull()
                    ?: return AgentRunResult(AgentStatus.EXHAUSTED, cycles, actions, decision.reason)
                experience.markDeadEnd(failed.screenId, failed.actionId)
                val receipt = device.back()
                cycles++
                actions += "back"
                if (!receipt.accepted) return AgentRunResult(AgentStatus.DEVICE_REJECTED, cycles, actions, receipt.detail)
                observation = device.observe()
                continue
            }

            val before = observation
            val receipt = device.act(action)
            cycles++
            actions += action.id
            if (!receipt.accepted) {
                experience.markDeadEnd(before.screenId, action.id)
                observation = device.observe()
                continue
            }
            val after = device.observe()
            val progressed = after.screenId != before.screenId || after.facts != before.facts
            experience.record(TransitionExperience(before.screenId, action.id, after.screenId, progressed))
            if (!progressed || after.screenId in path) {
                experience.markDeadEnd(before.screenId, action.id)
            } else {
                frames.addLast(Frame(before.screenId, action.id))
            }
            observation = after
        }
        return AgentRunResult(AgentStatus.EXHAUSTED, cycles, actions, "cycle budget exhausted")
    }

    private data class Frame(val screenId: String, val actionId: String)
}
